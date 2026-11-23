package com.zerointerrupt.clicker.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.ExpressiveSlider
import com.zerointerrupt.clicker.ui.components.OverflowMenuAction
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TuningTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val isCustomTier = state.tuningTier == ClickGlobalSettings.TIER_CUSTOM

    val cycleMs = (state.intervalMs + state.pressDurationMs).coerceAtLeast(1L).toDouble()
    val cpsValue = 1000.0 / cycleMs
    val cpsLabel = if (cpsValue >= 10.0) {
        "${cpsValue.roundToInt()} CPS"
    } else {
        String.format("%.1f CPS", cpsValue)
    }

    val tierOptions = listOf(
        ExpressiveOption(ClickGlobalSettings.TIER_CASUAL, "休闲 150"),
        ExpressiveOption(ClickGlobalSettings.TIER_STANDARD, "标准 60"),
        ExpressiveOption(ClickGlobalSettings.TIER_FAST, "疾速 20"),
        ExpressiveOption(ClickGlobalSettings.TIER_CUSTOM, "自定义")
    )

    val menuActions = listOf(
        OverflowMenuAction(
            label = "应用到全局",
            onClick = callbacks.onApplyDefaultsToGlobal
        ),
        OverflowMenuAction(
            label = "恢复默认参数",
            onClick = callbacks.onResetTuningDefaults
        )
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTwoColumn = maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PageTopHeader(
                title = "调校",
                menuActions = menuActions
            )

            ExpressiveConnectedGroup(
                options = tierOptions,
                selectedValue = state.tuningTier,
                onSelect = callbacks.onSelectTuningTier,
                height = 44.dp
            )

            if (isTwoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ClickTuningCard(
                        state = state,
                        callbacks = callbacks,
                        isCustomTier = isCustomTier,
                        cpsLabel = cpsLabel,
                        onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        modifier = Modifier.weight(1f)
                    )
                    InteractionTuningCard(
                        state = state,
                        callbacks = callbacks,
                        onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ClickTuningCard(
                    state = state,
                    callbacks = callbacks,
                    isCustomTier = isCustomTier,
                    cpsLabel = cpsLabel,
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                )
                InteractionTuningCard(
                    state = state,
                    callbacks = callbacks,
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                )
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ClickTuningCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    isCustomTier: Boolean,
    cpsLabel: String,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "连点设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = CircleShape,
                    color = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer
                ) {
                    Text(
                        text = "≈ $cpsLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                    )
                }
            }

            SliderBlock(
                label = "点击间隔",
                valueBadge = "${state.intervalMs} ms",
                value = state.intervalMs.coerceIn(5L, 500L).toFloat(),
                valueRange = 5f..500f,
                enabled = isCustomTier,
                onValueChange = { raw ->
                    val next = raw.roundToLong().coerceIn(5L, 500L)
                    if (next != state.intervalMs) {
                        onHaptic()
                        callbacks.onIntervalChange(next, false)
                    }
                },
                onValueChangeFinished = {
                    callbacks.onIntervalChange(state.intervalMs, true)
                }
            )

            SliderBlock(
                label = "按压时长",
                valueBadge = "${state.pressDurationMs} ms",
                value = state.pressDurationMs.coerceIn(8L, 300L).toFloat(),
                valueRange = 8f..300f,
                enabled = isCustomTier,
                onValueChange = { raw ->
                    val next = raw.roundToLong().coerceIn(8L, 300L)
                    if (next != state.pressDurationMs) {
                        onHaptic()
                        callbacks.onPressChange(next, false)
                    }
                },
                onValueChangeFinished = {
                    callbacks.onPressChange(state.pressDurationMs, true)
                }
            )

            SliderBlock(
                label = "高斯微抖动半径",
                valueBadge = "${state.jitterRadiusPx.roundToInt()} px",
                value = state.jitterRadiusPx.coerceIn(0f, 16f),
                valueRange = 0f..16f,
                enabled = true,
                onValueChange = { raw ->
                    val next = raw.roundToInt().toFloat().coerceIn(0f, 16f)
                    if (next != state.jitterRadiusPx) {
                        onHaptic()
                        callbacks.onJitterRadiusChange(next, false)
                    }
                },
                onValueChangeFinished = {
                    callbacks.onJitterRadiusChange(state.jitterRadiusPx, true)
                }
            )
        }
    }
}

@Composable
private fun InteractionTuningCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "交互设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            SliderBlock(
                label = "锁定不透明度",
                valueBadge = "${(state.lockedAnchorAlpha * 100f).roundToInt()}%",
                value = state.lockedAnchorAlpha.coerceIn(
                    ClickGlobalSettings.MIN_LOCKED_ALPHA,
                    ClickGlobalSettings.MAX_LOCKED_ALPHA
                ),
                valueRange = ClickGlobalSettings.MIN_LOCKED_ALPHA..
                    ClickGlobalSettings.MAX_LOCKED_ALPHA,
                onValueChange = { raw ->
                    val stepPct = (raw * 100f).roundToInt().coerceIn(35, 75)
                    val curPct = (state.lockedAnchorAlpha * 100f).roundToInt()
                    if (stepPct != curPct) {
                        onHaptic()
                        callbacks.onLockedAlphaChange(stepPct / 100f, false)
                    }
                },
                onValueChangeFinished = {
                    callbacks.onLockedAlphaChange(state.lockedAnchorAlpha, true)
                }
            )

            SettingSwitchRow(
                title = "启动后锁定锚点",
                checked = state.autoLockOnStart,
                onCheckedChange = callbacks.onAutoLockChange
            )

            SettingSwitchRow(
                title = "启动后贴边收起",
                checked = state.autoCollapseBar,
                onCheckedChange = callbacks.onAutoCollapseChange
            )

            SettingSwitchRow(
                title = "全局锚点与轨迹显示",
                checked = state.showMacroTrajectories,
                onCheckedChange = callbacks.onShowMacroTrajectoriesChange
            )
            Text(
                text = "全局控制所有模式下的悬浮锚点与轨迹显示；关闭后将隐藏全部点位与轨迹（不影响运行）",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SliderBlock(
    label: String,
    valueBadge: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f)
            )
            Surface(
                shape = CircleShape,
                color = if (enabled) cs.surfaceContainerHigh else cs.onSurface.copy(alpha = 0.08f)
            ) {
                Text(
                    text = valueBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        ExpressiveSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            floatingLabel = valueBadge,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        CheckSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
