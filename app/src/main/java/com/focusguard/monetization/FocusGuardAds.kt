package com.focusguard.monetization

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.focusguard.utils.FocusGuardLogger
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ponto único de integração de anúncios do FocusGuard.
 *
 * Por decisão do projeto, Debug e Release usam permanentemente os IDs oficiais de
 * teste do Google. A infraestrutura de consentimento, lifecycle e recompensa é a
 * mesma que seria usada com unidades monetizadas, mas estes IDs não geram receita.
 */
object FocusGuardAds {
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"
    const val TEST_ADAPTIVE_BANNER_ID = "ca-app-pub-3940256099942544/9214589741"
    const val TEST_NATIVE_ID = "ca-app-pub-3940256099942544/2247696110"

    private const val ADAPTIVE_BANNER_PRELOAD_BUFFER_SIZE = 2
    private const val ADAPTIVE_BANNER_PRELOAD_PREFIX = "focusguard-adaptive-banner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    private val pomodoroAdInFlight = AtomicBoolean(false)

    /**
     * Mantido para compatibilidade com o Application. O processo ainda não possui
     * uma Activity capaz de concluir a UMP, portanto o preload real começa no
     * primeiro warmUp(ComponentActivity).
     */
    fun warmUp(context: Context) {
        FocusGuardLogger.log("Ads", "Warm-up aguardando a primeira Activity para validar consentimento")
    }

    /**
     * Inicia consentimento + SDK + preload do banner já na abertura da Activity.
     *
     * O preloader oficial mantém um buffer pequeno e o reabastece automaticamente
     * depois que uma tela consome um BannerAd. Nenhum AdView invisível é mantido.
     */
    fun warmUp(activity: ComponentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        withAdsReady(
            activity = activity,
            onUnavailable = { message ->
                FocusGuardLogger.log("Ads", "Warm-up indisponível: $message")
            },
            onReady = {
                val widthDp = activity.resources.configuration.screenWidthDp.coerceAtLeast(300)
                startAdaptiveBannerPreload(activity, widthDp)
            }
        )
    }

