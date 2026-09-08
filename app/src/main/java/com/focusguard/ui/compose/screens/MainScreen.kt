package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.UserProfile
import com.focusguard.security.ProtectionPermission
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    profile: UserProfile,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    focusModeActive: Boolean,
    missingProtectionPermissions: List<ProtectionPermission>,
    showCreatorInstagramCard: Boolean,
    showCreatorFeedbackButton: Boolean,
    onPermissionsClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
    onBlockTypeClick: (BlockTypeUi) -> Unit,
    onSettingsClick: () -> Unit,
    usageStatsContent: @Composable () -> Unit,
    pomodoroContent: @Composable () -> Unit,
    recoveryContent: @Composable () -> Unit,
    focusModeContent: @Composable () -> Unit
) {
    val settingsContentDescription = if (profile.isConfigured) {
        stringResource(R.string.profile_settings_content_description, profile.displayName)
    } else {
        stringResource(R.string.nav_settings)
    }
    val usesFullHeightContent = selectedTab == 2 || selectedTab == 3

    BackHandler(enabled = focusModeActive) {
        if (selectedTab != 4) onTabChange(4)
    }

    // O halo ciano cobre barra de título, conteúdo e barra de navegação de uma
    // vez só; por isso o Scaffold e o topo ficam transparentes, senão cada um
    // pintaria o próprio retângulo por cima do degradê.
    FocusGuardAmbientBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!usesFullHeightContent) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(64.dp)
                    ) {
                        if (selectedTab == 4) {
                            Text(
                                stringResource(R.string.nav_focus_mode),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 16.dp),
                                color = TextPrimary,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.6).sp
                            )
                        } else if (!focusModeActive) {
                            FocusCard(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 16.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, AccentCyanEdge),
                                onClick = { onTabChange(0) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Public,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.nav_metrics),
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .semantics {
                                    contentDescription = settingsContentDescription
                                }
                        ) {
                            TopBarProfileGlyph(profile = profile)
                        }
                    }
                }
            },
            bottomBar = {
                if (!focusModeActive) {
                    FocusGuardBottomNavigation(
                        selectedTab = selectedTab,
                        onTabChange = onTabChange
                    )
                }
            }
        ) { paddingValues ->
            // A aba do Pomodoro mantém o próprio fundo sólido — a tela dela já é
            // fechada em si; as outras deixam o halo aparecer por trás.
            val contentBackground = if (selectedTab == 2) DarkBg else Color.Transparent
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(contentBackground)
                    .padding(paddingValues)
                    .then(if (usesFullHeightContent) Modifier.statusBarsPadding() else Modifier)
            ) {
                if (focusModeActive) {
                    FocusModeNavigationRail(
                        selectedTab = selectedTab,
                        onTabChange = onTabChange
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(contentBackground)
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(140)) togetherWith
                                fadeOut(animationSpec = tween(140))
                        },
                        label = "MainContent"
                    ) { targetTab ->
                        when (targetTab) {
                            0 -> usageStatsContent()
                            1 -> HomeContent(
                                missingProtectionPermissions = missingProtectionPermissions,
                                showCreatorInstagramCard = showCreatorInstagramCard,
                                showCreatorFeedbackButton = showCreatorFeedbackButton,
                                onPermissionsClick = onPermissionsClick,
                                onCreatorInstagramClick = onCreatorInstagramClick,
                                onBlockTypeClick = onBlockTypeClick,
                                pagerHint = false
                            )
                            2 -> pomodoroContent()
                            3 -> recoveryContent()
                            4 -> focusModeContent()
                        }
                    }

                    if (usesFullHeightContent) {
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 12.dp)
                                .semantics {
                                    contentDescription = settingsContentDescription
                                }
                        ) {
                            TopBarProfileGlyph(profile = profile)
                        }
                    }
                }
            }
        }
    }
}

/**
 * O botão da direita na barra de título.
 *
 * Com perfil configurado mostra o avatar dentro de um anel ciano — o mesmo
 * anel em todas as abas, para o atalho de configurações ficar reconhecível de
 * relance; sem perfil, continua sendo o ícone de menu de antes.
 */
