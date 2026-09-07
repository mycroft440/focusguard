package com.focusguard.ui.compose.components

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.focusguard.R
import com.focusguard.monetization.FocusGuardAds
import com.focusguard.ui.compose.theme.DarkBg
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import kotlin.math.roundToInt

/**
 * Anchored adaptive banner that owns the AdView lifecycle.
 *
 * The Android view is attached only after the SDK confirms a loaded banner. A
 * denied consent choice or load failure therefore reserves no empty footer space.
 * Loaded ads are framed as one visual block: a thin outline surrounds the full
 * ad while a deliberately thicker top band carries the app-support message.
 */
@Composable
fun FocusGuardBannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() } ?: return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBg)
    ) {
        val widthDp = maxWidth.value.roundToInt().coerceAtLeast(300)
        var loaded by remember(activity, widthDp) { mutableStateOf(false) }
        var unavailable by remember(activity, widthDp) { mutableStateOf(false) }
        val adView = remember(activity, widthDp) { AdView(activity) }

        DisposableEffect(adView) {
            onDispose {
                adView.destroy()
            }
        }

        LaunchedEffect(activity, adView, widthDp) {
            loaded = false
            unavailable = false
            FocusGuardAds.loadLargeAdaptiveBanner(
                activity = activity,
                adView = adView,
                widthDp = widthDp,
                onLoaded = { loaded = true },
                onUnavailable = {
                    loaded = false
                    unavailable = true
                }
            )
        }

        if (loaded && !unavailable) {
            val borderColor = MaterialTheme.colorScheme.outlineVariant
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = borderColor)
                    .background(DarkBg)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(borderColor.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.ad_support_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                AndroidView(
                    factory = { adView },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
