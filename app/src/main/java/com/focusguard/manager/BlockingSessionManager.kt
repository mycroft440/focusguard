package com.focusguard.manager

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.room.withTransaction
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.PredefinedWebsites
import com.focusguard.data.RecoveryProtectionPreset
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.receiver.BlockingScheduleCalculator
import com.focusguard.receiver.BlockingScheduleReceiver
import com.focusguard.security.AuthManager
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DopamineStartPolicy
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.utils.AppUsageLimitActivationUsage
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BlockingSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deactivationCredentialManager: DeactivationCredentialManager
) {

    class BlockingProtectionUnavailableException(
        val reason: Reason
    ) : IllegalStateException(reason.name) {
        enum class Reason {
            PROTECTION_PERMISSIONS_REQUIRED,

            /**
             * No master credential configured. Password blocks and dopamine fasts
             * cannot be armed without one — see [MasterCredentialPolicy].
             */
            MASTER_CREDENTIAL_REQUIRED
        }
    }

    /**
     * Refuses to arm a block until the master credential exists.
     *
     * Enforced here rather than only in the UI so no creation path — wizard,
     * protection setup, or a future caller — can arm a block that the user has no
     * authenticated way to manage afterwards.
     */
    private fun ensureMasterCredentialFor(sessionType: String) {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = sessionType,
            hasMasterCredential = deactivationCredentialManager.hasCredential()
        )
        if (gate == MasterCredentialPolicy.CreationGate.MASTER_CREDENTIAL_REQUIRED) {
            throw BlockingProtectionUnavailableException(
                BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED
            )
        }
    }

    /**
     * Read-only snapshot of what is currently blocked, grouped the way the user
     * chose it — by the kind of protection, not by app.
     *
     * Separate from [ConfiguredBlockedTargets], which answers "can I still add
     * this target?" during setup. This one answers "what is holding right now?"
     * and therefore carries the deadline a dopamine fast needs.
     */
    data class BlockOverview(
        val passwordEntries: List<Entry> = emptyList(),
        val dailyLimitEntries: List<Entry> = emptyList(),
        val dopamineFastEntries: List<Entry> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = passwordEntries.isEmpty() &&
                dailyLimitEntries.isEmpty() &&
                dopamineFastEntries.isEmpty()

        /**
         * @param identifier package name for an app, normalized rule for a site.
         * @param dailyLimitMinutes only set for daily-limit entries.
         * @param unlockAtMillis only set for time-bound blocks; null means the
         *   block runs until the user ends it.
         */
        data class Entry(
            val identifier: String,
            val isWebsite: Boolean,
            val dailyLimitMinutes: Int? = null,
            val unlockAtMillis: Long? = null
        )
    }

    /**
     * Builds the overview shown by the "check blocks" screen.
     *
     * App labels are deliberately not resolved here: turning a package name into a
     * display name needs a PackageManager lookup per entry, which belongs to the
     * UI layer where it can be done lazily for the rows actually on screen.
     */
    suspend fun getBlockOverview(): BlockOverview = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
            .filter { it.endTime == null || it.endTime > now }

        val passwordIds = activeSessions
            .filter { it.sessionType == "PASSWORD" }
            .map { it.id }

        // A fast can span several sessions; each target shows the deadline of the
        // session that actually holds it, so two fasts started apart do not report
        // a single misleading date.
        val fastSessions = activeSessions.filter {
            MasterCredentialPolicy.isIrreversibleSessionType(it.sessionType)
        }

        val passwordEntries = buildEntries(
            appPackages = getAppsForSessions(passwordIds),
            websiteRules = getSitesForSessions(passwordIds)
        )

        val fastEntries = fastSessions.flatMap { session ->
            buildEntries(
                appPackages = getAppsForSessions(listOf(session.id)),
                websiteRules = getSitesForSessions(listOf(session.id)),
                unlockAtMillis = session.endTime
            )
        }.distinctBy { it.identifier }

        val appLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
        val websiteLimits = database.websiteUsageLimitDao().getAllStatic().filter { it.isEnabled }
        val limitEntries = appLimits.map { limit ->
            BlockOverview.Entry(
                identifier = limit.packageName,
                isWebsite = false,
                dailyLimitMinutes = limit.dailyLimitMinutes
            )
        } + websiteLimits.map { limit ->
            BlockOverview.Entry(
                identifier = WebsiteBlocker.normalizeRule(limit.domain),
                isWebsite = true,
                dailyLimitMinutes = limit.dailyLimitMinutes
            )
        }

        BlockOverview(
            passwordEntries = passwordEntries.sortedBy { it.identifier },
            dailyLimitEntries = limitEntries.sortedBy { it.identifier },
            dopamineFastEntries = fastEntries.sortedBy { it.identifier }
        )
    }

    private fun buildEntries(
        appPackages: List<String>,
        websiteRules: List<String>,
        unlockAtMillis: Long? = null
    ): List<BlockOverview.Entry> {
        val apps = appPackages.distinct().map { packageName ->
            BlockOverview.Entry(
                identifier = packageName,
                isWebsite = false,
                unlockAtMillis = unlockAtMillis
            )
        }
        val sites = WebsiteBlocker.normalizeRules(websiteRules).map { rule ->
            BlockOverview.Entry(
                identifier = rule,
                isWebsite = true,
                unlockAtMillis = unlockAtMillis
            )
        }
        return apps + sites
    }

    data class ConfiguredBlockedTargets(
        val passwordAppPackageNames: Set<String> = emptySet(),
        val passwordWebsiteRules: Set<String> = emptySet(),
        val limitedAppPackageNames: Set<String> = emptySet(),
        val limitedWebsiteRules: Set<String> = emptySet(),
        val exclusiveAppPackageNames: Set<String> = emptySet(),
        val exclusiveWebsiteRules: Set<String> = emptySet(),
        val unavailableAppPackageNames: Set<String> = emptySet(),
        val unavailableWebsiteRules: Set<String> = emptySet()
    ) {
        val allAppPackageNames: Set<String>
            get() = passwordAppPackageNames + limitedAppPackageNames + exclusiveAppPackageNames

        val allWebsiteRules: Set<String>
            get() = passwordWebsiteRules + limitedWebsiteRules + exclusiveWebsiteRules
    }

    data class DailyLimitAppTarget(
        val packageName: String,
        val appName: String
    )

    enum class EndSessionResult {
        ENDED,
        NOT_FOUND,
        POMODORO_NOT_REVOCABLE,
        TIME_NOT_REVOCABLE,
        FAILED
    }

    enum class LimitUnlockResult {
        UNLOCKED,
        WRONG_PASSWORD,
        NOT_FOUND,
        FAILED
    }

    companion object {
        private const val STATE_PREFERENCES = "blocking_session_manager_state"
        private const val PREVIOUS_DND_FILTER_KEY = "previous_dnd_filter"
        private const val POLICY_RECONCILIATION_INTERVAL_MILLIS = 15L * 60L * 1_000L

        @Volatile
        private var legacyInstance: BlockingSessionManager? = null

        fun getInstance(context: Context): BlockingSessionManager {
            return try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BlockingSessionManagerEntryPoint::class.java
                )
                entryPoint.blockingSessionManager()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Hilt indisponível; usando singleton legado",
                    error
                )
                synchronized(this) {
                    legacyInstance ?: BlockingSessionManager(
                        context.applicationContext,
                        DeactivationCredentialManager(context.applicationContext)
                    ).also { legacyInstance = it }
                }
            }
        }

        internal fun combineConfiguredBlockedTargets(
            passwordSessionAppPackages: Collection<String>,
            passwordSessionWebsiteRules: Collection<String>,
            exclusiveSessionAppPackages: Collection<String>,
            exclusiveSessionWebsiteRules: Collection<String>,
            limitedAppPackages: Collection<String>,
            limitedWebsiteRules: Collection<String>
        ): ConfiguredBlockedTargets {
            // Aplicativo e site são superfícies independentes. Bloquear o pacote
            // do YouTube não cobre youtube.com no navegador, e bloquear o domínio
            // não impede abrir o aplicativo. Portanto, não converta pacotes em
            // domínios (nem domínios em pacotes) ao procurar duplicados.
            val passwordWebsiteRules =
                WebsiteBlocker.normalizeRules(passwordSessionWebsiteRules)
            val normalizedLimitedWebsiteRules =
                WebsiteBlocker.normalizeRules(limitedWebsiteRules)
            val exclusiveWebsiteRules =
                WebsiteBlocker.normalizeRules(exclusiveSessionWebsiteRules)
            val passwordAppPackageNames =
                normalizeConfiguredAppPackages(passwordSessionAppPackages)
            val normalizedLimitedAppPackages =
                normalizeConfiguredAppPackages(limitedAppPackages)
            val exclusiveAppPackageNames =
                normalizeConfiguredAppPackages(exclusiveSessionAppPackages)
            val allWebsiteRules = WebsiteBlocker.normalizeRules(
                passwordWebsiteRules + normalizedLimitedWebsiteRules + exclusiveWebsiteRules
            )
            val unavailableWebsiteRules = allWebsiteRules.filterTo(linkedSetOf()) { candidate ->
                isWebsiteRuleCoveredBy(candidate, exclusiveWebsiteRules) ||
                    (
                        isWebsiteRuleCoveredBy(candidate, passwordWebsiteRules) &&
                            isWebsiteRuleCoveredBy(candidate, normalizedLimitedWebsiteRules)
                        )
            }
            val unavailableAppPackageNames = (
                exclusiveAppPackageNames +
                    passwordAppPackageNames.intersect(normalizedLimitedAppPackages)
                ).filter(String::isNotBlank).toSet()

            return ConfiguredBlockedTargets(
                passwordAppPackageNames = passwordAppPackageNames,
                passwordWebsiteRules = passwordWebsiteRules,
                limitedAppPackageNames = normalizedLimitedAppPackages,
                limitedWebsiteRules = normalizedLimitedWebsiteRules,
                exclusiveAppPackageNames = exclusiveAppPackageNames,
                exclusiveWebsiteRules = exclusiveWebsiteRules,
                unavailableAppPackageNames = unavailableAppPackageNames,
                unavailableWebsiteRules = unavailableWebsiteRules
            )
        }

        private fun normalizeConfiguredAppPackages(
            packages: Collection<String>
        ): Set<String> = packages.filter(String::isNotBlank).toSet()

        internal fun isWebsiteRuleCoveredBy(
            candidate: String,
            configuredRules: Collection<String>
        ): Boolean {
            val normalizedCandidate = WebsiteBlocker.normalizeRule(candidate)
            if (normalizedCandidate.isEmpty()) return false

            val normalizedConfigured = WebsiteBlocker.normalizeRules(configuredRules)
            if (normalizedCandidate in normalizedConfigured) return true

            return WebsiteBlocker.findMatchingRule(
                normalizedCandidate,
                normalizedConfigured
            ) != null
        }

        internal fun participatesInBlocking(session: BlockSession): Boolean {
            return session.sessionType != "POMODORO" || session.isBlockingEnabled
        }

        internal fun matchesBlockedTarget(
            blockedPackage: String?,
            blockedDomain: String?,
            sessionApps: Collection<String>,
            sessionSites: Collection<String>
        ): Boolean {
            return (!blockedPackage.isNullOrBlank() && blockedPackage in sessionApps) ||
                (!blockedDomain.isNullOrBlank() &&
                WebsiteBlocker.isUrlBlocked(blockedDomain, sessionSites))
        }

        internal fun shouldArmSelfProtection(
            hasEnforcingSessions: Boolean,
            hasBlockedApps: Boolean,
            hasBlockedSites: Boolean,
            adultFilterEnabled: Boolean,
            focusModeActive: Boolean = false
        ): Boolean = hasEnforcingSessions ||
            hasBlockedApps ||
            hasBlockedSites ||
            adultFilterEnabled ||
            focusModeActive
        /**
         * Device Owner package suspension must not swallow an interactive
         * PASSWORD attempt before Accessibility can present its credential UI.
         * PASSWORD stays launchable only while no stronger protection owns it.
         */
        internal fun packagesForDeviceOwnerSuspension(
            enforcedPackages: Collection<String>,
            passwordSessionPackages: Collection<String>,
            strongerProtectionPackages: Collection<String>,
            strictPomodoro: Boolean = false
        ): List<String> {
            val enforced = enforcedPackages.filter(String::isNotBlank).distinct()
            if (strictPomodoro) return enforced

            val passwordTargets = passwordSessionPackages
                .filter(String::isNotBlank)
                .toSet()
            if (passwordTargets.isEmpty()) return enforced

            val strongerTargets = strongerProtectionPackages
                .filter(String::isNotBlank)
                .toSet()
            return enforced.filter { packageName ->
                packageName !in passwordTargets || packageName in strongerTargets
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface BlockingSessionManagerEntryPoint {
        fun blockingSessionManager(): BlockingSessionManager
    }

    private val database = AppDatabase.getDatabase(context)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enforcementMutex = Mutex()
    private val statePreferences = context.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    val activeSessionsFlow: Flow<List<BlockSession>> =
        database.blockSessionDao().getAllActiveSessions()

    val isBlockingActiveFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        sessions.any { participatesInBlocking(it) && isCurrentlyInBlockingWindow(it) }
    }

    /**
     * Desinstalar só pode ser impedido por uma proteção sem saída antecipada por
     * senha. Sessões PASSWORD e limites PASSWORD ficam deliberadamente fora:
     * neles a própria senha mestra é a saída autorizada.
     */
    val isUninstallBlockedByTimeFlow: Flow<Boolean> = combine(
        activeSessionsFlow,
        database.appUsageLimitDao().getAll(),
        database.websiteUsageLimitDao().getAll()
    ) { sessions, appLimits, websiteLimits ->
        val now = System.currentTimeMillis()
        sessions.any { session ->
            MasterCredentialPolicy.blocksUninstall(session.sessionType) &&
                MasterCredentialPolicy.isTimeCommitmentActive(
                    sessionType = session.sessionType,
                    isActive = session.isActive,
                    endTime = session.endTime,
                    nowMillis = now
                )
        } || appLimits.any { limit ->
            limit.isEnabled && MasterCredentialPolicy.isTimeHardened(
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        } || websiteLimits.any { limit ->
            limit.isEnabled && MasterCredentialPolicy.isTimeHardened(
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }
    }

    val hasRegisteredSessionFlow: Flow<Boolean> = activeSessionsFlow.map { it.isNotEmpty() }

    val sessionDetailsFlow: Flow<String> = activeSessionsFlow.map { sessions ->
        if (sessions.isEmpty()) return@map "Nenhuma sessão ativa"
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        buildString {
            appendLine("=== Sessões Ativas (${sessions.size}) ===")
            sessions.forEachIndexed { index, session ->
                appendLine("Sessão #${index + 1} (${session.sessionType})")
                appendLine(if (session.isFixed24h) "Modo: FIXO 24H" else "Modo: AGENDADO")
                session.endTime?.let { appendLine("Término: ${formatter.format(it)}") }
                appendLine("---")
            }
        }
    }

    /**
     * Returns targets grouped by protection mode.
     *
     * Password and daily-limit protection may coexist. Time/Pomodoro sessions are
     * exclusive, and targets that already have both compatible modes are exposed
     * as unavailable for an additional protection.
     */
    suspend fun getConfiguredBlockedTargets(): ConfiguredBlockedTargets =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val configuredSessions = database.blockSessionDao()
                .getAllActiveSessionsStatic()
                .filter { session -> session.endTime == null || session.endTime > now }
            val passwordSessionIds = configuredSessions
                .filter { it.sessionType == "PASSWORD" }
                .map { it.id }
            val exclusiveSessionIds = configuredSessions
                .filter { it.sessionType != "PASSWORD" }
                .map { it.id }

            combineConfiguredBlockedTargets(
                passwordSessionAppPackages = getAppsForSessions(passwordSessionIds),
                passwordSessionWebsiteRules = getSitesForSessions(passwordSessionIds),
                exclusiveSessionAppPackages = getAppsForSessions(exclusiveSessionIds),
                exclusiveSessionWebsiteRules = getSitesForSessions(exclusiveSessionIds),
                limitedAppPackages = database.appUsageLimitDao()
                    .getAllActiveLimitsStatic()
                    .map { it.packageName },
                limitedWebsiteRules = database.websiteUsageLimitDao()
                    .getAllStatic()
                    .filter { it.isEnabled }
                    .map { it.domain }
            )
        }

    /**
     * Saves daily limits with either automatic daily release or master-password
     * release. Password mode never creates an always-on app block: it applies
     * only after the daily allowance is exhausted.
     */
    suspend fun configureDailyLimits(
        apps: List<DailyLimitAppTarget>,
        sites: List<String>,
        dailyLimitMinutes: Int,
        addPasswordProtection: Boolean
    ) = withContext(Dispatchers.IO) {
        ensureBlockingPermissionsReady()
        require(dailyLimitMinutes in 1..24 * 60)
        // Checado antes da transação para não persistir um modo PASSWORD sem a
        // credencial canônica capaz de liberá-lo.
        if (addPasswordProtection) {
            ensureMasterCredentialFor("PASSWORD")
        }
        val appTargets = apps
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
        // Um limite conta tempo gasto num alvo, e palavra não é alvo de tempo:
        // sem este filtro, uma palavra digitada virava um limite `keyword:` que
        // bloqueava todo host que a contivesse ao estourar. Ver BlockTargetPolicy.
        val normalizedSites = BlockTargetPolicy.acceptedRules(
            kinds = BlockTargetPolicy.DAILY_LIMIT,
            rules = sites
        )

        database.withTransaction {
            // Os DAOs inserem com REPLACE, então reescrever um alvo que já tinha
            // limite apagaria o modo de proteção dele. Preservar deixa o limite
            // continuar removível pela credencial que o usuário configurou.
            val existingAppLimits = database.appUsageLimitDao()
                .getAllStatic()
                .associateBy { it.packageName }
            val existingSiteLimits = database.websiteUsageLimitDao()
                .getAllStatic()
                .associateBy { WebsiteBlocker.normalizeRule(it.domain) }

            appTargets.forEach { app ->
                val existing = existingAppLimits[app.packageName]
                val lockMode = if (addPasswordProtection) {
                    MasterCredentialPolicy.LOCK_MODE_PASSWORD
                } else {
                    existing?.lockMode ?: MasterCredentialPolicy.LOCK_MODE_NONE
                }
                database.appUsageLimitDao().insert(
                    AppUsageLimit(
                        packageName = app.packageName,
                        appName = app.appName,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true,
                        lockMode = lockMode,
                        lockPasswordHash = null,
                        lockUntilTimestamp = if (
                            lockMode == MasterCredentialPolicy.LOCK_MODE_PASSWORD
                        ) null else existing?.lockUntilTimestamp,
                        preventOpeningAfterLimit = true,
                        unlockWithPassword =
                            lockMode == MasterCredentialPolicy.LOCK_MODE_PASSWORD
                    )
                )
            }
            normalizedSites.forEach { rule ->
                val existing = existingSiteLimits[rule]
                val lockMode = if (addPasswordProtection) {
                    MasterCredentialPolicy.LOCK_MODE_PASSWORD
                } else {
                    existing?.lockMode ?: MasterCredentialPolicy.LOCK_MODE_NONE
                }
                database.websiteUsageLimitDao().insert(
                    WebsiteUsageLimit(
                        domain = rule,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true,
                        lockMode = lockMode,
                        lockPasswordHash = null,
                        lockUntilTimestamp = if (
                            lockMode == MasterCredentialPolicy.LOCK_MODE_PASSWORD
                        ) null else existing?.lockUntilTimestamp
                    )
                )
            }
        }

        checkAndEnforceOrThrow()
    }

    suspend fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        try {
            ensureBlockingPermissionsReady()
            ensureMasterCredentialFor("PASSWORD")
            // Bloqueio por senha vale só para aplicativos: sua saída é a tela de
            // senha que o app coloca na frente do alvo, e isso só é confiável
            // para um app detectado em primeiro plano. Filtrado aqui, e não só
            // na UI, para nenhum caminho de criação armar um bloqueio que
            // promete uma senha que nunca aparece — ver BlockTargetPolicy.
            val normalizedSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_PASSWORD,
                rules = sites
            )
            if (sites.isNotEmpty()) {
                FocusGuardLogger.log(
                    "BlockingSessionManager",
                    "Bloqueio por senha ignora ${sites.size} regra(s) de site/palavra"
                )
            }
            database.withTransaction {
                val session = BlockSession(
                    startTime = System.currentTimeMillis(),
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek,
                    blockedAppsCount = apps.distinct().size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = "PASSWORD",
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                apps.distinct().forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao()
                        .insert(SessionWebsiteCrossRef(sessionId, it))
                }
            }
            armSelfProtectionBeforeFirstExposure()
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError("BlockingSessionManager", "Erro ao iniciar sessão", error)
            throw error
        }
    }

    suspend fun startTimeSession(
        days: Int,
        hours: Int,
        isFixed24h: Boolean,
        /** Sem data final: o bloqueio só sai pela janela de manutenção do dia 15. */
        openEnded: Boolean = false,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        val protectionWasAlreadyArmed = deviceOwnerManager.isBlockingProtectionArmed()
        var sessionCreated = false
        try {
            // Sem gate de senha mestre aqui de propósito: o jejum não tem saída por
            // credencial, então exigir uma seria pedir chave que não abre nada. O
            // que ele exige é consentimento informado, coletado na UI antes de
            // chegar até aqui — ver MasterCredentialPolicy.
            //
            // O jejum é o único bloqueio que aceita palavras além de apps e
            // sites: é o mais rígido e o que as pessoas armam contra um hábito
            // inteiro, então leva a rede mais larga.
            val normalizedSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = sites
            )
            val normalizedApps = apps.filter(String::isNotBlank).distinct()
            require(normalizedApps.isNotEmpty() || normalizedSites.isNotEmpty()) {
                "O Jejum de Dopamina exige pelo menos um app ou site"
            }
            val duration = TimeUnit.DAYS.toMillis(days.toLong()) +
                TimeUnit.HOURS.toMillis(hours.toLong())
            // openEnded grava endTime nulo em vez de um prazo gigante: o resto do
            // app ja trata null como "sem data final" (isCurrentlyInBlockingWindow,
            // BlockCountdownPolicy), entao a UI diz "para sempre" em vez de contar
            // regressivamente a partir de um numero arbitrario.
            require(openEnded || duration > 0L) { "A duração da sessão deve ser positiva" }
            ensureBlockingPermissionsReady()
            database.withTransaction {
                val startMillis = System.currentTimeMillis()
                val session = BlockSession(
                    startTime = startMillis,
                    endTime = if (openEnded) null else startMillis + duration,
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek,
                    blockedAppsCount = normalizedApps.size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = "TIME",
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                normalizedApps.forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao()
                        .insert(SessionWebsiteCrossRef(sessionId, it))
                }
            }
            sessionCreated = true
            armSelfProtectionBeforeFirstExposure()
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!sessionCreated && !protectionWasAlreadyArmed) {
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.applyNuclearShield()
            }
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao iniciar sessão por tempo",
                error
            )
            throw error
        }
    }


    /**
     * Ativa, de uma só vez, o compromisso final da jornada AntiPorn:
     * pornografia sem data final e redes sociais por 180 dias.
     *
     * A frase é validada novamente aqui para que nenhum chamador consiga
     * contornar o consentimento exigido pela interface.
     */
    suspend fun startRecoveryProtectionPreset(
        typedConsent: String
    ) = withContext(Dispatchers.IO) {
        require(RecoveryProtectionPreset.isConsentAccepted(typedConsent)) {
            "O termo de consentimento deve ser digitado exatamente"
        }

        val protectionWasAlreadyArmed = deviceOwnerManager.isBlockingProtectionArmed()
        var sessionsCreated = false
        try {
            val pornographySites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
            val socialSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = RecoveryProtectionPreset.SOCIAL_WEBSITE_RULES.toList()
            )
            val socialApps = getRecoverySocialApps()

            require(pornographySites.isNotEmpty()) {
                "A categoria de pornografia não pôde ser preparada"
            }
            require(socialApps.isNotEmpty() || socialSites.isNotEmpty()) {
                "Nenhuma rede social pôde ser preparada"
            }

            ensureBlockingPermissionsReady()
            database.withTransaction {
                val startMillis = System.currentTimeMillis()

                val pornographySessionId = database.blockSessionDao().insertNewSession(
                    BlockSession(
                        startTime = startMillis,
                        endTime = null,
                        isActive = true,
                        isRecurring = false,
                        blockedAppsCount = 0,
                        blockedWebsitesCount = pornographySites.size,
                        sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                        isFixed24h = true
                    )
                ).toInt()
                pornographySites.forEach { rule ->
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(pornographySessionId, rule)
                    )
                }

                val socialSessionId = database.blockSessionDao().insertNewSession(
                    BlockSession(
                        startTime = startMillis,
                        endTime = startMillis + TimeUnit.DAYS.toMillis(
                            RecoveryProtectionPreset.SOCIAL_BLOCK_DAYS.toLong()
                        ),
                        isActive = true,
                        isRecurring = false,
                        blockedAppsCount = socialApps.size,
                        blockedWebsitesCount = socialSites.size,
                        sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                        isFixed24h = true
                    )
                ).toInt()
                socialApps.forEach { packageName ->
                    database.sessionAppCrossRefDao().insert(
                        SessionAppCrossRef(socialSessionId, packageName)
                    )
                }
                socialSites.forEach { rule ->
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(socialSessionId, rule)
                    )
                }
            }
            sessionsCreated = true
            armSelfProtectionBeforeFirstExposure()
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!sessionsCreated && !protectionWasAlreadyArmed) {
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.applyNuclearShield()
            }
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao ativar o atalho de proteção AntiPorn",
                error
            )
            throw error
        }
    }

    private fun ensureBlockingPermissionsReady() {
        val permissionState = ProtectionPermissionGate.read(context)
        if (!permissionState.isReady) {
            throw BlockingProtectionUnavailableException(
                BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED
            )
        }
        val protectionLevel = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = permissionState.accessibility,
                usageAccessEnabled = permissionState.usageAccess,
                batteryOptimizationExempt = permissionState.batteryOptimization,
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive()
            )
        )
        FocusGuardLogger.log(
            "BlockingSessionManager",
            "Bloqueio liberado com proteção ${protectionLevel.name.lowercase(Locale.US)}"
        )
    }

    /** Closes the gap between committing a new session and full reconciliation. */
    private fun armSelfProtectionBeforeFirstExposure() {
        check(SelfProtectionStateStore.setArmed(context, true)) {
            "Não foi possível armar a proteção síncrona da nova sessão"
        }
    }

    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean = true) {
        scope.launch {
            runCatching {
                require(durationMs > 0L) { "A duração do Pomodoro deve ser positiva" }
                if (isBlockingEnabled) ensureBlockingPermissionsReady()
                database.withTransaction {
                    database.blockSessionDao().deactivateActiveSessionsByType("POMODORO")
                    if (isBlockingEnabled) {
                        val startMillis = System.currentTimeMillis()
                        database.blockSessionDao().insertNewSession(
                            BlockSession(
                                startTime = startMillis,
                                endTime = startMillis + durationMs,
                                isActive = true,
                                sessionType = "POMODORO",
                                isFixed24h = true,
                                isBlockingEnabled = true
                            )
                        )
                    }
                }
                if (isBlockingEnabled) armSelfProtectionBeforeFirstExposure()
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.modo_pomodoro_ativado_foco_total, Toast.LENGTH_LONG)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao iniciar Pomodoro",
                    it
                )
            }
        }
    }

    fun endPomodoroSession() {
        scope.launch { endPomodoroSessionAndWait() }
    }

    suspend fun endPomodoroSessionAndWait(): Boolean {
        return runCatching {
            val changed = database.blockSessionDao()
                .deactivateActiveSessionsByType("POMODORO") > 0
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.stop(context)
            checkAndEnforce()
            changed
        }.onFailure {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar Pomodoro",
                it
            )
        }.getOrDefault(false)
    }

    /** Permanently removes every local source that can re-arm a block. */
    internal suspend fun removeAllBlocksForDevelopmentExit(): Boolean {
        return enforcementMutex.withLock {
            try {
                database.withTransaction {
                    database.blockSessionDao().deactivateAllActiveSessions()
                    database.pomodoroSessionDao().deleteSession()
                    database.appUsageLimitDao().deleteAll()
                    database.websiteUsageLimitDao().deleteAll()
                    database.usageLimitsLockDao().deleteAll()
                    database.blockedAppDao().deleteAllBlockedApps()
                    database.blockedWebsiteDao().deleteAllBlockedWebsites()
                }
                check(AuthManager.disableAdultFilterForDevelopmentExit(context)) {
                    "Não foi possível persistir a desativação do filtro adulto"
                }
                StrictPomodoroLock.clear(context)
                PomodoroForegroundService.stop(context)
                check(SelfProtectionStateStore.setArmed(context, false)) {
                    "Não foi possível desarmar o estado síncrono de autoproteção"
                }
                BlockingScheduleReceiver.scheduleNext(
                    context = context,
                    sessions = emptyList(),
                    additionalBoundaries = emptyList()
                )
                setDoNotDisturbMode(enabled = false)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "DevelopmentExit",
                    "Falha ao remover os bloqueios pela Área Dev",
                    error
                )
                false
            }
        }
    }

    /** True only while a password-backed rule is actually blocking now. */
    suspend fun hasPasswordProtectionBlockingNow(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val passwordSessionActive = database.blockSessionDao()
            .getAllActiveSessionsStatic()
            .any { session ->
                session.sessionType.equals("PASSWORD", ignoreCase = true) &&
                    isCurrentlyInBlockingWindow(session)
            }
        if (passwordSessionActive) return@withContext true

        val passwordAppLimits = database.appUsageLimitDao()
            .getAllActiveLimitsStatic()
            .filter { it.lockMode.equals("PASSWORD", ignoreCase = true) }
        if (getExceededAppLimits(passwordAppLimits, now).isNotEmpty()) {
            return@withContext true
        }

        val passwordWebsiteLimits = database.websiteUsageLimitDao()
            .getAllStatic()
            .filter {
                it.isEnabled && it.lockMode.equals("PASSWORD", ignoreCase = true)
            }
        if (passwordWebsiteLimits.isEmpty()) return@withContext false

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val usageByWebsite = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = passwordWebsiteLimits.map { it.domain }
        )
        passwordWebsiteLimits.any { limit ->
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = usageByWebsite[WebsiteBlocker.normalizeRule(limit.domain)] ?: 0L,
                dailyLimitMinutes = limit.dailyLimitMinutes,
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }
    }

    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessionsStatic()
            .any { it.sessionType == "TIME" }
    }

    fun appendToTimeSession(
        addedDays: Int,
        addedHours: Int,
        additionalApps: List<String>,
        additionalSites: List<String>
    ) {
        scope.launch {
            runCatching {
                val session = database.blockSessionDao().getAllActiveSessionsStatic()
                    .filter { it.sessionType == "TIME" }
                    .maxByOrNull { it.startTime }
                    ?: return@runCatching
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) +
                    TimeUnit.HOURS.toMillis(addedHours.toLong())
                require(addedMillis >= 0L) { "Extensão inválida" }
                val normalizedAdditionalSites = WebsiteBlocker.normalizeRules(additionalSites)
                database.withTransaction {
                    additionalApps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(session.id, it))
                    }
                    normalizedAdditionalSites.forEach {
                        database.sessionWebsiteCrossRefDao()
                            .insert(SessionWebsiteCrossRef(session.id, it))
                    }
                    val apps = database.sessionAppCrossRefDao()
                        .getAppsForSessions(listOf(session.id))
                    val sites = database.sessionWebsiteCrossRefDao()
                        .getWebsitesForSessions(listOf(session.id))
                    database.blockSessionDao().updateBlockSession(
                        session.copy(
                            endTime = (session.endTime ?: System.currentTimeMillis()) + addedMillis,
                            blockedAppsCount = apps.distinct().size,
                            blockedWebsitesCount = sites.distinct().size
                        )
                    )
                }
                checkAndEnforce()
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao estender sessão",
                    it
                )
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            runCatching {
                database.blockSessionDao().deactivateActiveSessionsByType("PASSWORD")
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.bloqueios_por_senha_encerrados, Toast.LENGTH_SHORT)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao encerrar sessões por senha",
                    it
                )
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch { endSessionAndWait(sessionId) }
    }

    suspend fun endSessionAndWait(sessionId: Int): EndSessionResult {
        return try {
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return EndSessionResult.NOT_FOUND
            when {
                session.sessionType == "POMODORO" -> EndSessionResult.POMODORO_NOT_REVOCABLE
                MasterCredentialPolicy.isTimeCommitmentActive(
                    sessionType = session.sessionType,
                    isActive = session.isActive,
                    endTime = session.endTime
                ) -> EndSessionResult.TIME_NOT_REVOCABLE
                database.blockSessionDao().deactivateSession(sessionId) == 0 ->
                    EndSessionResult.NOT_FOUND
                else -> {
                    checkAndEnforceOrThrow()
                    EndSessionResult.ENDED
                }
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar sessão $sessionId",
                error
            )
            EndSessionResult.FAILED
        }
    }

    suspend fun findResponsibleSessionId(
        blockedPackage: String?,
        blockedDomain: String?
    ): Int? {
        val sessions = database.blockSessionDao().getAllActiveSessionsStatic()
            .filter { it.sessionType == "PASSWORD" && isCurrentlyInBlockingWindow(it) }
            .sortedByDescending { it.startTime }

        for (session in sessions) {
            val apps = if (blockedPackage.isNullOrBlank()) emptyList() else {
                database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
            }
            val sites = if (blockedDomain.isNullOrBlank()) emptyList() else {
                database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
            }
            if (matchesBlockedTarget(blockedPackage, blockedDomain, apps, sites)) return session.id
        }

        return null
    }

    /**
     * Releases only the target whose password was successfully authenticated.
     * A PASSWORD session can contain several apps; opening one must never end the
     * protection of all siblings in that session.
     */
    suspend fun unlockPasswordSessionTarget(
        blockedPackage: String?,
        blockedDomain: String?
    ): EndSessionResult = withContext(Dispatchers.IO) {
        try {
            val sessionId = findResponsibleSessionId(blockedPackage, blockedDomain)
                ?: return@withContext EndSessionResult.NOT_FOUND
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return@withContext EndSessionResult.NOT_FOUND
            if (!session.sessionType.equals("PASSWORD", ignoreCase = true)) {
                return@withContext EndSessionResult.NOT_FOUND
            }

            database.withTransaction {
                blockedPackage?.takeIf(String::isNotBlank)?.let { packageName ->
                    database.sessionAppCrossRefDao().deleteSpecificApp(sessionId, packageName)
                }
                blockedDomain?.takeIf(String::isNotBlank)?.let { domain ->
                    val sessionSites = database.sessionWebsiteCrossRefDao()
                        .getWebsitesForSessions(listOf(sessionId))
                    sessionSites.filter { configuredRule ->
                        WebsiteBlocker.isUrlBlocked(domain, listOf(configuredRule))
                    }.forEach { configuredRule ->
                        database.sessionWebsiteCrossRefDao()
                            .deleteSpecificWebsite(sessionId, configuredRule)
                    }
                }

                val remainingApps = database.sessionAppCrossRefDao()
                    .getAppsForSessions(listOf(sessionId))
                val remainingSites = database.sessionWebsiteCrossRefDao()
                    .getWebsitesForSessions(listOf(sessionId))
                if (remainingApps.isEmpty() && remainingSites.isEmpty()) {
                    database.blockSessionDao().deactivateSession(sessionId)
                } else {
                    database.blockSessionDao().updateBlockSession(
                        session.copy(
                            blockedAppsCount = remainingApps.distinct().size,
                            blockedWebsitesCount = remainingSites.distinct().size
                        )
                    )
                }
            }

            blockedPackage?.takeIf(String::isNotBlank)?.let { packageName ->
                PasswordAppUnlockStore(context).clearPackages(listOf(packageName))
            }
            checkAndEnforceOrThrow()
            EndSessionResult.ENDED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao liberar alvo de sessão PASSWORD",
                error
            )
            EndSessionResult.FAILED
        }
    }

    /**
     * What kind of protection is holding this target closed, expressed only in
     * terms a credential can act on.
     *
     * Returns null whenever nothing here is credential-unlockable — a dopamine
     * fast, a time-hardened limit, or a limit with no password configured. The UI
     * uses this to decide whether to offer the fingerprint at all, so failing
     * closed is the safe direction: null hides the affordance.
     */
    suspend fun credentialUnlockOrigin(
        blockedPackage: String?,
        blockedDomain: String?,
        strictPomodoroActive: Boolean
    ): BiometricAppUnlockPolicy.BlockOrigin? {
        if (strictPomodoroActive) return BiometricAppUnlockPolicy.BlockOrigin.STRICT_POMODORO
        if (hasCredentialUnlockableLimit(blockedPackage, blockedDomain)) {
            return BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
        }
        // findResponsibleSessionId already restricts itself to PASSWORD sessions
        // inside their blocking window, so a hit here is exactly that.
        if (findResponsibleSessionId(blockedPackage, blockedDomain) != null) {
            return BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION
        }
        return null
    }

    /**
     * True when a credential-unlockable limit is currently blocking this target.
     *
     * Probes through the shared unlock path with a verifier that always refuses,
     * so eligibility can never drift from what a real unlock accepts. The refusal
     * returns before any write, making the probe side-effect free.
     */
    private suspend fun hasCredentialUnlockableLimit(
        blockedPackage: String?,
        blockedDomain: String?
    ): Boolean = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) { false } == LimitUnlockResult.WRONG_PASSWORD

    /** Unlocks a password-protected limit by typing the password. */
    suspend fun unlockPasswordProtectedLimit(
        password: String,
        blockedPackage: String?,
        blockedDomain: String?
    ): LimitUnlockResult = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) {
        when (deactivationCredentialManager.verify(password)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> true
            DeactivationCredentialManager.VerificationResult.REJECTED,
            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> false
        }
    }

    /**
     * Unlocks a password-protected limit after identity was already proven by a
     * BIOMETRIC_STRONG prompt, which cannot reproduce the typed password.
     *
     * Reaches exactly the same limits as [unlockPasswordProtectedLimit] because
     * both funnel through [unlockCredentialProtectedLimit] and share its
     * eligibility filter — only the verification step differs. That filter
     * requires `lockMode == "PASSWORD"`, so a time-hardened limit is structurally
     * out of reach here, with or without a fingerprint.
     *
     * Callers must have confirmed [BiometricAppUnlockPolicy.canAcceptBiometricResult]
     * first; this method trusts that the prompt succeeded, not that it was allowed.
     */
    suspend fun unlockLimitWithVerifiedIdentity(
        blockedPackage: String?,
        blockedDomain: String?
    ): LimitUnlockResult = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) { true }

    /**
     * @param verifyMasterCredential checks the single master credential shared
     *   by app protection, usage limits and FocusGuard entry.
     */
    private suspend fun unlockCredentialProtectedLimit(
        blockedPackage: String?,
        blockedDomain: String?,
        verifyMasterCredential: () -> Boolean
    ): LimitUnlockResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val unlockUntil = BlockingScheduleCalculator.nextLocalMidnight(now)

            val appLimit = blockedPackage
                ?.takeIf(String::isNotBlank)
                ?.let { database.appUsageLimitDao().getLimitForPackage(it) }
                ?.takeIf { limit ->
                    limit.isEnabled &&
                        limit.lockMode.equals("PASSWORD", ignoreCase = true) &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            if (appLimit != null &&
                appLimit.packageName in getExceededAppLimits(listOf(appLimit), now)
            ) {
                if (!verifyMasterCredential()) {
                    return@withContext LimitUnlockResult.WRONG_PASSWORD
                }
                database.appUsageLimitDao().update(
                    appLimit.copy(lockUntilTimestamp = unlockUntil)
                )
                checkAndEnforceOrThrow()
                return@withContext LimitUnlockResult.UNLOCKED
            }

            val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                .filter { limit ->
                    limit.isEnabled &&
                        limit.lockMode.equals("PASSWORD", ignoreCase = true) &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            val websiteUsage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = websiteLimits.map { it.domain }
            )
            val blockingWebsiteLimits = websiteLimits.filter { limit ->
                WebsiteUsageLimitPolicy.shouldBlock(
                    usedMillis = websiteUsage[WebsiteBlocker.normalizeRule(limit.domain)] ?: 0L,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    lockMode = limit.lockMode,
                    lockUntilTimestamp = limit.lockUntilTimestamp,
                    nowMillis = now
                )
            }
            val matchingRules = blockedDomain
                ?.takeIf(String::isNotBlank)
                ?.let {
                    WebsiteBlocker.findMatchingRules(
                        it,
                        WebsiteBlocker.normalizeRules(blockingWebsiteLimits.map { limit ->
                            limit.domain
                        })
                    )
                }
                .orEmpty()
            val matchingWebsiteLimits = matchingRules.mapNotNull { rule ->
                blockingWebsiteLimits.firstOrNull {
                    WebsiteBlocker.normalizeRule(it.domain) == rule
                }
            }
            if (matchingWebsiteLimits.isNotEmpty()) {
                if (!verifyMasterCredential()) {
                    return@withContext LimitUnlockResult.WRONG_PASSWORD
                }
                matchingWebsiteLimits.forEach { limit ->
                    database.websiteUsageLimitDao().insert(
                        limit.copy(lockUntilTimestamp = unlockUntil)
                    )
                }
                checkAndEnforceOrThrow()
                return@withContext LimitUnlockResult.UNLOCKED
            }

            LimitUnlockResult.NOT_FOUND
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao desbloquear limite protegido por credencial",
                error
            )
            LimitUnlockResult.FAILED
        }
    }

    suspend fun checkAndEnforce() {
        try {
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao reconciliar bloqueios",
                error
            )
        }
    }

    internal suspend fun checkAndEnforceStrict() {
        checkAndEnforceOrThrow()
    }

    private suspend fun checkAndEnforceOrThrow() {
        enforcementMutex.withLock {
                val now = System.currentTimeMillis()
                val focusModeSession = FocusModeStore.readSession(context)
                    ?.takeIf { it.isActive(now) }
                val focusModeApps = focusModeSession?.blockedPackages.orEmpty()
                val beforeExpiration = database.blockSessionDao().getAllActiveSessionsStatic()
                val expiredPomodoro = beforeExpiration.any {
                    it.sessionType == "POMODORO" && it.endTime != null && it.endTime <= now
                }
                database.blockSessionDao().deactivateExpiredSessions(now)
                if (expiredPomodoro) {
                    StrictPomodoroLock.clear(context)
                    PomodoroForegroundService.stop(context)
                }

                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val activeTimeCommitment = activeSessions.any { session ->
                    MasterCredentialPolicy.isTimeCommitmentActive(
                        sessionType = session.sessionType,
                        isActive = session.isActive,
                        endTime = session.endTime,
                        nowMillis = now
                    )
                }
                val enforcingSessions = activeSessions.filter {
                    participatesInBlocking(it) && isCurrentlyInBlockingWindow(it)
                }
                val enforcingIds = enforcingSessions.map { it.id }
                val passwordSessionIds = enforcingSessions
                    .filter { it.sessionType == "PASSWORD" }
                    .map { it.id }
                val strongerSessionIds = enforcingSessions
                    .filter { it.sessionType != "PASSWORD" }
                    .map { it.id }
                val strictPomodoro = enforcingSessions.any {
                    it.sessionType == "POMODORO" && it.isBlockingEnabled
                }

                setDoNotDisturbMode(strictPomodoro)

                val sessionApps = if (strictPomodoro) {
                    getInstalledUserAppsExceptPhone()
                } else {
                    getAppsForSessions(enforcingIds)
                }
                val sessionSites = getSitesForSessions(enforcingIds)
                val passwordSessionApps = getAppsForSessions(passwordSessionIds)
                val strongerSessionApps = if (strictPomodoro) {
                    sessionApps
                } else {
                    getAppsForSessions(strongerSessionIds)
                }
                val strongerSessionSites = getSitesForSessions(strongerSessionIds)

                val activeAppLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
                val limitApps = getExceededAppLimits(activeAppLimits, now)

                val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic()
                    .filter { it.isEnabled }
                val adultFilterEnabled = AuthManager.isAdultFilterConfigured(context)
                val policyExpirations = (
                    activeAppLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    } + activeWebsiteLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    }
                )
                val nextDailyReset = if (
                    activeAppLimits.isNotEmpty() || activeWebsiteLimits.isNotEmpty()
                ) {
                    BlockingScheduleCalculator.nextLocalMidnight(now)
                } else null
                val nextReconciliation = if (
                    activeSessions.isNotEmpty() ||
                    activeAppLimits.isNotEmpty() ||
                    activeWebsiteLimits.isNotEmpty() ||
                    adultFilterEnabled ||
                    focusModeSession != null
                ) {
                    now + POLICY_RECONCILIATION_INTERVAL_MILLIS
                } else null
                BlockingScheduleReceiver.scheduleNext(
                    context = context,
                    sessions = activeSessions,
                    additionalBoundaries = policyExpirations +
                        listOfNotNull(
                            nextDailyReset,
                            nextReconciliation,
                            focusModeSession?.endTimeMillis
                        ),
                    nowMillis = now
                )
                val activeWebsiteDomains = WebsiteBlocker.normalizeRules(
                    activeWebsiteLimits.map { it.domain }
                )
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                val usageByWebsite = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                    usageByIdentifier = database.dailyUsageStatDao()
                        .getStatsForDateStatic(today)
                        .map { it.identifier to it.timeSpentMs },
                    configuredRules = activeWebsiteDomains
                )
                val limitSites = activeWebsiteLimits.filter { limit ->
                    val normalizedDomain = WebsiteBlocker.normalizeRule(limit.domain)
                    WebsiteUsageLimitPolicy.shouldBlock(
                        usedMillis = usageByWebsite[normalizedDomain] ?: 0L,
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        nowMillis = now
                    )
                }.map { WebsiteBlocker.normalizeRule(it.domain) }

                val appFamilySites = WebsiteBlocker.domainRulesForAppPackages(
                    sessionApps + limitApps
                )
                // O filtro adulto global entra aqui, e não só dentro de
                // enforceWebsiteRestrictions: esta lista também vira o snapshot
                // enviado ao AccessibilityService, que substitui o conjunto
                // vigente e adia o próximo refresh. Sem a regra, o serviço
                // passava a janela inteira até a próxima recarga sem bloquear
                // pornografia — e num aparelho sem Device Owner ele é a única
                // camada que sobra.
                val adultFilterRules = if (adultFilterEnabled) {
                    listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
                } else {
                    emptyList()
                }
                val strongerWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    strongerSessionSites + limitSites + adultFilterRules
                ).keys.filter(::isPackageInstalled)
                val sitesToBlock = (sessionSites + limitSites + appFamilySites + adultFilterRules)
                    .map(WebsiteBlocker::normalizeRule)
                    .filter { it.isNotBlank() }
                    .distinct()
                val pornographyCategoryActive =
                    WebsiteBlocker.containsPornographyRule(sitesToBlock)
                deviceOwnerManager.setPornographyCategoryActive(pornographyCategoryActive)
                val websiteAppsToBlock = WebsiteBlocker.appPackageDomainsFor(sitesToBlock)
                    .keys
                    .filter(::isPackageInstalled)
                // A Focus Mode allowlist is an explicit temporary override:
                // phone, SMS and the apps chosen for that session must remain
                // fully launchable even if another FocusGuard rule also names them.
                val appsToBlock = FocusModePolicy.packagesToEnforce(
                    configuredBlockedPackages = sessionApps + limitApps + websiteAppsToBlock,
                    focusModeBlockedPackages = focusModeApps,
                    focusModeAllowedPackages = focusModeSession?.allowedPackages.orEmpty()
                ).toList()
                val deviceOwnerAppsToSuspend = packagesForDeviceOwnerSuspension(
                    enforcedPackages = appsToBlock,
                    passwordSessionPackages = passwordSessionApps,
                    strongerProtectionPackages =
                        strongerSessionApps + limitApps + strongerWebsiteApps + focusModeApps,
                    strictPomodoro = strictPomodoro
                )
                val nativeFocusLockdownActive = focusModeSession != null &&
                    FocusModePolicy.usesNativeFocusLockdown(
                        deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                        systemLockdownSupported =
                            deviceOwnerManager.isFocusModeSystemLockdownSupported()
                    )
                // Native mode suspends the Focus Mode targets. Consumer mode keeps
                // them in Accessibility's snapshot so attempts can be redirected
                // straight back to FocusGuard without opening BlockNoticeActivity.
                val accessibilityAppsToBlock = FocusModePolicy.packagesForAccessibility(
                    enforcedPackages = appsToBlock,
                    focusModeBlockedPackages = focusModeApps,
                    nativeFocusLockdownActive = nativeFocusLockdownActive
                ).toList()

                val allSessionApps = getAppsForSessions(activeSessions.map { it.id })
                val allSessionSites = getSitesForSessions(activeSessions.map { it.id })
                val allKnownWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    allSessionSites + activeWebsiteLimits.map { it.domain }
                ).keys.filter(::isPackageInstalled)
                val allKnownApps = (
                    allSessionApps +
                        activeAppLimits.map { it.packageName } +
                        allKnownWebsiteApps +
                        focusModeApps
                ).distinct()

                deviceOwnerManager.syncSuspendedApps(
                    allAppsInSessions = allKnownApps,
                    appsToBlockNow = deviceOwnerAppsToSuspend,
                    allowedSystemApps = deviceOwnerAppsToSuspend.toSet()
                )

                if (sitesToBlock.isEmpty() && !adultFilterEnabled) {
                    deviceOwnerManager.clearWebsiteRestrictions()
                } else {
                    deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                }

                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                ) || activeTimeCommitment
                if (selfProtectionRequired) {
                    // Persist before DevicePolicyManager/broadcast work. The
                    // AccessibilityService can be recreated between any two of
                    // these calls; its very first event must already fail closed.
                    check(SelfProtectionStateStore.setArmed(context, true)) {
                        "Não foi possível persistir a autoproteção da primeira tentativa"
                    }
                    val nativeProtectionConfirmed =
                        deviceOwnerManager.enforceBlockingPolicies()
                    check(
                        !deviceOwnerManager.isDeviceOwnerActive() ||
                            nativeProtectionConfirmed
                    ) {
                        "O Android não confirmou a autoproteção nativa do FocusGuard"
                    }
                } else {
                    deviceOwnerManager.clearBlockingPolicies()
                    // Clear only after native cleanup. A failure therefore stays
                    // safely armed until the next reconciliation or the Dev exit.
                    check(SelfProtectionStateStore.setArmed(context, false)) {
                        "Não foi possível persistir o fim da autoproteção"
                    }
                }
                deviceOwnerManager.applyNuclearShield()

                context.sendBroadcast(
                    BlockingAccessibilityService.createRefreshBlockingIntent(
                        context = context,
                        blockedApps = accessibilityAppsToBlock,
                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro
                    )
                )
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        if (session.endTime != null && now.timeInMillis >= session.endTime) return false
        if (session.isFixed24h) return true

        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        val overnight = startMinutes > endMinutes
        val afterMidnight = overnight && currentMinutes < endMinutes
        val logicalDay = now.clone() as Calendar
        if (afterMidnight) logicalDay.add(Calendar.DAY_OF_YEAR, -1)

        if (session.recurringDaysOfWeek.isNotBlank()) {
            val allowedDays = session.recurringDaysOfWeek.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            if (logicalDay.get(Calendar.DAY_OF_WEEK).toString() !in allowedDays) return false
        }

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    private fun getExceededAppLimits(
        limits: List<com.focusguard.database.AppUsageLimit>,
        now: Long
    ): List<String> {
        if (limits.isEmpty()) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as?
            UsageStatsManager
        if (usageStatsManager == null) return emptyList()
        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val totalDayUsageMillis =
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
            val effectiveUsageMillis = AppUsageLimitActivationUsage.effectiveUsageMillis(
                context = context,
                usageStatsManager = usageStatsManager,
                limit = limit,
                currentDayUsageMillis = totalDayUsageMillis,
                dayStartMillis = startOfDay,
                nowMillis = now
            )
            UsageLimitForegroundPolicy.usedMinutes(effectiveUsageMillis) >=
                limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.map { it.packageName }
    }


    /**
     * Combina redes conhecidas (inclusive ainda não instaladas) com apps de
     * terceiros que o próprio Android classificou como sociais. Mensageiros
     * dedicados são retirados por último para que a exceção sempre prevaleça.
     */
    private fun getRecoverySocialApps(): List<String> {
        val discoveredSocialApps = try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystemApp = app.flags and (
                        ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                        ) != 0
                    val declaredSocialCategory =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            app.category == ApplicationInfo.CATEGORY_SOCIAL

                    app.packageName != context.packageName &&
                        RecoveryProtectionPreset.shouldBlockApp(
                            packageName = app.packageName,
                            declaredSocialCategory = declaredSocialCategory,
                            isSystemApp = isSystemApp
                        )
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao identificar redes sociais instaladas",
                error
            )
            emptyList()
        }

        return (RecoveryProtectionPreset.KNOWN_SOCIAL_APP_PACKAGES +
            discoveredSocialApps)
            .asSequence()
            .filterNot { it == context.packageName }
            .filterNot(RecoveryProtectionPreset::isMessengerPackage)
            .distinct()
            .toList()
    }

    private fun getInstalledUserAppsExceptPhone(): List<String> {
        val phoneWhitelist = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui"
        )
        return try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != context.packageName &&
                        app.packageName !in phoneWhitelist &&
                        app.flags and ApplicationInfo.FLAG_SYSTEM == 0
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao listar aplicativos instalados",
                error
            )
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao verificar pacote associado a site",
                error
            )
            false
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)
    }

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
    }

    private fun setDoNotDisturbMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.isNotificationPolicyAccessGranted) {
                if (enabled) {
                    if (!statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                        statePreferences.edit()
                            .putInt(PREVIOUS_DND_FILTER_KEY, manager.currentInterruptionFilter)
                            .apply()
                    }
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else if (statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                    val savedFilter = statePreferences.getInt(
                        PREVIOUS_DND_FILTER_KEY,
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    val previousFilter = savedFilter.takeIf {
                        it == NotificationManager.INTERRUPTION_FILTER_ALL ||
                            it == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                            it == NotificationManager.INTERRUPTION_FILTER_NONE ||
                            it == NotificationManager.INTERRUPTION_FILTER_ALARMS
                    } ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    manager.setInterruptionFilter(previousFilter)
                    statePreferences.edit().remove(PREVIOUS_DND_FILTER_KEY).apply()
                }
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao alterar Não Perturbe",
                error
            )
        }
    }

    private suspend fun showToast(resourceId: Int, duration: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(resourceId), duration).show()
        }
    }
}
