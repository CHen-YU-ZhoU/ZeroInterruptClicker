package com.zerointerrupt.clicker.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerointerrupt.clicker.ui.components.AnimatedTabIcon
import com.zerointerrupt.clicker.ui.components.SetupWizardBottomSheet
import com.zerointerrupt.clicker.ui.tabs.HomeTab
import com.zerointerrupt.clicker.ui.tabs.LabTab
import com.zerointerrupt.clicker.ui.tabs.PresetsTab
import com.zerointerrupt.clicker.ui.tabs.TuningTab

private val NavPillExpandEasing = CubicBezierEasing(0.18f, 0.88f, 0.24f, 1f)

@Composable
fun MainScreen(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    snackbarHostState: SnackbarHostState,
    requestedTab: MainTab? = null,
    onConsumeRequestedTab: () -> Unit = {},
    onCurrentTabChanged: (MainTab) -> Unit = {}
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var showWizardSheet by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            currentTab = requestedTab
            onConsumeRequestedTab()
        }
    }

    LaunchedEffect(currentTab) {
        onCurrentTabChanged(currentTab)
    }

    val styledSnackbarHost: @Composable () -> Unit = {
        SnackbarHost(hostState = snackbarHostState) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(14.dp),
                containerColor = cs.inverseSurface,
                contentColor = cs.inverseOnSurface,
                actionColor = cs.inversePrimary
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 700.dp

        if (isWide) {
            Scaffold(
                containerColor = cs.background,
                snackbarHost = styledSnackbarHost,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = cs.surfaceContainer,
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        Spacer(Modifier.weight(1f))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MainTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                ClassicM3NavBarItem(
                                    tab = tab,
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            currentTab = tab
                                        }
                                    },
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(56.dp)
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        TabContentHost(
                            currentTab = currentTab,
                            state = state,
                            callbacks = callbacks,
                            onOpenSetupWizard = { showWizardSheet = true }
                        )
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = cs.background,
                snackbarHost = styledSnackbarHost,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    ClassicM3NavigationBar(
                        currentTab = currentTab,
                        onSelectTab = { currentTab = it }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding()
                        )
                        .statusBarsPadding()
                ) {
                    TabContentHost(
                        currentTab = currentTab,
                        state = state,
                        callbacks = callbacks,
                        onOpenSetupWizard = { showWizardSheet = true }
                    )
                }
            }
        }
    }

    if (showWizardSheet) {
        SetupWizardBottomSheet(
            state = state,
            callbacks = callbacks,
            onDismiss = { showWizardSheet = false }
        )
    }
}

@Composable
private fun ClassicM3NavigationBar(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    Surface(
        color = cs.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(62.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == currentTab
                ClassicM3NavBarItem(
                    tab = tab,
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTab(tab)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ClassicM3NavBarItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    val indicatorProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            easing = NavPillExpandEasing
        ),
        label = "navIndicatorProgress_${tab.name}"
    )

    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "navPressScale_${tab.name}"
    )

    val selectBounce = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !wasSelected) {
            selectBounce.snapTo(0.92f)
            selectBounce.animateTo(
                targetValue = 1.06f,
                animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
            )
            selectBounce.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.56f,
                    stiffness = 460f
                )
            )
        } else if (!selected) {
            selectBounce.snapTo(1f)
        }
        wasSelected = selected
    }

    val iconColor by animateColorAsState(
        targetValue = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        animationSpec = tween(durationMillis = 240),
        label = "navIconColor_${tab.name}"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) cs.onSurface else cs.onSurfaceVariant,
        animationSpec = tween(durationMillis = 240),
        label = "navLabelColor_${tab.name}"
    )
    val indicatorColor = cs.secondaryContainer

    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(30.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val p = indicatorProgress
                        if (p > 0.005f) {
                            val pillW = size.width * (0.20f + 0.80f * p)
                            val pillH = size.height
                            val left = (size.width - pillW) * 0.5f
                            val cornerR = pillH * 0.5f
                            val pillAlpha = (p * 1.8f).coerceIn(0f, 1f)
                            drawRoundRect(
                                color = indicatorColor.copy(alpha = indicatorColor.alpha * pillAlpha),
                                topLeft = Offset(left, 0f),
                                size = Size(pillW, pillH),
                                cornerRadius = CornerRadius(cornerR, cornerR)
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        val s = pressScale * selectBounce.value
                        scaleX = s
                        scaleY = s
                    }
                ) {
                    CompositionLocalProvider(LocalContentColor provides iconColor) {
                        AnimatedTabIcon(tab = tab, selected = selected, sizeDp = 21)
                    }
                }
            }
            Text(
                text = tab.title,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TabContentHost(
    currentTab: MainTab,
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onOpenSetupWizard: () -> Unit
) {
    Crossfade(
        targetState = currentTab,
        animationSpec = tween(durationMillis = 180),
        label = "mainTabCrossfade",
        modifier = Modifier.fillMaxSize()
    ) { tab ->
        when (tab) {
            MainTab.HOME -> HomeTab(
                state = state,
                callbacks = callbacks,
                onOpenSetupWizard = onOpenSetupWizard
            )
            MainTab.PRESETS -> PresetsTab(
                state = state,
                callbacks = callbacks
            )
            MainTab.TUNING -> TuningTab(
                state = state,
                callbacks = callbacks
            )
            MainTab.LAB -> LabTab(
                state = state,
                callbacks = callbacks
            )
        }
    }
}

