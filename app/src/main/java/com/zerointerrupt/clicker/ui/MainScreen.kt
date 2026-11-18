package com.zerointerrupt.clicker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.ui.components.SetupWizardBottomSheet
import com.zerointerrupt.clicker.ui.tabs.HomeTab
import com.zerointerrupt.clicker.ui.tabs.LabTab
import com.zerointerrupt.clicker.ui.tabs.PresetsTab
import com.zerointerrupt.clicker.ui.tabs.TuningTab

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 700.dp

        if (isWide) {
            Scaffold(
                containerColor = cs.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        MainTab.entries.forEachIndexed { index, tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentTab = tab
                                    }
                                },
                                icon = { TabIcon(tab = tab) },
                                label = {
                                    Text(
                                        text = tab.title,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = cs.secondaryContainer,
                                    selectedIconColor = cs.onSecondaryContainer,
                                    selectedTextColor = cs.onSurface,
                                    unselectedIconColor = cs.onSurfaceVariant,
                                    unselectedTextColor = cs.onSurfaceVariant
                                )
                            )
                            Spacer(Modifier.weight(1f))
                        }
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
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(tab)
                            }
                        },
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
                                .background(if (selected) cs.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
                            ) {
                                TabIcon(tab = tab, sizeDp = 21)
                            }
                        }
                        Text(
                            text = tab.title,
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
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
    AnimatedContent(
        targetState = currentTab,
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            val offsetSpec = spring<IntOffset>(
                dampingRatio = 0.92f,
                stiffness = Spring.StiffnessMediumLow
            )
            val fadeSpec = tween<Float>(durationMillis = 150)
            (slideInHorizontally(animationSpec = offsetSpec) { w ->
                if (forward) w / 5 else -w / 5
            } + fadeIn(animationSpec = fadeSpec)) togetherWith
                (slideOutHorizontally(animationSpec = offsetSpec) { w ->
                    if (forward) -w / 5 else w / 5
                } + fadeOut(animationSpec = fadeSpec))
        },
        label = "mainTabTransition",
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

@Composable
private fun TabIcon(
    tab: MainTab,
    sizeDp: Int = 22
) {
    when (tab) {
        MainTab.HOME -> Icon(
            imageVector = Icons.Rounded.Home,
            contentDescription = tab.title,
            modifier = Modifier.size(sizeDp.dp)
        )
        MainTab.PRESETS -> Icon(
            painter = painterResource(R.drawable.ic_layers),
            contentDescription = tab.title,
            modifier = Modifier.size(sizeDp.dp)
        )
        MainTab.TUNING -> Icon(
            painter = painterResource(R.drawable.ic_tune),
            contentDescription = tab.title,
            modifier = Modifier.size(sizeDp.dp)
        )
        MainTab.LAB -> Icon(
            painter = painterResource(R.drawable.ic_lab),
            contentDescription = tab.title,
            modifier = Modifier.size(sizeDp.dp)
        )
    }
}