@Composable
private fun TopBarProfileGlyph(profile: UserProfile) {
    if (profile.isConfigured) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AccentCyanWash)
                .border(1.dp, AccentCyanEdge, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatar(
                avatarId = profile.avatarId,
                modifier = Modifier.size(34.dp)
            )
        }
    } else {
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun FocusGuardBottomNavigation(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(DarkSurface, DarkBg))
            )
    ) {
        // Um fio de luz ciano no lugar do traço cinza: marca onde a barra
        // começa e amarra a navegação à cor da casa.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(CardBorder, AccentCyanEdge, CardBorder)
                    )
                )
        )
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            FocusGuardNavigationItems.forEach { item ->
                val selected = selectedTab == item.tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabChange(item.tab) },
                    icon = {
                        Icon(
                            item.icon,
                            contentDescription = stringResource(item.labelRes),
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(item.labelRes),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    alwaysShowLabel = true,
                    colors = navigationItemColors()
                )
            }
        }
    }
}

@Composable
private fun FocusModeNavigationRail(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    NavigationRail(
        containerColor = DarkSurface,
        header = {
            Icon(
                Icons.Default.LockClock,
                contentDescription = stringResource(R.string.nav_focus_mode),
                tint = AccentCyan,
                modifier = Modifier.padding(vertical = 12.dp).size(28.dp)
            )
        }
    ) {
        FocusGuardNavigationItems.forEach { item ->
            NavigationRailItem(
                selected = selectedTab == item.tab,
                onClick = { onTabChange(item.tab) },
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = { Text(stringResource(item.labelRes), fontSize = 10.sp) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextHint,
                    unselectedTextColor = TextHint,
                    indicatorColor = AccentCyan.copy(alpha = 0.12f)
                )
            )
        }
    }
}

private data class FocusGuardNavigationItem(
    val tab: Int,
    val icon: ImageVector,
    val labelRes: Int
)

private val FocusGuardNavigationItems = listOf(
    FocusGuardNavigationItem(1, Icons.Default.Shield, R.string.nav_protection),
    FocusGuardNavigationItem(2, Icons.Default.Timer, R.string.nav_focus),
    FocusGuardNavigationItem(3, Icons.Outlined.VisibilityOff, R.string.nav_recovery),
    FocusGuardNavigationItem(4, Icons.Default.LockClock, R.string.nav_focus_mode)
)

internal fun pendingPermissionsDescriptionRes(
    missingPermissions: List<ProtectionPermission>
): Int {
    return when (missingPermissions.toSet()) {
        setOf(ProtectionPermission.SELF_PROTECTION_CONSENT) ->
            R.string.pending_self_protection_consent_desc
        setOf(ProtectionPermission.ACCESSIBILITY) ->
            R.string.pending_permissions_accessibility_desc
        setOf(ProtectionPermission.USAGE_ACCESS) ->
            R.string.pending_permissions_usage_access_desc
        else -> R.string.pending_permissions_desc
    }
}

