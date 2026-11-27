package com.zerointerrupt.clicker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.model.RawMacroCodec
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

/**
 * Degree-7 (G3-continuous: curvature = 0 and curvature derivative = 0 at straight-edge joins)
 * high-order Bezier corner shape with symmetric evenly-spaced edge control points (1, 0.75, 0.5, 0.25, 0).
 */
class SmoothRoundedCornerShape(
    private val cornerRadius: Dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val rPx = with(density) { cornerRadius.toPx() }.coerceAtLeast(0f)
        val r = minOf(rPx, w * 0.46f, h * 0.46f)
        val path = Path()
        if (r <= 0.5f || w <= 1f || h <= 1f) {
            path.moveTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            return Outline.Generic(path)
        }

        // L = 1.50 * r preserves ~0.299r diagonal clearance (matching circle's 0.293r) while extending tangents to G3 continuity.
        val d = minOf(r * 1.50f, w * 0.495f, h * 0.495f)

        // Start after top-left corner on top edge -> trace clockwise
        path.moveTo(d, 0f)
        path.lineTo(w - d, 0f)

        // Top-right corner: (w - u*d, v*d) as t goes 0..1 from top edge to right edge
        for (i in 1..STEPS) {
            path.lineTo(w - UNIT_X[i] * d, UNIT_Y[i] * d)
        }

        path.lineTo(w, h - d)
        // Bottom-right corner: (w - v*d, h - u*d) from right edge to bottom edge
        for (i in 1..STEPS) {
            path.lineTo(w - UNIT_Y[i] * d, h - UNIT_X[i] * d)
        }

        path.lineTo(d, h)
        // Bottom-left corner: (u*d, h - v*d) from bottom edge to left edge
        for (i in 1..STEPS) {
            path.lineTo(UNIT_X[i] * d, h - UNIT_Y[i] * d)
        }

        path.lineTo(0f, d)
        // Top-left corner: (v*d, u*d) from left edge to top edge
        for (i in 1..STEPS) {
            path.lineTo(UNIT_Y[i] * d, UNIT_X[i] * d)
        }

        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is SmoothRoundedCornerShape && cornerRadius == other.cornerRadius
    }

    override fun hashCode(): Int = cornerRadius.hashCode()

    private companion object {
        const val STEPS = 28

        // Control weights of symmetric degree-7 Bezier from (1, 0) to (0, 1):
        // P0=(1,0), P1=(0.75,0), P2=(0.5,0), P3=(0.25,0), P4=(0,0.25), P5=(0,0.5), P6=(0,0.75), P7=(0,1)
        val CTRL_X = doubleArrayOf(1.0, 0.75, 0.50, 0.25, 0.0, 0.0, 0.0, 0.0)
        val CTRL_Y = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.25, 0.50, 0.75, 1.0)
        val BINOM = doubleArrayOf(1.0, 7.0, 21.0, 35.0, 35.0, 21.0, 7.0, 1.0)

        val UNIT_X = FloatArray(STEPS + 1)
        val UNIT_Y = FloatArray(STEPS + 1)

        init {
            for (step in 0..STEPS) {
                val t = step.toDouble() / STEPS
                val omt = 1.0 - t
                var sx = 0.0
                var sy = 0.0
                for (i in 0..7) {
                    val b = BINOM[i] * Math.pow(t, i.toDouble()) * Math.pow(omt, (7 - i).toDouble())
                    sx += b * CTRL_X[i]
                    sy += b * CTRL_Y[i]
                }
                UNIT_X[step] = sx.toFloat()
                UNIT_Y[step] = sy.toFloat()
            }
        }
    }
}

