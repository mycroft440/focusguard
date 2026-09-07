package com.focusguard.ui.compose.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single app-icon renderer used by blocking, usage-limit and analytics screens.
 *
 * Resolution priority is deliberate:
 * 1) installed app -> launcher Activity icon, matching the icon the user opens;
 * 2) installed app without a resolvable launcher icon -> Application icon;
 * 3) known uninstalled app -> official/domain favicon at high resolution;
 * 4) bundled brand artwork when available;
 * 5) branded local fallback as the last resort.
 *
 * Installed artwork is never painted on a FocusGuard fallback background and is
 * not forced into a new circular/rounded mask. Adaptive and legacy icons therefore
 * keep the shape and transparent padding supplied by Android/the app itself.
 */
@Composable
fun FocusGuardAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
    cornerRadius: Dp = 10.dp,
    allowRemoteFallback: Boolean = true
) {
    val context = LocalContext.current
    var installedBitmap by remember(packageName) {
        mutableStateOf(appIconCache.get(packageName))
    }
    var localLookupFinished by remember(packageName) {
        mutableStateOf(installedBitmap != null)
    }
    var remoteLoadFailed by remember(packageName, iconUrl) { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        if (installedBitmap != null) {
            localLookupFinished = true
            return@LaunchedEffect
        }

        val loaded = withContext(Dispatchers.IO) {
            loadInstalledLauncherBitmap(context, packageName)
        }

        if (loaded != null) {
            appIconCache.put(packageName, loaded)
            installedBitmap = loaded
        }
        localLookupFinished = true
    }

    val shape = RoundedCornerShape(cornerRadius)
    val installed = installedBitmap

    if (installed != null) {
        val bitmap = remember(installed) { installed.asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
        return
    }

    val bundledIcon = remember(packageName) { bundledPredefinedIcon(packageName) }
    val remoteIconUrl = remember(packageName, iconUrl, allowRemoteFallback) {
        if (!allowRemoteFallback) {
            null
        } else {
            predefinedFaviconUrl(packageName)
                ?: highResolutionCallerIconUrl(iconUrl)
        }
    }

    // Styled containers are restricted to targets Android cannot resolve locally.
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF111820)),
        contentAlignment = Alignment.Center
    ) {
        BrandedAppFallback(packageName = packageName, appName = appName)

        when {
            localLookupFinished && remoteIconUrl != null && !remoteLoadFailed -> {
                AsyncImage(
                    model = remoteIconUrl,
                    contentDescription = appName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onError = { remoteLoadFailed = true }
                )
            }

            bundledIcon != null -> {
                Image(
                    painter = painterResource(bundledIcon),
                    contentDescription = appName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun loadInstalledLauncherBitmap(context: Context, packageName: String): Bitmap? {
    val packageManager = context.packageManager
    val launcherDrawable = runCatching {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val component = launchIntent?.component ?: launchIntent?.resolveActivity(packageManager)
        component?.let { packageManager.getActivityIcon(it) }
    }.getOrNull()

    val drawable = launcherDrawable
        ?: runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        ?: return null

    return runCatching {
        drawable.toBitmap(APP_ICON_SIZE_PX, APP_ICON_SIZE_PX)
    }.getOrNull()
}

@DrawableRes
private fun bundledPredefinedIcon(packageName: String): Int? = when (packageName) {
    "com.instagram.android" -> R.drawable.ic_brand_instagram
    "com.facebook.katana" -> R.drawable.ic_brand_facebook
    "com.google.android.youtube" -> R.drawable.ic_brand_youtube
    else -> null
}

private fun predefinedFaviconUrl(packageName: String): String? =
    PredefinedApps.PREVENTIVE_APPS
        .firstOrNull { it.packageName == packageName }
        ?.domain
        ?.takeIf { it.isNotBlank() }
        ?.let { domain ->
            "https://www.google.com/s2/favicons?domain=$domain&sz=$REMOTE_ICON_SIZE_PX"
        }

/** Upgrade old Google S2 favicon URLs supplied by legacy callers without touching
 * arbitrary image providers. */
private fun highResolutionCallerIconUrl(iconUrl: String?): String? {
    val url = iconUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (!url.contains("google.com/s2/favicons", ignoreCase = true)) return url

    val sizeParameter = Regex("([?&]sz=)\\d+", RegexOption.IGNORE_CASE)
    return if (sizeParameter.containsMatchIn(url)) {
        sizeParameter.replace(url) { match ->
            "${match.groupValues[1]}$REMOTE_ICON_SIZE_PX"
        }
    } else {
        "$url${if ('?' in url) '&' else '?'}sz=$REMOTE_ICON_SIZE_PX"
    }
}

@Composable
private fun BrandedAppFallback(packageName: String, appName: String) {
    val background = remember(packageName) { fallbackBrandColor(packageName) }
    val mark = remember(packageName, appName) {
        when (packageName) {
            "com.instagram.android" -> "◎"
            "com.facebook.katana" -> "f"
            "com.google.android.youtube" -> "▶"
            "com.zhiliaoapp.musically" -> "♪"
            "com.twitter.android" -> "X"
            "com.netflix.mediaclient" -> "N"
            "com.spotify.music" -> "S"
            "com.discord" -> "D"
            else -> appName.trim().take(1).uppercase().ifBlank { "•" }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mark,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

private fun fallbackBrandColor(packageName: String): Color = when {
    packageName.contains("instagram") -> Color(0xFFE4405F)
    packageName.contains("facebook") -> Color(0xFF1877F2)
    packageName.contains("youtube") -> Color(0xFFFF0000)
    packageName.contains("tiktok") -> Color(0xFF111111)
    packageName.contains("twitter") -> Color(0xFF111111)
    packageName.contains("netflix") -> Color(0xFFE50914)
    packageName.contains("spotify") -> Color(0xFF1DB954)
    packageName.contains("tinder") -> Color(0xFFFE3C72)
    packageName.contains("twitch") -> Color(0xFF9146FF)
    packageName.contains("discord") -> Color(0xFF5865F2)
    else -> Color(packageName.hashCode()).copy(alpha = 1f)
}

private const val APP_ICON_SIZE_PX = 256
private const val REMOTE_ICON_SIZE_PX = 256
private const val APP_ICON_CACHE_MAX_BYTES = 12 * 1024 * 1024
private val appIconCache = object : LruCache<String, Bitmap>(APP_ICON_CACHE_MAX_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
