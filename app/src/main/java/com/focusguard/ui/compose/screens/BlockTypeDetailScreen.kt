package com.focusguard.ui.compose.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.monetization.MonetizationPolicy
import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.BlockCountdownPolicy
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.components.FocusGuardBannerAd
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentIconBadge
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.FocusGuardAmbientBackground
import com.focusguard.ui.compose.theme.FocusGuardBackButton
import com.focusguard.ui.compose.theme.FocusSectionLabel
import com.focusguard.ui.compose.theme.StatusPill
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber
import com.focusguard.utils.WebsiteBlocker

/**
 * The three kinds of protection, as the user chooses between them.
 *
 * Each carries its own colour and icon so the type is recognisable before the
 * text is read — the same card looks the same on the home screen and at the top
 * of its own list.
 */
enum class BlockTypeUi(
    val titleRes: Int,
    val subtitleRes: Int,
    val emptyRes: Int,
    val actionRes: Int,
    val icon: ImageVector,
    val accent: Color
) {
    PASSWORD(
        titleRes = R.string.block_type_password_title,
        subtitleRes = R.string.block_type_password_subtitle,
        emptyRes = R.string.block_type_password_empty,
        actionRes = R.string.block_type_password_action,
        icon = Icons.Outlined.Lock,
        accent = AccentCyan
    ),
    DAILY_LIMIT(
        titleRes = R.string.block_type_limit_title,
        subtitleRes = R.string.block_type_limit_subtitle,
        emptyRes = R.string.block_type_limit_empty,
        actionRes = R.string.block_type_limit_action,
        icon = Icons.Outlined.Timelapse,
        accent = WarningAmber
    ),
    DOPAMINE_FAST(
        titleRes = R.string.block_type_fast_title,
        subtitleRes = R.string.block_type_fast_subtitle,
        emptyRes = R.string.block_type_fast_empty,
        actionRes = R.string.block_type_fast_action,
        icon = Icons.Outlined.HourglassEmpty,
        accent = DangerRed
    );

    fun entriesOf(overview: BlockingSessionManager.BlockOverview) = when (this) {
        PASSWORD -> overview.passwordEntries
        DAILY_LIMIT -> overview.dailyLimitEntries
        DOPAMINE_FAST -> overview.dopamineFastEntries
    }
}