@Composable
fun PresetMiniCanvas(
    preset: ClickPreset,
    allPresets: List<ClickPreset> = listOf(preset),
    screenWidthPx: Int,
    screenHeightPx: Int,
    modifier: Modifier = Modifier,
    drawOuterFrame: Boolean = true
) {
    val bezelColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val tapColor = MaterialTheme.colorScheme.primary
    val swipeColor = MaterialTheme.colorScheme.tertiary
    val holdColor = MaterialTheme.colorScheme.secondary
    val disabledColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    val aspect = (screenWidthPx.toFloat() / screenHeightPx.coerceAtLeast(1).toFloat())
        .coerceIn(0.42f, 0.72f)

    val canvasModifier = if (drawOuterFrame) {
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(14.dp))
            .background(bezelColor)
            .border(1.dp, outlineColor, RoundedCornerShape(14.dp))
    } else {
        modifier
    }

    Canvas(modifier = canvasModifier) {
        val w = size.width
        val h = size.height
        val maxDispW = (screenWidthPx - 1).coerceAtLeast(1).toFloat()
        val maxDispH = (screenHeightPx - 1).coerceAtLeast(1).toFloat()

        if (drawOuterFrame) {
            drawRoundRect(
                color = outlineColor.copy(alpha = 0.35f),
                topLeft = Offset(4f, 4f),
                size = Size(w - 8f, h - 8f),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(width = 1f)
            )
        }

        if (preset.isRawMacro && preset.rawMacroJson.isNotBlank()) {
            val polylines = RawMacroCodec.extractNormalizedPolylines(preset.rawMacroJson)
            val strokeColor = swipeColor.copy(alpha = 0.88f)
            val strokePx = 2.dp.toPx()
            polylines.forEach { pts ->
                if (pts.isEmpty()) return@forEach
                val first = pts.first()
                val fx = first.first * w
                val fy = first.second * h
                if (pts.size == 1) {
                    drawCircle(color = strokeColor.copy(alpha = 0.24f), radius = 5.5.dp.toPx(), center = Offset(fx, fy))
                    drawCircle(color = strokeColor, radius = 3.2.dp.toPx(), center = Offset(fx, fy))
                } else {
                    val strokePath = Path().apply {
                        moveTo(fx, fy)
                        for (i in 1 until pts.size) {
                            lineTo(pts[i].first * w, pts[i].second * h)
                        }
                    }
                    drawPath(
                        path = strokePath,
                        color = strokeColor,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    val last = pts.last()
                    drawCircle(color = strokeColor.copy(alpha = 0.25f), radius = 4.5.dp.toPx(), center = Offset(fx, fy))
                    drawCircle(color = strokeColor, radius = 2.6.dp.toPx(), center = Offset(fx, fy))
                    drawCircle(
                        color = strokeColor,
                        radius = 2.4.dp.toPx(),
                        center = Offset(last.first * w, last.second * h),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                }
            }
            return@Canvas
        }

        val previewTargets = ClickConfigStore.collectPreviewTargets(allPresets, preset)
        val densePreview = previewTargets.size > 10
        previewTargets.forEachIndexed { idx, t ->
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

                if (densePreview) {
                    drawLine(
                        color = color.copy(alpha = 0.85f),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                } else {
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
                    drawCircle(color = color.copy(alpha = 0.25f), radius = 5.5.dp.toPx(), center = Offset(ex, ey))
                }
            }

            val isDenseMidKnot = densePreview && t.actionType == ActionType.SWIPE && idx > 0 &&
                (maxOf(0, idx - 8) until idx).any { k ->
                    val prev = previewTargets[k]
                    prev.actionType == ActionType.SWIPE &&
                        prev.intervalMs <= ClickTarget.SEAMLESS_HOLD_GAP_MS &&
                        kotlin.math.hypot((t.x - prev.endX).toDouble(), (t.y - prev.endY).toDouble()) <= 24.0
                }
            if (!isDenseMidKnot) {
                if (t.actionType == ActionType.HOLD && !densePreview) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetupWizardBottomSheet(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerScope = rememberCoroutineScope()
    val initialPage = remember {
        if (state.authMode == ClickGlobalSettings.AUTH_MODE_ROOT) 1 else 0
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2 })

    val isShizukuActive = state.authMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU
    val isRootActive = state.authMode == ClickGlobalSettings.AUTH_MODE_ROOT
    val shizukuConnected = isShizukuActive && state.connectionState == ShizukuConnectionState.CONNECTED
    val rootConnected = isRootActive && state.connectionState == ShizukuConnectionState.CONNECTED

    val modeTabs = remember {
        listOf(
            ExpressiveOption(0, "Shizuku 模式", R.drawable.ic_terminal),
            ExpressiveOption(1, "Root 模式", R.drawable.ic_shield_check)
        )
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "初始化向导",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val readyLabel = when {
                    !state.needsSetupWizard && isRootActive -> "已就绪 · Root"
                    !state.needsSetupWizard -> "已就绪 · Shizuku"
                    else -> "任选一页完成"
                }
                val badgeBg = if (!state.needsSetupWizard) cs.primaryContainer else cs.secondaryContainer
                val badgeFg = if (!state.needsSetupWizard) cs.onPrimaryContainer else cs.onSecondaryContainer
                Surface(
                    shape = CircleShape,
                    color = badgeBg
                ) {
                    Text(
                        text = readyLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                    )
                }
            }

            val calibrating = state.selfCheckRemainingMs > 0L
            ExpressiveConnectedGroup(
                options = modeTabs,
                selectedValue = pagerState.currentPage,
                onSelect = { page ->
                    if (!calibrating) {
                        pagerScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                },
                height = 44.dp
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !calibrating,
                pageSpacing = 14.dp,
                verticalAlignment = Alignment.Top,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) { page ->
                if (page == 0) {
                    ShizukuWizardPage(
                        state = state,
                        callbacks = callbacks,
                        shizukuConnected = shizukuConnected,
                        rootCurrentlyActive = rootConnected,
                        onDismiss = onDismiss
                    )
                } else {
                    RootWizardPage(
                        state = state,
                        callbacks = callbacks,
                        rootConnected = rootConnected,
                        shizukuCurrentlyActive = shizukuConnected,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun ShizukuWizardPage(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    shizukuConnected: Boolean,
    rootCurrentlyActive: Boolean,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rootCurrentlyActive) {
            WizardModeSwitchHint(
                text = "当前已通过 Root 通道运行；如需改用 Shizuku，点击下方「授权连接」即可切换"
            )
        }

        var stepNum = 1
        WizardStepCard(
            step = (stepNum++).toString(),
            title = "悬浮窗权限",
            subtitle = if (state.overlayPermGranted) "已授权" else "未授权",
            done = state.overlayPermGranted,
            actionLabel = if (state.overlayPermGranted) "已完成" else "去开启",
            onAction = callbacks.onGrantOverlay
        )

        if (state.showNotifRow) {
            WizardStepCard(
                step = (stepNum++).toString(),
                title = "通知权限（建议）",
                subtitle = if (state.notifPermGranted) "已授权" else "用于前台保活显示",
                done = state.notifPermGranted,
                actionLabel = if (state.notifPermGranted) "已完成" else "授予",
                onAction = callbacks.onGrantNotif
            )
        }

        WizardStepCard(
            step = (stepNum++).toString(),
            title = "省电策略（建议）",
            subtitle = if (state.batteryOptIgnored) "已忽略电池优化" else "建议设为无限制，防止后台断连",
            done = state.batteryOptIgnored,
            actionLabel = if (state.batteryOptIgnored) "已完成" else "去设置",
            onAction = callbacks.onGrantBatteryOpt
        )

        val shizukuBtn = if (state.authMode != ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
            "切换并连接"
        } else {
            when (state.connectionState) {
                ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> "打开 Shizuku"
                ShizukuConnectionState.NO_PERMISSION -> "授权连接"
                ShizukuConnectionState.BINDING -> "连接中..."
                ShizukuConnectionState.CONNECTED -> "已连接"
                ShizukuConnectionState.ERROR -> "重试"
            }
        }
        WizardStepCard(
            step = (stepNum++).toString(),
            title = "Shizuku 通道",
            subtitle = when {
                shizukuConnected -> "已连接 (UID ${state.engineSnapshot.uid.takeIf { it >= 0 } ?: 2000})"
                state.authMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU &&
                    state.connectionState == ShizukuConnectionState.BINDING -> "正在绑定底层服务…"
                else -> "未连接"
            },
            done = shizukuConnected,
            actionLabel = shizukuBtn,
            onAction = callbacks.onGrantShizuku
        )

        val touchReadable = state.engineSnapshot.touchDeviceReadable &&
            state.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK
        TouchCalibrationStepCard(
            stepNumber = stepNum.toString(),
            channelConnected = shizukuConnected,
            touchReadable = touchReadable,
            lockedHint = "连接 Shizuku 通道后解锁触控校准",
            state = state,
            callbacks = callbacks,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun RootWizardPage(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    rootConnected: Boolean,
    shizukuCurrentlyActive: Boolean,
    onDismiss: () -> Unit
) {
    val step1Done = rootConnected && state.overlayPermGranted
    val rootSubtitle = when {
        state.rootConnecting -> "正在授权并连接…"
        rootConnected && state.overlayPermGranted -> "已连接 (UID 0)"
        rootConnected -> "已连接，待开启悬浮窗权限"
        !state.rootErrorMessage.isNullOrBlank() -> state.rootErrorMessage
        else -> "未连接"
    }
    val rootBtnLabel = when {
        state.rootConnecting -> "连接中..."
        rootConnected && state.overlayPermGranted -> "已连接"
        rootConnected -> "去开启"
        shizukuCurrentlyActive -> "切换并连接"
        else -> "授权连接"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WizardStepCard(
            step = "1",
            title = "Root 通道",
            subtitle = rootSubtitle,
            done = step1Done,
            actionLabel = rootBtnLabel,
            onAction = {
                if (!state.rootConnecting) {
                    if (rootConnected && !state.overlayPermGranted) {
                        callbacks.onGrantOverlay()
                    } else {
                        callbacks.onGrantRoot()
                    }
                }
            }
        )

        val touchReadable = state.engineSnapshot.touchDeviceReadable &&
            state.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK
        TouchCalibrationStepCard(
            stepNumber = "2",
            channelConnected = rootConnected,
            touchReadable = touchReadable,
            lockedHint = "连接 Root 通道后解锁触控校准",
            state = state,
            callbacks = callbacks,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun WizardModeSwitchHint(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onTertiaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun TouchCalibrationStepCard(
    stepNumber: String,
    channelConnected: Boolean,
    touchReadable: Boolean,
    lockedHint: String,
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val testing = state.selfCheckRemainingMs > 0L
    val calibrationNeeded = !channelConnected || touchReadable
    val stepDone = channelConnected && (!touchReadable || state.selfCheckPassed)
    val cardAlpha = if (channelConnected) 1f else 0.62f

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.alpha(cardAlpha)
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
                            if (stepDone) cs.primary else cs.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (stepDone) "✓" else stepNumber,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stepDone) cs.onPrimary else cs.onSecondaryContainer
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
                            !channelConnected -> lockedHint
                            !calibrationNeeded -> "当前后端无需硬件独占校准，可直接开启"
                            testing -> "请滑动屏幕测试触控，正常后点击确认"
                            state.selfCheckPassed -> "已通过，零断触引擎就绪"
                            else -> "验证物理触控合流并开启安全回滚保护"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            if (testing && channelConnected) {
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
                        shape = CircleShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("立即回滚")
                    }
                    Button(
                        onClick = callbacks.onConfirmSelfCheck,
                        shape = CircleShape,
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
                    if (stepDone) {
                        if (touchReadable) {
                            OutlinedButton(
                                onClick = callbacks.onStartSelfCheck,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("重新校准")
                            }
                        }
                        Button(
                            onClick = {
                                onDismiss()
                                callbacks.onToggleOverlay()
                            },
                            enabled = state.overlayPermGranted,
                            shape = CircleShape,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (state.overlayVisible) "关闭悬浮窗" else "立即开启悬浮窗")
                        }
                    } else {
                        Button(
                            onClick = callbacks.onStartSelfCheck,
                            enabled = channelConnected,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (channelConnected) "开始 5 秒触控校准" else "等待通道连接",
                                fontWeight = FontWeight.SemiBold
                            )
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
    title: String = "批量导入 JSON",
    hintText: String = "粘贴导出的批量方案 JSON 或单方案 JSON：",
    confirmLabel: String = "导入",
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = hintText,
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
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ScriptImportDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var scriptText by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("脚本新建方案", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "支持极简指令（tap/hold/swipe/wait）或直接粘贴单方案 JSON：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { scriptText = ClickConfigStore.SCRIPT_TEMPLATE_EXAMPLE },
                        label = { Text("填入脚本示例") }
                    )
                }
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it },
                    minLines = 5,
                    maxLines = 10,
                    placeholder = { Text(ClickConfigStore.SCRIPT_TEMPLATE_EXAMPLE) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(scriptText) },
                enabled = scriptText.isNotBlank()
            ) { Text("解析并创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

data class PresetTreeNode(
    val preset: ClickPreset,
    val children: List<ClickPreset>
)

fun buildPresetTree(presets: List<ClickPreset>): List<PresetTreeNode> {
    val byId = presets.associateBy { it.id }
    val assignedParent = HashMap<String, String>()

    // 1. Explicit parentPresetId (recorded macros & imported bundles)
    for (p in presets) {
        val pid = p.parentPresetId
        if (pid.isNotBlank() && pid != p.id && pid in byId) {
            assignedParent[p.id] = pid
        }
    }

    // 2. Fallback: infer parent from SUB_PRESET references when child is frozen / named "<parent>.N"
    for (parent in presets) {
        if (parent.executionMode != ExecutionMode.SEQUENTIAL) continue
        for (t in parent.targets) {
            if (t.actionType != ActionType.SUB_PRESET || t.refPresetId.isBlank()) continue
            val child = byId[t.refPresetId] ?: continue
            if (child.id == parent.id || child.id in assignedParent) continue
            if (child.isFrozen || child.name.startsWith("${parent.name}.")) {
                assignedParent[child.id] = parent.id
            }
        }
    }

    val childrenMap = LinkedHashMap<String, MutableList<ClickPreset>>()
    for (p in presets) {
        val pid = assignedParent[p.id]
        if (pid != null) {
            childrenMap.getOrPut(pid) { mutableListOf() }.add(p)
        }
    }

    val roots = presets.filter { it.id !in assignedParent }
    val emittedIds = HashSet<String>()
    val nodes = mutableListOf<PresetTreeNode>()
    for (r in roots) {
        emittedIds += r.id
        val kids = childrenMap[r.id].orEmpty().filter { it.id != r.id }
        kids.forEach { emittedIds += it.id }
        nodes += PresetTreeNode(preset = r, children = kids)
    }
    // Defensive: ensure no cyclic orphan preset is ever hidden
    for (p in presets) {
        if (p.id !in emittedIds) {
            nodes += PresetTreeNode(preset = p, children = emptyList())
        }
    }
    return nodes
}

@Composable
fun BatchDeletePresetsDialog(
    presets: List<ClickPreset>,
    activePresetId: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val treeNodes = remember(presets) { buildPresetTree(presets) }
    var selectedIds by remember(presets) { mutableStateOf(emptySet<String>()) }
    var expandedRoots by remember(presets) { mutableStateOf(emptySet<String>()) }
    val maxDeletable = (treeNodes.size - 1).coerceAtLeast(0)
    val cascadeCount = remember(presets, selectedIds) {
        ClickConfigStore.collectCascadeDeleteIds(presets, selectedIds).size
    }
    val canConfirm = selectedIds.isNotEmpty() && selectedIds.size <= maxDeletable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量删除方案", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (cascadeCount > selectedIds.size) {
                            "已选 ${selectedIds.size} 个大方案（含子方案共 $cascadeCount 个，至少保留 1 个）"
                        } else {
                            "已选 ${selectedIds.size} 个（至少保留 1 个）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val keeperId = treeNodes.firstOrNull { node ->
                                node.preset.id == activePresetId || node.children.any { it.id == activePresetId }
                            }?.preset?.id ?: treeNodes.firstOrNull()?.preset?.id
                            selectedIds = if (selectedIds.size >= maxDeletable) {
                                emptySet()
                            } else {
                                treeNodes.map { it.preset.id }.filter { it != keeperId }.toSet()
                            }
                        }
                    ) {
                        Text(if (selectedIds.size >= maxDeletable) "清空" else "全选其余")
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    treeNodes.forEach { node ->
                        val preset = node.preset
                        val checked = preset.id in selectedIds
                        val canToggleOn = checked || selectedIds.size < maxDeletable
                        val hasChildren = node.children.isNotEmpty()
                        val expanded = preset.id in expandedRoots

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (checked) cs.errorContainer.copy(alpha = 0.45f) else cs.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = canToggleOn) {
                                    selectedIds = if (checked) {
                                        selectedIds - preset.id
                                    } else {
                                        selectedIds + preset.id
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = canToggleOn,
                                    onCheckedChange = { next ->
                                        if (next && selectedIds.size < maxDeletable) {
                                            selectedIds = selectedIds + preset.id
                                        } else if (!next) {
                                            selectedIds = selectedIds - preset.id
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val activeTag = if (preset.id == activePresetId) " · 使用中" else ""
                                    val childTag = if (hasChildren) " · 含${node.children.size}子方案(一并删除)" else ""
                                    Text(
                                        text = "${preset.targets.size} 步骤 · ${preset.executionMode.label}$activeTag$childTag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (checked && hasChildren) cs.error else cs.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (hasChildren) {
                                    IconButton(
                                        onClick = {
                                            expandedRoots = if (expanded) {
                                                expandedRoots - preset.id
                                            } else {
                                                expandedRoots + preset.id
                                            }
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                                            contentDescription = if (expanded) "收起子方案" else "展开子方案",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (hasChildren && expanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                node.children.forEach { child ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (checked) cs.errorContainer.copy(alpha = 0.25f) else cs.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = child.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${child.targets.size} 步骤${if (checked) " · 随主方案删除" else ""}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (checked) cs.error else cs.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds) },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.error,
                    contentColor = cs.onError
                )
            ) {
                Text("删除 ($cascadeCount)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun BatchCreateEmptyPresetsDialog(
    nextNumber: Int,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, mode: ExecutionMode) -> Unit
) {
    var countText by remember { mutableStateOf("3") }
    var execMode by remember { mutableStateOf(ExecutionMode.CONCURRENT) }
    val parsedCount = (countText.toIntOrNull() ?: 0).coerceIn(0, 50)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量添加空方案", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 5, 10).forEach { qty ->
                        FilterChip(
                            selected = parsedCount == qty,
                            onClick = { countText = qty.toString() },
                            label = { Text("${qty}个") }
                        )
                    }
                }
                OutlinedTextField(
                    value = countText,
                    onValueChange = { raw -> countText = raw.filter { it.isDigit() }.take(2) },
                    label = { Text("新增数量 (1~50)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ExpressiveConnectedGroup(
                    options = listOf(
                        ExpressiveOption(ExecutionMode.CONCURRENT, "并发"),
                        ExpressiveOption(ExecutionMode.SEQUENTIAL, "顺序")
                    ),
                    selectedValue = execMode,
                    onSelect = { execMode = it },
                    height = 40.dp
                )
                if (parsedCount in 1..50) {
                    val startNum = nextNumber
                    val endNum = nextNumber + parsedCount - 1
                    Text(
                        text = if (parsedCount == 1) "将新增：方案 $startNum" else "将新增：方案 $startNum ~ 方案 $endNum",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsedCount.coerceIn(1, 50), execMode) },
                enabled = parsedCount in 1..50
            ) {
                Text("创建${if (parsedCount > 0) " ($parsedCount)" else ""}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun PresetTreeFreezeDialog(
    presets: List<ClickPreset>,
    activePresetId: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val treeNodes = remember(presets) { buildPresetTree(presets) }
    var frozenIds by remember(presets) {
        mutableStateOf(presets.filter { it.isFrozen }.map { it.id }.toSet())
    }
    var expandedRoots by remember(presets) { mutableStateOf(emptySet<String>()) }

    fun toggleFrozen(id: String) {
        frozenIds = if (id in frozenIds) frozenIds - id else frozenIds + id
    }

    fun toggleExpanded(rootId: String) {
        expandedRoots = if (rootId in expandedRoots) expandedRoots - rootId else expandedRoots + rootId
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("冻结与解冻", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "冻结后的方案在方案页隐藏，悬浮窗切换时自动跳过且不占序号；嵌套子方案默认收起。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${treeNodes.size} 个主方案 · 已冻结 ${frozenIds.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            frozenIds = if (frozenIds.isEmpty()) {
                                treeNodes.flatMap { it.children }.map { it.id }.toSet()
                            } else {
                                emptySet()
                            }
                        }
                    ) {
                        Text(if (frozenIds.isEmpty()) "冻结子方案" else "全部解冻")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    treeNodes.forEach { node ->
                        val root = node.preset
                        val hasChildren = node.children.isNotEmpty()
                        val expanded = root.id in expandedRoots
                        val rootFrozen = root.id in frozenIds

                        item(key = "root_${root.id}") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (rootFrozen) cs.secondaryContainer.copy(alpha = 0.55f) else cs.surfaceContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (hasChildren) toggleExpanded(root.id) else toggleFrozen(root.id)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (hasChildren) {
                                        IconButton(
                                            onClick = { toggleExpanded(root.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                                                contentDescription = if (expanded) "收起子方案" else "展开子方案",
                                                modifier = Modifier.size(18.dp),
                                                tint = cs.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.width(6.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = root.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val activeTag = if (root.id == activePresetId && !rootFrozen) " · 使用中" else ""
                                        val childTag = if (hasChildren) " · ${node.children.size} 个子方案" else ""
                                        Text(
                                            text = "${root.targets.size} 步骤 · ${root.executionMode.label}$activeTag$childTag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = cs.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    FilterChip(
                                        selected = rootFrozen,
                                        onClick = { toggleFrozen(root.id) },
                                        label = {
                                            Text(
                                                text = if (rootFrozen) "已冻结" else "正常",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (hasChildren && expanded) {
                            items(
                                items = node.children,
                                key = { child -> "child_${child.id}" }
                            ) { child ->
                                val childFrozen = child.id in frozenIds
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (childFrozen) cs.secondaryContainer.copy(alpha = 0.45f) else cs.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { toggleFrozen(child.id) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = child.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${child.targets.size} 步骤 · ${child.executionMode.label}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = cs.onSurfaceVariant
                                            )
                                        }
                                        FilterChip(
                                            selected = childFrozen,
                                            onClick = { toggleFrozen(child.id) },
                                            label = {
                                                Text(
                                                    text = if (childFrozen) "已冻结" else "正常",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(frozenIds) }) {
                Text("保存")
            }
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
    activePresetId: String,
    executionMode: ExecutionMode,
    allPresets: List<ClickPreset>,
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
        repeatCount: Int,
        refPresetId: String,
        subDurationMs: Long,
        triggerTargetZone: Boolean
    ) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isSequential = executionMode == ExecutionMode.SEQUENTIAL
    val concurrentCandidates = remember(allPresets, activePresetId) {
        allPresets.filter { it.id != activePresetId && it.executionMode == ExecutionMode.CONCURRENT }
    }

    var actionType by remember {
        mutableStateOf(
            if (!isSequential && initial?.actionType == ActionType.SUB_PRESET) {
                ActionType.TAP
            } else {
                initial?.actionType ?: ActionType.TAP
            }
        )
    }
    var lastGestureType by remember {
        mutableStateOf(
            if (initial != null && initial.actionType != ActionType.SUB_PRESET) initial.actionType
            else ActionType.TAP
        )
    }
    var refPresetId by remember {
        mutableStateOf(
            initial?.refPresetId?.takeIf { id -> concurrentCandidates.any { it.id == id } }
                ?: concurrentCandidates.firstOrNull()?.id.orEmpty()
        )
    }
    var subDurationText by remember {
        mutableStateOf((initial?.subDurationMs ?: ClickTarget.DEFAULT_SUB_DURATION_MS).toString())
    }
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
    var intervalText by remember {
        mutableStateOf(
            (initial?.intervalMs ?: if (initial?.actionType == ActionType.SUB_PRESET) 0L else defaultIntervalMs).toString()
        )
    }
    var pressText by remember { mutableStateOf((initial?.pressDurationMs ?: defaultPressMs).toString()) }
    var swipeDurText by remember { mutableStateOf((initial?.swipeDurationMs ?: ClickTarget.DEFAULT_SWIPE_DURATION_MS).toString()) }
    var repeatText by remember {
        val defaultRep = initial?.repeatCount ?: ClickTarget.defaultRepeatFor(executionMode)
        mutableStateOf(defaultRep.toString())
    }
    var jitter by remember { mutableStateOf(initial?.microJitter ?: defaultJitter) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var triggerTargetZone by remember { mutableStateOf(initial?.triggerTargetZone ?: true) }

    val canSave = actionType != ActionType.SUB_PRESET || refPresetId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial != null) "编辑步骤 #${initial.index}" else "新增步骤",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isSequential) {
                    val isSubPreset = actionType == ActionType.SUB_PRESET
                    ExpressiveConnectedGroup(
                        options = listOf(
                            ExpressiveOption(false, "基础手势", R.drawable.ic_touch_app),
                            ExpressiveOption(true, "并发方案", R.drawable.ic_layers)
                        ),
                        selectedValue = isSubPreset,
                        onSelect = { chooseSub ->
                            actionType = if (chooseSub) {
                                if (initial == null) {
                                    intervalText = "0"
                                }
                                ActionType.SUB_PRESET
                            } else {
                                if (initial == null && intervalText == "0") {
                                    intervalText = defaultIntervalMs.toString()
                                }
                                lastGestureType
                            }
                        },
                        height = 40.dp
                    )
                }

                if (actionType != ActionType.SUB_PRESET) {
                    ExpressiveConnectedGroup(
                        options = listOf(ActionType.TAP, ActionType.SWIPE, ActionType.HOLD).map { act ->
                            ExpressiveOption(
                                value = act,
                                label = act.label,
                                iconRes = when (act) {
                                    ActionType.TAP -> R.drawable.ic_touch_app
                                    ActionType.SWIPE -> R.drawable.ic_swipe
                                    else -> R.drawable.ic_hold
                                }
                            )
                        },
                        selectedValue = actionType,
                        onSelect = {
                            actionType = it
                            lastGestureType = it
                        },
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
                }

                when (actionType) {
                    ActionType.SUB_PRESET -> {
                        if (concurrentCandidates.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = cs.errorContainer.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "暂无可引入的「并发」方案，请先创建或将一个方案设为并发模式。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "选择并发子方案",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                concurrentCandidates.forEach { candidate ->
                                    val picked = candidate.id == refPresetId
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (picked) cs.primaryContainer else cs.surfaceContainerHigh,
                                        border = if (picked) BorderStroke(1.2.dp, cs.primary) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { refPresetId = candidate.id }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = candidate.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                                color = if (picked) cs.onPrimaryContainer else cs.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${candidate.targets.count { it.enabled }} 节点",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (picked) cs.onPrimaryContainer else cs.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "并发运行持续时长",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant
                            )
                            val subDurVal = subDurationText.toLongOrNull() ?: ClickTarget.DEFAULT_SUB_DURATION_MS
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    1000L to "1秒",
                                    3000L to "3秒",
                                    5000L to "5秒",
                                    10000L to "10秒"
                                ).forEach { (ms, tag) ->
                                    val selected = subDurVal == ms
                                    Surface(
                                        shape = CircleShape,
                                        color = if (selected) cs.primary else cs.surfaceContainerHigh,
                                        contentColor = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(CircleShape)
                                            .clickable { subDurationText = ms.toString() }
                                    ) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            modifier = Modifier.padding(vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = subDurationText,
                                    onValueChange = { subDurationText = it.filter(Char::isDigit).take(6) },
                                    label = { Text("持续时长(ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = intervalText,
                                    onValueChange = { intervalText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("步后停顿(ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            val gapVal = intervalText.toLongOrNull() ?: 0L
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(0L to "无缝(0)", 20L to "20ms", 60L to "60ms", 200L to "200ms").forEach { (ms, tag) ->
                                    val sel = gapVal == ms
                                    Surface(
                                        shape = CircleShape,
                                        color = if (sel) cs.primary else cs.surfaceContainerHigh,
                                        contentColor = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(CircleShape)
                                            .clickable { intervalText = ms.toString() }
                                    ) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "步后停顿为 0ms 时，相邻同坐标按住/滑动自动不断触衔接",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
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
                                label = { Text("步后停顿(ms)") },
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
                                label = { Text("按住时长(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { intervalText = it.filter(Char::isDigit).take(5) },
                                label = { Text("步后停顿(ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    ActionType.TAP -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = cs.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "间隔 ${defaultIntervalMs}ms · 按压 ${defaultPressMs}ms（由「调校 → 连点设置」统一控制）",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = repeatText,
                    onValueChange = { repeatText = it.filter(Char::isDigit).take(4) },
                    label = { Text("循环次数 (0=无限, 1~9999)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                if (actionType == ActionType.TAP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开启拟人高斯抖动", style = MaterialTheme.typography.bodyMedium)
                        CheckSwitch(checked = jitter, onCheckedChange = { jitter = it })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用该步骤", style = MaterialTheme.typography.bodyMedium)
                    CheckSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (actionType == ActionType.TAP || actionType == ActionType.HOLD) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("设为触发目标区域", style = MaterialTheme.typography.bodyMedium)
                        CheckSwitch(checked = triggerTargetZone, onCheckedChange = { triggerTargetZone = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val x = (xText.toFloatOrNull() ?: (screenW * 0.5f))
                        .coerceIn(0f, (screenW - 1).coerceAtLeast(1).toFloat())
                    val y = (yText.toFloatOrNull() ?: (screenH * 0.5f))
                        .coerceIn(0f, (screenH - 1).coerceAtLeast(1).toFloat())
                    val ex = (endXText.toFloatOrNull() ?: (screenW * 0.7f))
                        .coerceIn(0f, (screenW - 1).coerceAtLeast(1).toFloat())
                    val ey = (endYText.toFloatOrNull() ?: y)
                        .coerceIn(0f, (screenH - 1).coerceAtLeast(1).toFloat())
                    val minGapOrInt = if (isSequential && actionType != ActionType.TAP) {
                        ClickTarget.MIN_STAGE_GAP_MS
                    } else {
                        ClickTarget.MIN_INTERVAL_MS
                    }
                    val interval = if (actionType == ActionType.TAP) {
                        defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                    } else {
                        (intervalText.toLongOrNull() ?: defaultIntervalMs)
                            .coerceIn(minGapOrInt, ClickTarget.MAX_INTERVAL_MS)
                    }
                    val press = if (actionType == ActionType.TAP) {
                        defaultPressMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    } else {
                        (pressText.toLongOrNull() ?: defaultPressMs)
                            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    }
                    val swipeDur = (swipeDurText.toLongOrNull() ?: ClickTarget.DEFAULT_SWIPE_DURATION_MS)
                        .coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS)
                    val repeatCount = (repeatText.toIntOrNull() ?: ClickTarget.defaultRepeatFor(executionMode))
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    val subDur = (subDurationText.toLongOrNull() ?: ClickTarget.DEFAULT_SUB_DURATION_MS)
                        .coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS)
                    onSave(
                        x, y, interval, press, jitter, enabled, actionType,
                        ex, ey, swipeDur, repeatCount,
                        if (actionType == ActionType.SUB_PRESET) refPresetId else "",
                        subDur,
                        triggerTargetZone
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