@Composable
private fun navigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = AccentCyan,
    selectedTextColor = AccentCyan,
    unselectedIconColor = TextHint,
    unselectedTextColor = TextSecondary.copy(alpha = 0.75f),
    // A pílula da aba selecionada fica mais presente: com 12% de opacidade ela
    // quase sumia no fundo e a aba atual só se distinguia pela cor do ícone.
    indicatorColor = AccentCyan.copy(alpha = 0.18f)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerMenuButton(
    icon: ImageVector,
    label: String,
    iconTint: Color = AccentCyan,
    labelColor: Color = TextPrimary,
    bgColor: Color = DarkCard,
    onClick: () -> Unit
) {
    FocusCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        brush = focusCardBrush(bgColor, bgColor),
        border = BorderStroke(1.dp, CardBorder),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = labelColor)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_open), tint = TextHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun HomeContent(
    missingProtectionPermissions: List<ProtectionPermission>,
    showCreatorInstagramCard: Boolean,
    showCreatorFeedbackButton: Boolean,
    onPermissionsClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
    onBlockTypeClick: (BlockTypeUi) -> Unit,
    pagerHint: Boolean
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = 8.dp,
                    bottom = if (pagerHint) 64.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn(animationSpec = tween(450)) +
                    slideInVertically(animationSpec = tween(450)) { -20 }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // O escudo passa a ser o ponto de luz da tela: um disco de
                    // ciano difuso atrás dele dá o brilho que um ícone chapado
                    // sobre fundo preto não consegue sozinho.
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(AccentCyanGlow, Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield),
                            contentDescription = stringResource(
                                R.string.content_focusguard_logo
                            ),
                            modifier = Modifier.size(38.dp),
                            tint = AccentCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(id = R.string.app_name),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(id = R.string.focus_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp, bottom = 20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = visible && missingProtectionPermissions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                FocusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    brush = accentWashBrush(DangerRed),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.55f)),
                    onClick = onPermissionsClick
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.content_warning),
                            tint = DangerRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.pending_permissions_title), color = DangerRed, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(
                                    id = pendingPermissionsDescriptionRes(
                                        missingProtectionPermissions
                                    )
                                ),
                                color = DangerRed.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.action_open),
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(animationSpec = tween(500, delayMillis = 150)) { 30 }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BlockTypeUi.entries.forEach { type ->
                        SessionCard(
                            icon = type.icon,
                            title = stringResource(id = type.titleRes),
                            subtitle = stringResource(id = type.subtitleRes),
                            accent = type.accent,
                            compact = true,
                            onClick = { onBlockTypeClick(type) }
                        )
                    }
                    AnimatedVisibility(
                        visible = showCreatorInstagramCard,
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        CreatorInstagramCard(
                            onClick = onCreatorInstagramClick,
                            compact = true
                        )
                    }
                    AnimatedVisibility(
                        visible = showCreatorFeedbackButton,
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        CreatorFeedbackButton(
                            onClick = onCreatorInstagramClick,
                            compact = true
                        )
                    }
                }
            }
        }

        if (pagerHint) {
            Text(
                stringResource(id = R.string.swipe_hint),
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorInstagramCard(
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val instagramGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF833AB4),
            Color(0xFFE1306C),
            Color(0xFFF77737),
            Color(0xFFFCAF45)
        )
    )

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, instagramGradient),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 40.dp else 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(instagramGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 22.dp else 27.dp)
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.creator_instagram_title),
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.creator_instagram_handle),
                    color = Color(0xFFE1306C),
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                Text(
                    text = stringResource(R.string.creator_instagram_description),
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = Color(0xFFE1306C),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorFeedbackButton(
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val attentionTransition = rememberInfiniteTransition(label = "FeedbackAttention")
    val attentionAlpha by attentionTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 7_000
                1f at 0
                1f at 5_500
                0f at 7_000 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "FeedbackAttentionAlpha"
    )

    FocusCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = attentionAlpha },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorder),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 11.dp else 16.dp,
                vertical = if (compact) 9.dp else 14.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 36.dp else 42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AccentCyan.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = AccentCyan.copy(alpha = 0.82f),
                    modifier = Modifier.size(if (compact) 19.dp else 22.dp)
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.creator_feedback_title),
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
                Text(
                    text = stringResource(R.string.creator_feedback_description),
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * O cartão de cada tipo de bloqueio na tela inicial.
 *
 * O acento recebido (ciano, âmbar ou vermelho, conforme o tipo) aparece no
 * selo do ícone e na seta, sem uma faixa colorida junto à borda do cartão.
 */
@Composable
fun SessionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color = AccentCyan,
    compact: Boolean = false
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CardBorder),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.padding(
                    start = if (compact) 17.dp else 20.dp,
                    end = if (compact) 13.dp else 16.dp,
                    top = if (compact) 12.dp else 16.dp,
                    bottom = if (compact) 12.dp else 16.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccentIconBadge(
                    icon = icon,
                    accent = accent,
                    size = if (compact) 44.dp else 48.dp,
                    iconSize = if (compact) 24.dp else 26.dp,
                    shape = RoundedCornerShape(if (compact) 13.dp else 16.dp)
                )
                Spacer(modifier = Modifier.width(if (compact) 12.dp else 16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = if (compact) 15.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.1).sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(if (compact) 3.dp else 4.dp))
                    Text(
                        subtitle,
                        fontSize = if (compact) 12.sp else 13.sp,
                        lineHeight = if (compact) 16.sp else 18.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.action_open),
                        modifier = Modifier.size(17.dp),
                        tint = accent.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}