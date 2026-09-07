package com.focusguard.focusmode

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.admin.FocusGuardDeviceAdminReceiver
import com.focusguard.utils.FocusGuardLogger

/**
 * Protects the device Home surface while native Focus Mode kiosk enforcement is active.
 *
 * Lock Task itself owns the primary navigation guard: HOME and OVERVIEW are deliberately
 * omitted from [DevicePolicyManager.setLockTaskFeatures], so Android consumes the Home
 * button/gesture instead of briefly leaving Focus Mode and asking HardBlock to recover.
 *
 * The temporary HOME alias remains configured only as a fail-closed fallback for the short
 * boot/process-recreation interval before Lock Task is restored. Global actions stay enabled
 * so the protected power menu can still forward normal shutdown/restart actions.
 */
object FocusModeHomeController {
    internal const val HOME_ALIAS_CLASS =
        "com.focusguard.focusmode.FocusModeHomeActivity"

    fun reconcile(context: Context): Boolean {
        val appContext = context.applicationContext
        val active = FocusModeStore.isActive(appContext)
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
            ?: return !active
        val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)
        val deviceOwner = runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }
            .getOrDefault(false)

        if (!active || !deviceOwner || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            if (!active || !deviceOwner) disableHomeAlias(appContext)
            return true
        }

        return runCatching {
            // Keep a safe Home destination in reserve for restoration races, but do not
            // enable LOCK_TASK_FEATURE_HOME. While Lock Task is active the system must
            // suppress Home rather than execute it and bounce through this alias.
            setHomeAliasEnabled(appContext, true)
            dpm.addPersistentPreferredActivity(
                admin,
                homeIntentFilter(),
                homeComponent(appContext)
            )
            dpm.setLockTaskFeatures(admin, requiredLockTaskFeatures())
            isNativeHomeConfigured(appContext)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao proteger o Home durante o Modo Foco",
                error
            )
        }.getOrDefault(false)
    }

    fun isNativeHomeConfigured(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)) {
            return false
        }
        val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)
        val features = runCatching { dpm.getLockTaskFeatures(admin) }.getOrNull() ?: return false
        return lockTaskFeaturesBlockHomeAndKeepPower(features) &&
            isHomeAliasEnabled(appContext) &&
            isHomeResolvedToFocusGuard(appContext)
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        val deviceOwner = dpm?.let {
            runCatching { it.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)
        } == true
        if (deviceOwner) {
            val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)
            runCatching {
                dpm?.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "FocusMode",
                    "Falha ao restaurar o launcher padrão após o Modo Foco",
                    error
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    dpm?.setLockTaskFeatures(
                        admin,
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    )
                }.onFailure { error ->
                    FocusGuardLogger.logError(
                        "FocusMode",
                        "Falha ao restaurar recursos padrão do Lock Task",
                        error
                    )
                }
            }
        }
        disableHomeAlias(appContext)
    }

    /**
     * HOME/OVERVIEW are intentionally absent. Their absence is what makes Android keep the
     * current allowlisted task in front when the user presses or swipes Home.
     */
    internal fun requiredLockTaskFeatures(): Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    internal fun lockTaskFeaturesBlockHomeAndKeepPower(features: Int): Boolean =
        features == requiredLockTaskFeatures()

    /** Kept temporarily for source compatibility with older tests/helpers. */
    @Deprecated("Use lockTaskFeaturesBlockHomeAndKeepPower")
    internal fun lockTaskFeaturesKeepHomeAndPower(features: Int): Boolean =
        lockTaskFeaturesBlockHomeAndKeepPower(features)

    internal fun homeIntentFilter(): IntentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    internal fun homeComponent(context: Context): ComponentName =
        ComponentName(context.packageName, HOME_ALIAS_CLASS)

    private fun isHomeAliasEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(homeComponent(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun isHomeResolvedToFocusGuard(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return runCatching {
            context.packageManager.resolveActivity(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    private fun disableHomeAlias(context: Context) {
        setHomeAliasEnabled(context, false)
    }

    private fun setHomeAliasEnabled(context: Context, enabled: Boolean) {
        val targetState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (context.packageManager.getComponentEnabledSetting(homeComponent(context)) == targetState) {
            return
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                homeComponent(context),
                targetState,
                PackageManager.DONT_KILL_APP
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao atualizar a Home temporária do Modo Foco",
                error
            )
        }
    }
}