internal fun shouldShowBlockTypeBanner(type: BlockTypeUi): Boolean =
    type == BlockTypeUi.DAILY_LIMIT || type == BlockTypeUi.DOPAMINE_FAST

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockTypeDetailScreen(
    type: BlockTypeUi,
    onAddClick: () -> Unit,
    onIntruderLogClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    var entries by remember {
        mutableStateOf<List<BlockingSessionManager.BlockOverview.Entry>?>(null)
    }

    // O assistente de criação roda em outra Activity, então esta composição
    // sobrevive à ida e à volta e um LaunchedEffect(type) sozinho nunca
    // dispararia de novo: a tela reapareceria mostrando a lista de antes do
    // bloqueio ser criado, como se o app ou site adicionado tivesse sumido.
    // Recarregar a cada ON_RESUME faz a lista refletir o banco toda vez que a
    // tela volta a ficar visível.
    var reloadTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reloadTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // `entries` não volta a null nas recargas: manter a lista anterior na tela
    // evita um piscar de spinner a cada retorno para uma tela que já tem dados.
    LaunchedEffect(type, reloadTrigger) {
        entries = runCatching { type.entriesOf(sessionManager.getBlockOverview()) }
            .getOrDefault(emptyList())
    }

    // O halo do topo usa a cor do próprio tipo de bloqueio: entrar na tela da
    // senha, do limite ou do jejum já dá o sinal de qual proteção é, antes da
    // primeira linha de texto.
    FocusGuardAmbientBackground(
        modifier = Modifier.fillMaxSize(),
        baseColor = DarkBg,
        glowColor = type.accent.copy(alpha = 0.09f)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(type.titleRes),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        )
                    },
                    navigationIcon = {
                        FocusGuardBackButton(
                            onBack = onBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            bottomBar = {
                if (shouldShowBlockTypeBanner(type)) {
                    FocusGuardBannerAd()
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                BlockTypeHeader(type)

                // Ferramentas que pertencem ao bloqueio por senha ficam junto
                // da própria proteção, em vez de escondidas em Configurações.
                if (type == BlockTypeUi.PASSWORD) {
                    Spacer(Modifier.height(16.dp))
                    PasswordProtectionTools(
                        accent = type.accent,
                        onIntruderLogClick = onIntruderLogClick
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Botão principal em degradê da cor do tipo: fica o elemento mais
                // luminoso da tela, que é o que se espera da ação que a tela existe
                // para oferecer.
                Button(
                    onClick = onAddClick,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(type.accent, type.accent.copy(alpha = 0.78f))
                                ),
                                RoundedCornerShape(16.dp)
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(type.actionRes),
                            color = DarkBg,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                val current = entries
                when {
                    current == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = type.accent)
                    }

                    current.isEmpty() -> EmptyBlockList(type)

                    else -> {
                        StatusPill(
                            text = stringResource(
                                R.string.block_type_already_blocked,
                                current.size
                            ),
                            accent = type.accent,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        // Aplicativos e sites em seções próprias: são coisas
                        // diferentes de gerenciar — um se reconhece pelo ícone, o
                        // outro pelo endereço — e misturados numa lista só a pessoa
                        // precisa ler item a item para achar o que procura.
                        val apps = current.filterNot { it.isWebsite }
                        val sites = current.filter { it.isWebsite }
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (apps.isNotEmpty()) {
                                item(key = "header_apps") {
                                    BlockedSectionHeader(
                                        text = stringResource(
                                            R.string.block_type_section_apps,
                                            apps.size
                                        ),
                                        accent = type.accent
                                    )
                                }
                                items(apps, key = { "app_${it.identifier}" }) { entry ->
                                    BlockedEntryRow(entry = entry, accent = type.accent)
                                }
                            }
                            if (sites.isNotEmpty()) {
                                item(key = "header_sites") {
                                    BlockedSectionHeader(
                                        text = stringResource(
                                            R.string.block_type_section_sites,
                                            sites.size
                                        ),
                                        accent = type.accent
                                    )
                                }
                                items(sites, key = { "site_${it.identifier}" }) { entry ->
                                    BlockedEntryRow(entry = entry, accent = type.accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ferramentas relacionadas a tentativas de abertura de apps protegidos.
 *
 * O desbloqueio biométrico usa a mesma preferência e o mesmo autenticador do
 * fluxo real de abertura. Ativar biometria ou selfie exige uma recompensa
 * independente. A selfie pede a permissão de câmera antes do anúncio para não
 * cobrar uma recompensa por uma função que o Android não poderá habilitar.
 */
@Composable
private fun PasswordProtectionTools(
    accent: Color,
    onIntruderLogClick: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember(context) { AuthManager(context) }
    val biometricAvailable = remember(context) {
        AppUnlockBiometricAuthenticator.isAvailable(context)
    }

    var biometricEnabled by remember {
        mutableStateOf(authManager.isBiometricAppUnlockEnabled())
    }
    var selfieEnabled by remember { mutableStateOf(authManager.isPhotoCaptureEnabled()) }

    val biometricGateTitle = stringResource(R.string.password_app_unlock_quick_biometric_title)
    val biometricGateDescription =
        stringResource(R.string.password_app_unlock_biometric_rewarded_desc)
    val selfieGateTitle = stringResource(R.string.limits_intruder_selfie)
    val selfieGateDescription =
        stringResource(R.string.password_app_unlock_intruder_selfie_rewarded_desc)

    val launchBiometricRewardedGate: () -> Unit = {
        RewardedGateCoordinator.launch(
            context = context,
            requiredAds = MonetizationPolicy.BIOMETRIC_UNLOCK_REWARDED_ADS,
            title = biometricGateTitle,
            description = biometricGateDescription
        ) {
            biometricEnabled = true
            authManager.setBiometricAppUnlockEnabled(true)
        }
    }

    val launchSelfieRewardedGate: () -> Unit = {
        RewardedGateCoordinator.launch(
            context = context,
            requiredAds = MonetizationPolicy.INTRUDER_SELFIE_REWARDED_ADS,
            title = selfieGateTitle,
            description = selfieGateDescription
        ) {
            selfieEnabled = true
            authManager.setPhotoCaptureEnabled(true)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSelfieRewardedGate()
        } else {
            selfieEnabled = false
            authManager.setPhotoCaptureEnabled(false)
        }
    }

    Column {
        FocusCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            ToggleRow(
                title = biometricGateTitle,
                subtitle = stringResource(
                    if (biometricAvailable) {
                        R.string.password_app_unlock_quick_biometric_desc
                    } else {
                        R.string.password_app_unlock_biometric_unavailable
                    }
                ),
                checked = biometricEnabled && biometricAvailable,
                enabled = biometricAvailable,
                accent = accent,
                onCheckedChange = { enable ->
                    if (enable) {
                        launchBiometricRewardedGate()
                    } else {
                        biometricEnabled = false
                        authManager.setBiometricAppUnlockEnabled(false)
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        FocusCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            ToggleRow(
                title = selfieGateTitle,
                subtitle = stringResource(R.string.limits_intruder_selfie_desc),
                checked = selfieEnabled,
                enabled = true,
                accent = accent,
                onCheckedChange = { enable ->
                    if (enable) {
                        val cameraGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (cameraGranted) {
                            launchSelfieRewardedGate()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        selfieEnabled = false
                        authManager.setPhotoCaptureEnabled(false)
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Default.PhotoCamera,
            title = stringResource(R.string.intruder_log),
            subtitle = stringResource(R.string.settings_intruder_log_subtitle),
            iconTint = accent,
            onClick = onIntruderLogClick
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) TextPrimary else TextHint,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkBg,
                checkedTrackColor = accent
            )
        )
    }
}

@Composable
private fun BlockTypeHeader(type: BlockTypeUi) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, type.accent.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccentIconBadge(
                icon = type.icon,
                accent = type.accent,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(type.subtitleRes),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyBlockList(type: BlockTypeUi) {
    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        // A lista vazia deixa de ser um ícone cinza solto no vazio: vira um
        // painel tracejado, que se lê como um espaço à espera de conteúdo em
        // vez de uma tela que não carregou.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(type.accent.copy(alpha = 0.03f))
                .border(
                    1.dp,
                    type.accent.copy(alpha = 0.16f),
                    RoundedCornerShape(20.dp)
                )
                .padding(vertical = 34.dp, horizontal = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(type.accent.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    type.icon,
                    contentDescription = null,
                    tint = type.accent.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(type.emptyRes),
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )
        }
    }
}

/**
 * The name shown for a blocked entry.
 *
 * A preventive target is, by definition, an app that is not installed, so the
 * PackageManager has no label for it and [installedLabel] comes back null. The
 * catalogue answers for those before the package name does, so the row reads
 * "Instagram" instead of "com.instagram.android" — a raw package id looks like a
 * wrong entry to whoever just added the block.
 *
 * @param installedLabel what the PackageManager resolved, or null when the app
 *   is not installed (or the entry is a website).
 */
internal fun blockedEntryLabel(
    identifier: String,
    isWebsite: Boolean,
    installedLabel: String?
): String {
    // displayRule desfaz os prefixos de persistência: "keyword:aposta" vira
    // "*aposta*" e a categoria adulta vira "Pornografia".
    if (isWebsite) return WebsiteBlocker.displayRule(identifier)
    installedLabel?.takeIf { it.isNotBlank() }?.let { return it }
    return PredefinedApps.PREVENTIVE_APPS
        .firstOrNull { it.packageName == identifier }
        ?.appName
        ?: identifier
}

@Composable
private fun BlockedSectionHeader(text: String, accent: Color) {
    FocusSectionLabel(
        text = text,
        accent = accent,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun BlockedEntryRow(
    entry: BlockingSessionManager.BlockOverview.Entry,
    accent: Color
) {
    val context = LocalContext.current
    val label = remember(entry.identifier) {
        blockedEntryLabel(
            identifier = entry.identifier,
            isWebsite = entry.isWebsite,
            installedLabel = if (entry.isWebsite) {
                null
            } else {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(entry.identifier, 0)).toString()
                }.getOrNull()
            }
        )
    }

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.isWebsite) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(
                            1.dp,
                            accent.copy(alpha = 0.24f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                FocusGuardAppIcon(
                    packageName = entry.identifier,
                    appName = label,
                    modifier = Modifier.size(34.dp),
                    cornerRadius = 10.dp,
                    allowRemoteFallback = true
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = entryStatusText(entry),
                    color = accent,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * The "how long is left" line.
 *
 * Every type gets an honest answer rather than a blank: a daily limit has an
 * allowance instead of a deadline, and a password block lasts until the user ends
 * it. Leaving those empty would read as "no idea", which is worse than the truth.
 */
@Composable
private fun entryStatusText(
    entry: BlockingSessionManager.BlockOverview.Entry
): String {
    entry.dailyLimitMinutes?.let { minutes ->
        return stringResource(R.string.block_type_daily_allowance, minutes)
    }

    return when (val remaining = BlockCountdownPolicy.remaining(entry.unlockAtMillis)) {
        null -> stringResource(R.string.block_type_until_you_end_it)
        is BlockCountdownPolicy.Remaining.Days ->
            stringResource(R.string.block_type_remaining_days, remaining.days)
        is BlockCountdownPolicy.Remaining.Hours ->
            stringResource(R.string.block_type_remaining_hours, remaining.hours)
        BlockCountdownPolicy.Remaining.LessThanAnHour ->
            stringResource(R.string.block_type_remaining_less_than_hour)
        BlockCountdownPolicy.Remaining.Elapsed ->
            stringResource(R.string.block_type_remaining_elapsed)
    }
}
