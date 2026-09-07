package com.focusguard

import com.focusguard.monetization.FocusGuardAds
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.navigation.FocusGuardNavHost
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // Injetados via Hilt — usam singletons do app (corrigido em P0-2 / P3-1).
    // Antes eram instanciados direto, criando instâncias paralelas e disparando
    // migrações em paralelo (AuthManager) ou multiplas instâncias de manager.
    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var focusModeManager: FocusModeManager

    private lateinit var pomodoroManager: PomodoroManager
    private var grayscaleApplied: Boolean? = null
    private var contentDrawn = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L
    private val focusModeReturnNonce = mutableLongStateOf(0L)
    private val pomodoroNavigationNonce = mutableLongStateOf(0L)

    /**
     * Last-resort Back guard for every FocusGuard screen. Compose screens keep
     * their own navigation handlers, but if one of them stops consuming Back,
     * an active Focus Mode must never finish the root Activity and reveal Home.
     */
    private val focusModeBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!focusModeManager.isActive()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
                return
            }

            FocusGuardLogger.log("FocusMode", "Voltar interceptado pelo shell do Modo Foco")
            requestFocusModeHomeSurface()
            FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
            enforceFocusModeLockTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardLogger.init(this)
        FocusGuardLogger.log("MainActivity", "onCreate disparado")

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()

        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        // Inicia UMP, Mobile Ads e o buffer oficial de banners já na abertura.
        // Assim as telas com banner normalmente consomem um anúncio já pronto.
        FocusGuardAds.warmUp(this)

        // PomodoroManager ainda usa o singleton legado.
        pomodoroManager = PomodoroManager.getInstance(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pomodoroManager.onSessionFinished.collect {
                    FocusGuardAds.showPendingPomodoroCompletion(this@MainActivity)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, focusModeBackGuard)
        updateFocusModeBackGuard()
        consumeFocusModeReturnIntent(intent)
        consumePomodoroNavigationIntent(intent)
        FocusModeKioskController.reconcileSystemRestrictions(this)

        FocusGuardLogger.log("MainActivity", "Managers inicializados com sucesso")

        setContent {
            FocusGuardTheme {
                FocusGuardNavHost(
                    activity = this,
                    authManager = authManager,
                    pomodoroManager = pomodoroManager,
                    focusModeManager = focusModeManager,
                    focusModeReturnNonce = focusModeReturnNonce.longValue,
                    pomodoroNavigationNonce = pomodoroNavigationNonce.longValue,
                    onEnforceFocusModeLockTask = ::enforceFocusModeLockTask
                )
            }
        }
        notifyCurtainWhenDrawn(intent)
        applyFocusModeGrayscale(
            focusModeManager.session.value?.let {
                it.isActive() && it.grayscaleEnabled
            } == true
        )
        observeFocusModeVisualState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeFocusModeReturnIntent(intent)
        consumePomodoroNavigationIntent(intent)
        updateFocusModeBackGuard()
        enforceFocusModeLockTask()
        notifyCurtainWhenDrawn(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        window.decorView.post { FocusGuardAds.showPendingPomodoroCompletion(this@MainActivity) }
        acknowledgePendingCurtainIfPresented()
        FocusGuardLogger.log("MainActivity", "onResume disparado")
        updateFocusModeBackGuard()
        FocusModeKioskController.reconcileSystemRestrictions(this)
        enforceFocusModeLockTask()
    }

    override fun onPause() {
        activityResumed = false
        FocusGuardLogger.log("MainActivity", "onPause disparado")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) acknowledgePendingCurtainIfPresented()
    }

    fun enforceFocusModeLockTask() {
        updateFocusModeBackGuard()
        FocusModeKioskController.reconcileSystemRestrictions(this)
        val deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        // Lock Task allowlisting survives process death. Re-enter it immediately
        // on resume, before the asynchronous full policy reconciliation, so there
        // is no Home-button escape window after Android recreates this activity.
        if (focusModeManager.isActive() &&
            deviceOwnerManager.isFocusModeLockTaskPermitted()
        ) {
            runCatching { startLockTask() }
        }
        lifecycleScope.launch {
            if (!focusModeManager.ensureEnforced()) {
                updateFocusModeBackGuard()
                FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
                return@launch
            }
            updateFocusModeBackGuard()
            FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
            if (!deviceOwnerManager.isDeviceOwnerActive()) return@launch
            runCatching { startLockTask() }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "FocusMode",
                        "Falha ao iniciar Lock Task na atividade principal",
                        error
                    )
                }
        }
    }

    private fun requestFocusModeHomeSurface() {
        focusModeReturnNonce.longValue = focusModeReturnNonce.longValue + 1L
    }

    private fun consumeFocusModeReturnIntent(sourceIntent: Intent) {
        if (!focusModeManager.isActive()) return
        val explicitRestore = sourceIntent.getBooleanExtra(
            FocusModeKioskController.EXTRA_RESTORE_FOCUS_MODE,
            false
        )
        val homeIntent = sourceIntent.action == Intent.ACTION_MAIN &&
            sourceIntent.categories?.contains(Intent.CATEGORY_HOME) == true
        if (explicitRestore || homeIntent) requestFocusModeHomeSurface()
    }

    private fun consumePomodoroNavigationIntent(sourceIntent: Intent) {
        if (!sourceIntent.getBooleanExtra(EXTRA_OPEN_POMODORO, false)) return
        sourceIntent.removeExtra(EXTRA_OPEN_POMODORO)
        if (!focusModeManager.isActive()) {
            pomodoroNavigationNonce.longValue = pomodoroNavigationNonce.longValue + 1L
        }
    }

    private fun updateFocusModeBackGuard() {
        focusModeBackGuard.isEnabled = focusModeManager.isActive()
    }

    private fun notifyCurtainWhenDrawn(sourceIntent: Intent) {
        val generation = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        if (generation <= 0L) return
        pendingCurtainGeneration = generation
        freshFrameGeneration = 0L
        if (acknowledgePendingCurtainIfPresented()) return
        window.decorView.doOnPreDraw {
            contentDrawn = true
            if (pendingCurtainGeneration == generation &&
                activityResumed && window.decorView.isShown
            ) {
                freshFrameGeneration = generation
            }
            acknowledgePendingCurtainIfPresented()
        }
        window.decorView.invalidate()
    }

    private fun acknowledgePendingCurtainIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
        if (generation <= 0L) return false
        val decor = window.decorView
        val ready = SafeSurfaceReadinessPolicy.decide(
            alreadyDrawn = contentDrawn,
            freshFrameAfterRequest = freshFrameGeneration == generation,
            lifecycleResumed = activityResumed,
            decorShown = decor.isShown,
            windowFocused = windowFocused
        ) == SafeSurfaceReadinessPolicy.Decision.ACK_NOW
        if (!ready) return false
        pendingCurtainGeneration = 0L
        freshFrameGeneration = 0L
        notifyCurtainReady(generation)
        return true
    }

    private fun notifyCurtainReady(generation: Long) {
        CurtainDestinationReadyCoordinator.notifyReady(generation)
    }

    private fun observeFocusModeVisualState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                focusModeManager.session.collect { session ->
                    val active = session?.isActive() == true
                    focusModeBackGuard.isEnabled = active
                    FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
                    applyFocusModeGrayscale(active && session?.grayscaleEnabled == true)
                    if (active) {
                        val deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
                        if (deviceOwnerManager.isFocusModeLockTaskPermitted()) {
                            runCatching { startLockTask() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Android exposes no public API for a third-party DPC to recolor every app.
     * Focus Mode therefore desaturates the complete FocusGuard activity layer;
     * selected external emergency/communication apps keep their own rendering.
     */
    private fun applyFocusModeGrayscale(enabled: Boolean) {
        if (grayscaleApplied == enabled) return
        grayscaleApplied = enabled
        val decorView = window.decorView
        if (enabled) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            decorView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        decorView.invalidate()
    }

    companion object {
        const val EXTRA_OPEN_POMODORO = "com.focusguard.extra.OPEN_POMODORO"
    }
}
