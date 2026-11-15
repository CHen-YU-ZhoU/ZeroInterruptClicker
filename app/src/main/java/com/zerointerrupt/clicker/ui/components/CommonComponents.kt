package com.zerointerrupt.clicker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CheckSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = null
    )
}

@Composable
fun PresetMiniCanvas(
    preset: ClickPreset,
    screenWidthPx: Int,
    screenHeightPx: Int,
    modifier: Modifier = Modifier
) {
    val bezelColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val tapColor = MaterialTheme.colorScheme.primary
    val swipeColor = MaterialTheme.colorScheme.tertiary
    val holdColor = MaterialTheme.colorScheme.secondary
    val disabledColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    val aspect = (screenWidthPx.toFloat() / screenHeightPx.coerceAtLeast(1).toFloat())
        .coerceIn(0.42f, 0.72f)

    Canvas(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(14.dp))
            .background(bezelColor)
            .border(1.dp, outlineColor, RoundedCornerShape(14.dp))
    ) {
        val w = size.width
        val h = size.height
        val maxDispW = (screenWidthPx - 1).coerceAtLeast(1).toFloat()
        val maxDispH = (screenHeightPx - 1).coerceAtLeast(1).toFloat()

        // Subtle grid guidelines
        drawLine(
            color = outlineColor.copy(alpha = 0.25f),
            start = Offset(w * 0.5f, 0f),
            end = Offset(w * 0.5f, h),
            strokeWidth = 1f
        )
        drawLine(
            color = outlineColor.copy(alpha = 0.25f),
            start = Offset(0f, h * 0.5f),
            end = Offset(w, h * 0.5f),
            strokeWidth = 1f
        )

        // Draw rounded screen safe frame
        drawRoundRect(
            color = outlineColor.copy(alpha = 0.35f),
            topLeft = Offset(4f, 4f),
            size = androidx.compose.ui.geometry.Size(w - 8f, h - 8f),
            cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
            style = Stroke(width = 1f)
        )

        preset.targets.forEach { t ->
            val u = if (t.normX in 0f..1f) t.normX else (t.x / maxDispW).coerceIn(0f, 1f)
            val v = if (t.normY in 0f..1f) t.normY else (t.y / maxDispH).coerceIn(0f, 1f)
            val sx = u * w
            val sy = v * h
            val color = when {
                !t.enabled -> disabledColor
                t.actionType == ActionType.SWIPE -> swipeColor
                t.actionType == ActionType.HOLD -> holdColor
                else -> tapColor
            }

            if (t.actionType == ActionType.SWIPE) {
                val eu = if (t.endNormX in 0f..1f) t.endNormX else (t.endX / maxDispW).coerceIn(0f, 1f)
                val ev = if (t.endNormY in 0f..1f) t.endNormY else (t.endY / maxDispH).coerceIn(0f, 1f)
                val ex = eu * w
                val ey = ev * h

                drawLine(
                    color = color.copy(alpha = 0.85f),
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                )

                val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                val len = 6.dp.toPx()
                val spread = Math.toRadians(28.0)
                val arrow = Path().apply {
                    moveTo(ex, ey)
                    lineTo(
                        ex - (len * cos(angle - spread)).toFloat(),
                        ey - (len * sin(angle - spread)).toFloat()
                    )
                    lineTo(
                        ex - (len * cos(angle + spread)).toFloat(),
                        ey - (len * sin(angle + spread)).toFloat()
                    )
                    close()
                }
                drawPath(arrow, color)
                drawCircle(color = color.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = Offset(ex, ey))
            }

            if (t.actionType == ActionType.HOLD) {
                drawCircle(
                    color = color,
                    radius = 7.dp.toPx(),
                    center = Offset(sx, sy),
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }

            drawCircle(color = color.copy(alpha = 0.24f), radius = 5.5.dp.toPx(), center = Offset(sx, sy))
            drawCircle(color = color, radius = 3.2.dp.toPx(), center = Offset(sx, sy))
        }
    }
}