    private suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            MobileAds.initialize(
                context,
                InitializationConfig.Builder(TEST_APP_ID).build()
            ) { }
            initialized = true
        }
    }

    private fun withAdsReady(
        activity: ComponentActivity,
        onReady: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onUnavailable("A tela não está disponível para exibir anúncios.")
            return
        }

        activity.runOnUiThread {
            AdsConsentManager.ensureCanRequestAds(activity) { canRequestAds ->
                if (!canRequestAds) {
                    onUnavailable("Os anúncios não podem ser solicitados com as escolhas de privacidade atuais.")
                    return@ensureCanRequestAds
                }

                scope.launch {
                    runCatching { ensureInitialized(activity.applicationContext) }
                        .onFailure { error ->
                            withContext(Dispatchers.Main) {
                                onUnavailable(
                                    error.message ?: "Não foi possível inicializar os anúncios."
                                )
                            }
                        }
                        .onSuccess {
                            withContext(Dispatchers.Main) {
                                if (activity.isFinishing || activity.isDestroyed) {
                                    onUnavailable("A tela não está mais disponível.")
                                } else {
                                    onReady()
                                }
                            }
                        }
                }
            }
        }
    }

    private fun startAdaptiveBannerPreload(
        activity: ComponentActivity,
        widthDp: Int
    ) {
        val normalizedWidthDp = widthDp.coerceAtLeast(300)
        val preloadId = adaptiveBannerPreloadId(normalizedWidthDp)
        val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            activity,
            normalizedWidthDp
        )
        val request = BannerAdRequest.Builder(
            TEST_ADAPTIVE_BANNER_ID,
            adSize
        ).build()
        val started = BannerAdPreloader.start(
            preloadId,
            PreloadConfiguration(
                request = request,
                bufferSize = ADAPTIVE_BANNER_PRELOAD_BUFFER_SIZE
            )
        )
        FocusGuardLogger.log(
            "Ads",
            if (started) {
                "Preload de banner iniciado para largura ${normalizedWidthDp}dp"
            } else {
                "Preload de banner já ativo para largura ${normalizedWidthDp}dp"
            }
        )
    }

    private fun adaptiveBannerPreloadId(widthDp: Int): String =
        "$ADAPTIVE_BANNER_PRELOAD_PREFIX-${widthDp.coerceAtLeast(300)}"

    fun loadNative(
        activity: ComponentActivity,
        onLoaded: (NativeAd) -> Unit,
        onUnavailable: (String) -> Unit = {}
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                val request = NativeAdRequest.Builder(
                    TEST_NATIVE_ID,
                    listOf(NativeAd.NativeAdType.NATIVE)
                ).build()
                NativeAdLoader.load(
                    request,
                    object : NativeAdLoaderCallback {
                        override fun onNativeAdLoaded(nativeAd: NativeAd) {
                            if (activity.isFinishing || activity.isDestroyed) {
                                nativeAd.destroy()
                            } else {
                                onLoaded(nativeAd)
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            onUnavailable(
                                adError.message.ifBlank {
                                    "Nenhum anúncio nativo está disponível agora."
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    fun loadLargeAdaptiveBanner(
        activity: ComponentActivity,
        adView: AdView,
        widthDp: Int,
        onLoaded: () -> Unit = {},
        onUnavailable: (String) -> Unit = {}
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                val normalizedWidthDp = widthDp.coerceAtLeast(300)
                val preloadId = adaptiveBannerPreloadId(normalizedWidthDp)
                val preloadedAd = BannerAdPreloader.pollAd(preloadId)
                if (preloadedAd != null) {
                    adView.registerBannerAd(preloadedAd, activity)
                    FocusGuardLogger.log(
                        "Ads",
                        "Banner adaptativo servido do preload para ${normalizedWidthDp}dp"
                    )
                    onLoaded()
                    return@withAdsReady
                }

                val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
                    activity,
                    normalizedWidthDp
                )
                val request = BannerAdRequest.Builder(
                    TEST_ADAPTIVE_BANNER_ID,
                    adSize
                ).build()
                adView.loadAd(
                    request,
                    object : AdLoadCallback<BannerAd> {
                        override fun onAdLoaded(ad: BannerAd) {
                            FocusGuardLogger.log("Ads", "Banner adaptativo carregado diretamente")
                            onLoaded()
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            FocusGuardLogger.log(
                                "Ads",
                                "Banner adaptativo indisponível: ${adError.message}"
                            )
                            onUnavailable(
                                adError.message.ifBlank { "Nenhum banner está disponível agora." }
                            )
                        }
                    }
                )
            }
        )
    }

    /** A recompensa só é creditada por onUserEarnedReward. */
    fun showRewarded(
        activity: ComponentActivity,
        onRewardEarned: () -> Unit,
        onClosedWithoutReward: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    onUnavailable("Volte para o aplicativo e tente novamente.")
                    return@withAdsReady
                }

                RewardedAd.load(
                    AdRequest.Builder(TEST_REWARDED_ID).build(),
                    object : AdLoadCallback<RewardedAd> {
                        override fun onAdLoaded(ad: RewardedAd) {
                            var rewardEarned = false
                            ad.adEventCallback = object : RewardedAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    if (!rewardEarned) onClosedWithoutReward()
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    if (!rewardEarned) {
                                        onUnavailable(
                                            fullScreenContentError.message.ifBlank {
                                                "O anúncio não pôde ser exibido."
                                            }
                                        )
                                    }
                                }
                            }
                            ad.show(activity) {
                                if (!rewardEarned) {
                                    rewardEarned = true
                                    onRewardEarned()
                                }
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            onUnavailable(
                                adError.message.ifBlank {
                                    "Nenhum anúncio está disponível agora."
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    /**
     * Exibe no máximo um intersticial por conclusão persistida de plano Pomodoro.
     * Falha de carregamento mantém a conclusão na fila; falha ao apresentar devolve
     * a reserva à fila para uma futura tentativa.
     */
    fun showPendingPomodoroCompletion(activity: ComponentActivity) {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!MonetizationStateStore.hasPomodoroCompletionAdPending(activity)) return
        if (!pomodoroAdInFlight.compareAndSet(false, true)) return

        withAdsReady(
            activity = activity,
            onUnavailable = { message ->
                pomodoroAdInFlight.set(false)
                FocusGuardLogger.log("Ads", "Pomodoro aguardando anúncio: $message")
            },
            onReady = {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pomodoroAdInFlight.set(false)
                    return@withAdsReady
                }

                InterstitialAd.load(
                    AdRequest.Builder(TEST_INTERSTITIAL_ID).build(),
                    object : AdLoadCallback<InterstitialAd> {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            if (activity.isFinishing || activity.isDestroyed ||
                                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            ) {
                                pomodoroAdInFlight.set(false)
                                return
                            }

                            if (!MonetizationStateStore.consumePomodoroCompletionAdPending(activity)) {
                                pomodoroAdInFlight.set(false)
                                return
                            }

                            var reservationRestored = false
                            fun restoreReservation() {
                                if (!reservationRestored) {
                                    reservationRestored = true
                                    MonetizationStateStore.restorePomodoroCompletionAdPending(activity)
                                }
                            }

                            ad.adEventCallback = object : InterstitialAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    pomodoroAdInFlight.set(false)
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    restoreReservation()
                                    pomodoroAdInFlight.set(false)
                                    FocusGuardLogger.log(
                                        "Ads",
                                        "Interstitial Pomodoro indisponível: ${fullScreenContentError.message}"
                                    )
                                }
                            }

                            runCatching { ad.show(activity) }
                                .onFailure { error ->
                                    restoreReservation()
                                    pomodoroAdInFlight.set(false)
                                    FocusGuardLogger.logError(
                                        "Ads",
                                        "Falha ao apresentar intersticial do Pomodoro",
                                        error
                                    )
                                }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            pomodoroAdInFlight.set(false)
                            FocusGuardLogger.log(
                                "Ads",
                                "Interstitial Pomodoro não carregou: ${adError.message}"
                            )
                        }
                    }
                )
            }
        )
    }
}