@Composable
fun SlotMatrixStrip(
    slots: List<Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "16-Slot 硬件槽位实时矩阵",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlotLegendDot("物理", MaterialTheme.colorScheme.primary)
                SlotLegendDot("虚拟", MaterialTheme.colorScheme.tertiary)
                SlotLegendDot("空闲", MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0 until 8, 8 until 16).forEach { range ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (idx in range) {
                        val st = slots.getOrElse(idx) { 0 }
                        val targetBg = when (st) {
                            1 -> MaterialTheme.colorScheme.primaryContainer
                            2 -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        val targetFg = when (st) {
                            1 -> MaterialTheme.colorScheme.onPrimaryContainer
                            2 -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                        val bg by animateColorAsState(targetBg, label = "slot_$idx")
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = bg
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "$idx",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (st != 0) FontWeight.Bold else FontWeight.Normal,
                                    color = targetFg
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotLegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardBottomSheet(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "初始化向导",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            WizardStepCard(
                step = "1",
                title = "悬浮窗权限",
                subtitle = if (state.overlayPermGranted) "已授权" else "未授权",
                done = state.overlayPermGranted,
                actionLabel = if (state.overlayPermGranted) "已完成" else "去开启",
                onAction = callbacks.onGrantOverlay
            )

            if (state.showNotifRow) {
                WizardStepCard(
                    step = "2",
                    title = "通知权限",
                    subtitle = if (state.notifPermGranted) "已授权" else "未授权",
                    done = state.notifPermGranted,
                    actionLabel = if (state.notifPermGranted) "已完成" else "授予",
                    onAction = callbacks.onGrantNotif
                )
            }

            val shizukuConnected = state.connectionState == ShizukuConnectionState.CONNECTED
            val shizukuBtn = when (state.connectionState) {
                ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> "打开 Shizuku"
                ShizukuConnectionState.NO_PERMISSION -> "授权连接"
                ShizukuConnectionState.BINDING -> "连接中..."
                ShizukuConnectionState.CONNECTED -> "已连接"
                ShizukuConnectionState.ERROR -> "重试"
            }
            WizardStepCard(
                step = if (state.showNotifRow) "3" else "2",
                title = "Shizuku 通道",
                subtitle = if (shizukuConnected) "已连接" else "未连接",
                done = shizukuConnected,
                actionLabel = shizukuBtn,
                onAction = callbacks.onGrantShizuku
            )

            // Step 4: 5-second touch calibration (when hardware touch node readable)
            val touchReadable = state.engineSnapshot.touchDeviceReadable &&
                state.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK
            if (shizukuConnected && touchReadable) {
                val stepNum = if (state.showNotifRow) "4" else "3"
                val testing = state.selfCheckRemainingMs > 0L
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.selfCheckPassed) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.selfCheckPassed) "✓" else stepNum,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.selfCheckPassed) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "5 秒触控校准",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when {
                                        testing -> "滑动屏幕测试并点击确认"
                                        state.selfCheckPassed -> "已通过"
                                        else -> "待校准"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (testing) {
                            val sec = (state.selfCheckRemainingMs + 999L) / 1000L
                            val smoothProgress = remember { Animatable(1f) }
                            LaunchedEffect(testing) {
                                smoothProgress.snapTo(1f)
                                smoothProgress.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = state.selfCheckTimeoutMs.coerceAtLeast(1000L).toInt(),
                                        easing = LinearEasing
                                    )
                                )
                            }
                            M3WavyLinearProgressIndicator(
                                progress = { smoothProgress.value },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = callbacks.onRollbackSelfCheck,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("立即回滚")
                                }
                                Button(
                                    onClick = callbacks.onConfirmSelfCheck,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("触控正常 (${sec}s)")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (state.selfCheckPassed) {
                                    OutlinedButton(
                                        onClick = callbacks.onStartSelfCheck,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("重新校准")
                                    }
                                    Button(
                                        onClick = {
                                            onDismiss()
                                            callbacks.onToggleOverlay()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (state.overlayVisible) "关闭悬浮窗" else "立即开启悬浮窗")
                                    }
                                } else {
                                    Button(
                                        onClick = callbacks.onStartSelfCheck,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("开始 5 秒触控校准")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepCard(
    step: String,
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (done) "✓" else step,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (done) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (done) {
                FilledTonalButton(
                    onClick = onAction,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Text(actionLabel)
                }
            } else {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun PresetNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    showCopySwitch: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, copyCurrent: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var copyCurrent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    singleLine = true,
                    label = { Text("方案名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showCopySwitch) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { copyCurrent = !copyCurrent }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("复制当前方案的手势布局", style = MaterialTheme.typography.bodyMedium)
                        CheckSwitch(checked = copyCurrent, onCheckedChange = { copyCurrent = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifEmpty { initialName }, copyCurrent) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ImportJsonDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 JSON 方案", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "粘贴好友分享的方案 JSON 文本：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(jsonText) },
                enabled = jsonText.isNotBlank()
            ) { Text("导入并激活") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetEditDialog(
    initial: ClickTarget?,
    screenW: Int,
    screenH: Int,
    defaultIntervalMs: Long,
    defaultPressMs: Long,
    defaultJitter: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        x: Float,
        y: Float,
        intervalMs: Long,
        pressMs: Long,
        jitter: Boolean,
        enabled: Boolean,
        actionType: ActionType,
        endX: Float,
        endY: Float,
        swipeDurationMs: Long,
        repeatCount: Int
    ) -> Unit
) {
    var actionType by remember { mutableStateOf(initial?.actionType ?: ActionType.TAP) }
    var xText by remember { mutableStateOf((initial?.x ?: (screenW * 0.5f)).roundToInt().toString()) }
    var yText by remember { mutableStateOf((initial?.y ?: (screenH * 0.5f)).roundToInt().toString()) }
    var endXText by remember {
        val ex = initial?.endX ?: (screenW * 0.72f)
        mutableStateOf(ex.roundToInt().toString())
    }
    var endYText by remember {
        val ey = initial?.endY ?: (screenH * 0.5f)
        mutableStateOf(ey.roundToInt().toString())
    }
    var intervalText by remember { mutableStateOf((initial?.intervalMs ?: defaultIntervalMs).toString()) }
    var pressText by remember { mutableStateOf((initial?.pressDurationMs ?: defaultPressMs).toString()) }
    var swipeDurText by remember { mutableStateOf((initial?.swipeDurationMs ?: ClickTarget.DEFAULT_SWIPE_DURATION_MS).toString()) }
    var repeatText by remember { mutableStateOf((initial?.repeatCount ?: ClickTarget.DEFAULT_REPEAT_COUNT).toString()) }
    var jitter by remember { mutableStateOf(initial?.microJitter ?: defaultJitter) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial != null) "编辑手势 #${initial.index}" else "新增手势节点",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExpressiveConnectedGroup(
                    options = ActionType.entries.map { act ->
                        ExpressiveOption(
                            value = act,
                            label = act.label,
                            iconRes = when (act) {
                                ActionType.TAP -> R.drawable.ic_touch_app
                                ActionType.SWIPE -> R.drawable.ic_swipe
                                ActionType.HOLD -> R.drawable.ic_hold
                            }
                        )
                    },
                    selectedValue = actionType,
                    onSelect = { actionType = it },
                    height = 42.dp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = xText,
                        onValueChange = { xText = it.filter(Char::isDigit).take(5) },
                        label = { Text(if (actionType == ActionType.SWIPE) "起点 X" else "X 坐标") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = yText,
                        onValueChange = { yText = it.filter(Char::isDigit).take(5) },
                        label = { Text(if (actionType == ActionType.SWIPE) "起点 Y" else "Y 坐标") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                when (actionType) {
                    ActionType.SWIPE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = endXText,
                                onValueChange = { endXText = it.filter(Char::isDigit).take(5) },
                                label = { Text("终点 X") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = endYText,
                                onValueChange = { endYText = it.filter(Char::isDigit).take(5) },
                                label = { Text("终点 Y") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = swipeDurText,
                                onValueChange = { swipeDurText = it.filter(Char::isDigit).take(5) },
                                label = { Text("滑动时长(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { intervalText = it.filter(Char::isDigit).take(5) },
                                label = { Text("滑动间隔(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    ActionType.HOLD -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pressText,
                                onValueChange = { pressText = it.filter(Char::isDigit).take(5) },
                                label = { Text("长按时长(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { intervalText = it.filter(Char::isDigit).take(5) },
                                label = { Text("循环间隔(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    ActionType.TAP -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "间隔 ${defaultIntervalMs}ms · 按压 ${defaultPressMs}ms（由「调校 → 连点设置」统一控制）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = repeatText,
                    onValueChange = { repeatText = it.filter(Char::isDigit).take(4) },
                    label = { Text("循环次数 (1~9999)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开启拟人高斯抖动", style = MaterialTheme.typography.bodyMedium)
                    CheckSwitch(checked = jitter, onCheckedChange = { jitter = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用该节点", style = MaterialTheme.typography.bodyMedium)
                    CheckSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val x = (xText.toFloatOrNull() ?: (screenW * 0.5f))
                        .coerceIn(0f, (screenW - 1).coerceAtLeast(1).toFloat())
                    val y = (yText.toFloatOrNull() ?: (screenH * 0.5f))
                        .coerceIn(0f, (screenH - 1).coerceAtLeast(1).toFloat())
                    val ex = (endXText.toFloatOrNull() ?: (screenW * 0.7f))
                        .coerceIn(0f, (screenW - 1).coerceAtLeast(1).toFloat())
                    val ey = (endYText.toFloatOrNull() ?: y)
                        .coerceIn(0f, (screenH - 1).coerceAtLeast(1).toFloat())
                    val interval = if (actionType == ActionType.TAP) {
                        defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                    } else {
                        (intervalText.toLongOrNull() ?: defaultIntervalMs)
                            .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                    }
                    val press = if (actionType == ActionType.TAP) {
                        defaultPressMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    } else {
                        (pressText.toLongOrNull() ?: defaultPressMs)
                            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    }
                    val swipeDur = (swipeDurText.toLongOrNull() ?: ClickTarget.DEFAULT_SWIPE_DURATION_MS)
                        .coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS)
                    val repeatCount = (repeatText.toIntOrNull() ?: ClickTarget.DEFAULT_REPEAT_COUNT)
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    onSave(x, y, interval, press, jitter, enabled, actionType, ex, ey, swipeDur, repeatCount)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
