package com.zerointerrupt.clicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickTarget
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen non-touchable overlay layer.
 *
 * When unlocked, draws dashed directional vectors for SWIPE gestures underneath draggable anchors.
 * When locked (including during automated playback), renders BOTH SWIPE vectors and all gesture
 * anchors in this single WindowManager surface so Android 12+ BLOCK_UNTRUSTED touch-occlusion
 * opacity never compounds across stacked overlay windows or breaks cross-app / desktop touches.
 */
class SwipeConnectorOverlayView(context: Context) : View(context) {

    private data class DrawNode(
        val indexNumber: Int,
        val actionType: ActionType,
        val x: Float,
        val y: Float,
        val endX: Float,
        val endY: Float,
        val intervalMs: Long,
        val enabled: Boolean,
        val isExpandedChild: Boolean
    )

    private data class ActiveSpot(
        var x: Float,
        var y: Float
    )

    private data class FadingSpot(
        val x: Float,
        val y: Float,
        val liftTimeMs: Long
    )

    private var nodes: List<DrawNode> = emptyList()
    private var running: Boolean = false
    private var locked: Boolean = false
    private var drawMacros: Boolean = true
    private var macroAlphaScale: Float = 1f

    // Silent system-synced show_touches dots for Tier 3 Hybrid mode
    private val activeSpots = LinkedHashMap<Int, ActiveSpot>()
    private val fadingSpots = ArrayList<FadingSpot>(16)
    private var lastTouchIdsSeen = HashSet<Int>(16)

    private val d = resources.displayMetrics.density
    private val touchSpotRadiusPx = 11.5f * d
    private val touchSpotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val touchSpotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        color = Color.BLACK
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * d
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
    }
    private val solidCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
    }
    private val endRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * d
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
    }
    private val secondaryRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13f * d
        setShadowLayer(2f * d, 0f, 1f, 0xCC000000.toInt())
    }
    private val endTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 11f * d
        setShadowLayer(2f * d, 0f, 1f, 0xCC000000.toInt())
    }

    fun updateSwipes(targets: List<ClickTarget>, isRunning: Boolean, isLocked: Boolean) {
        updateExpandedSwipes(
            entries = targets.map { ClickConfigStore.DisplayTargetEntry(it, isExpandedChild = false) },
            isRunning = isRunning,
            isLocked = isLocked
        )
    }

    fun updateExpandedSwipes(
        entries: List<ClickConfigStore.DisplayTargetEntry>,
        isRunning: Boolean,
        isLocked: Boolean,
        showMacros: Boolean = true,
        macroAlphaScale: Float = 1f
    ) {
        running = isRunning
        locked = isLocked
        drawMacros = showMacros
        this.macroAlphaScale = macroAlphaScale.coerceIn(0f, 1f)
        var seq = 1
        nodes = if (!showMacros) {
            emptyList()
        } else {
            entries
                .filter { it.target.actionType != ActionType.SUB_PRESET }
                .map { entry ->
                    val t = entry.target
                    val displayNum = if (entry.isExpandedChild) seq++ else {
                        seq = maxOf(seq, t.index + 1)
                        t.index
                    }
                    DrawNode(
                        indexNumber = displayNum,
                        actionType = t.actionType,
                        x = t.x,
                        y = t.y,
                        endX = t.endX,
                        endY = t.endY,
                        intervalMs = t.intervalMs,
                        enabled = t.enabled,
                        isExpandedChild = entry.isExpandedChild
                    )
                }
        }
        invalidate()
    }

    fun updateHybridTouchPoints(pointerIds: IntArray, xs: FloatArray, ys: FloatArray) {
        val nowMs = SystemClock.uptimeMillis()
        val count = minOf(pointerIds.size, xs.size, ys.size)
        lastTouchIdsSeen.clear()
        for (i in 0 until count) {
            val pid = pointerIds[i]
            val x = xs[i]
            val y = ys[i]
            lastTouchIdsSeen.add(pid)
            val existing = activeSpots[pid]
            if (existing != null) {
                existing.x = x
                existing.y = y
            } else {
                activeSpots[pid] = ActiveSpot(x, y)
            }
        }

        val iter = activeSpots.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!lastTouchIdsSeen.contains(entry.key)) {
                if (fadingSpots.size >= MAX_FADING_SPOTS) {
                    fadingSpots.removeAt(0)
                }
                fadingSpots.add(FadingSpot(entry.value.x, entry.value.y, nowMs))
                iter.remove()
            }
        }
        invalidate()
    }

    fun clearHybridTouchSpots() {
        if (activeSpots.isEmpty() && fadingSpots.isEmpty()) return
        activeSpots.clear()
        fadingSpots.clear()
        invalidate()
    }

    private fun drawTouchSpots(canvas: Canvas) {
        if (activeSpots.isEmpty() && fadingSpots.isEmpty()) return

        val nowMs = SystemClock.uptimeMillis()
        var needsNextFrame = false

        var i = 0
        while (i < fadingSpots.size) {
            val spot = fadingSpots[i]
            val elapsed = nowMs - spot.liftTimeMs
            if (elapsed >= SPOT_FADE_DURATION_MS) {
                fadingSpots.removeAt(i)
                continue
            }
            val progress = (elapsed.toFloat() / SPOT_FADE_DURATION_MS).coerceIn(0f, 1f)
            val fadeAlpha = (1f - progress) * (1f - progress)
            val radius = touchSpotRadiusPx * (1f - 0.16f * progress)
            touchSpotHaloPaint.alpha = (88f * fadeAlpha).roundToInt().coerceIn(0, 255)
            touchSpotFillPaint.alpha = (232f * fadeAlpha).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(spot.x, spot.y, radius, touchSpotFillPaint)
            canvas.drawCircle(spot.x, spot.y, radius, touchSpotHaloPaint)
            needsNextFrame = true
            i++
        }

        if (activeSpots.isNotEmpty()) {
            touchSpotHaloPaint.alpha = 96
            touchSpotFillPaint.alpha = 240
            for (spot in activeSpots.values) {
                canvas.drawCircle(spot.x, spot.y, touchSpotRadiusPx, touchSpotFillPaint)
                canvas.drawCircle(spot.x, spot.y, touchSpotRadiusPx, touchSpotHaloPaint)
            }
        }

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawMacros && nodes.isNotEmpty()) {
            drawMacroLayer(canvas)
        }
        drawTouchSpots(canvas)
    }

    private fun drawMacroLayer(canvas: Canvas) {
        val mScale = macroAlphaScale
        val denseMode = nodes.size > 12

        // Layer 1: Draw SWIPE vector lines / arrows
        for (i in nodes.indices) {
            val node = nodes[i]
            if (node.actionType != ActionType.SWIPE) continue
            val dist = hypot((node.endX - node.x).toDouble(), (node.endY - node.y).toDouble()).toFloat()
            if (dist < 4f) continue

            val baseColor = when {
                !node.enabled -> Color.parseColor("#64748B")
                running -> Color.parseColor("#00E676")
                else -> Color.parseColor("#7C4DFF")
            }
            val vecAlpha = ((if (locked) 210 else 220) * mScale).roundToInt().coerceIn(0, 255)

            if (denseMode) {
                solidCurvePaint.color = baseColor
                solidCurvePaint.alpha = vecAlpha
                canvas.drawLine(node.x, node.y, node.endX, node.endY, solidCurvePaint)
                continue
            }

            linePaint.color = baseColor
            linePaint.alpha = vecAlpha
            arrowPaint.color = baseColor
            arrowPaint.alpha = vecAlpha

            canvas.drawLine(node.x, node.y, node.endX, node.endY, linePaint)

            val angle = atan2((node.endY - node.y).toDouble(), (node.endX - node.x).toDouble())
            val tipBack = 14f * d
            val tx = node.endX - (cos(angle) * tipBack).toFloat()
            val ty = node.endY - (sin(angle) * tipBack).toFloat()
            val headLen = 10f * d
            val spread = Math.toRadians(28.0)

            arrowPath.reset()
            arrowPath.moveTo(tx, ty)
            arrowPath.lineTo(
                tx - (headLen * cos(angle - spread)).toFloat(),
                ty - (headLen * sin(angle - spread)).toFloat()
            )
            arrowPath.lineTo(
                tx - (headLen * cos(angle + spread)).toFloat(),
                ty - (headLen * sin(angle + spread)).toFloat()
            )
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }

        // When unlocked, direct non-child anchors render as draggable AnchorPointView windows above this layer;
        // expanded multi-finger child nodes still render here so concurrent sub-preset fingers remain visible.
        val hasExpandedChildren = nodes.any { it.isExpandedChild }
        if (!locked && !hasExpandedChildren) return

        // Layer 2: Unified anchor rendering (single WindowManager window = zero alpha stacking)
        val ringAlphaScale = (if (running) 0.78f else 0.94f) * mScale
        val radius = 19f * d
        val endRadius = 13.5f * d
        val innerTick = radius + 1.5f * d
        val outerTick = radius + 5.0f * d
        val fontMetrics = textPaint.fontMetrics
        val endFontMetrics = endTextPaint.fontMetrics

        for (i in nodes.indices) {
            val node = nodes[i]
            if (!locked && !node.isExpandedChild) continue
            val accentColor = when {
                !node.enabled -> Color.parseColor("#64748B")
                running -> Color.parseColor("#00E676")
                node.actionType == ActionType.SWIPE -> Color.parseColor("#7C4DFF")
                node.actionType == ActionType.HOLD -> Color.parseColor("#FF9100")
                else -> Color.parseColor("#00E5FF")
            }

            // Search neighboring stages (supports multi-finger concurrent SWIPE streams interleaved in sequential blocks)
            var isContinuousMidKnot = false
            if (denseMode && node.actionType == ActionType.SWIPE) {
                val minIdx = maxOf(0, i - 12)
                for (k in (i - 1) downTo minIdx) {
                    val prev = nodes[k]
                    if (prev.actionType == ActionType.SWIPE &&
                        prev.intervalMs <= 80L &&
                        hypot((node.x - prev.endX).toDouble(), (node.y - prev.endY).toDouble()) <= 22.0
                    ) {
                        isContinuousMidKnot = true
                        break
                    }
                }
            }

            if (isContinuousMidKnot) {
                centerDotPaint.color = accentColor
                centerDotPaint.alpha = (210 * ringAlphaScale).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(node.x, node.y, 3.2f * d, centerDotPaint)
            } else {
                fillPaint.color = accentColor
                fillPaint.alpha = (42 * mScale).roundToInt().coerceIn(0, 255)
                outerRingPaint.color = accentColor
                outerRingPaint.alpha = (255 * ringAlphaScale).roundToInt().coerceIn(0, 255)
                crosshairPaint.color = accentColor
                crosshairPaint.alpha = (210 * ringAlphaScale).roundToInt().coerceIn(0, 255)

                canvas.drawCircle(node.x, node.y, radius, fillPaint)
                canvas.drawCircle(node.x, node.y, radius, outerRingPaint)

                if (node.actionType == ActionType.HOLD && node.enabled) {
                    secondaryRingPaint.color = accentColor
                    secondaryRingPaint.alpha = (180 * ringAlphaScale).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(node.x, node.y, radius - 4.2f * d, secondaryRingPaint)
                }

                canvas.drawLine(node.x, node.y - outerTick, node.x, node.y - innerTick, crosshairPaint)
                canvas.drawLine(node.x, node.y + innerTick, node.x, node.y + outerTick, crosshairPaint)
                canvas.drawLine(node.x - outerTick, node.y, node.x - innerTick, node.y, crosshairPaint)
                canvas.drawLine(node.x + innerTick, node.y, node.x + outerTick, node.y, crosshairPaint)

                centerDotPaint.color = accentColor
                centerDotPaint.alpha = (210 * mScale).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(node.x, node.y, 2.2f * d, centerDotPaint)

                textPaint.alpha = (255 * ringAlphaScale).roundToInt().coerceIn(0, 255)
                val textY = node.y - (fontMetrics.ascent + fontMetrics.descent) * 0.5f
                canvas.drawText(node.indexNumber.toString(), node.x, textY, textPaint)
            }

            if (node.actionType == ActionType.SWIPE) {
                var seamlesslyContinues = false
                if (node.intervalMs <= 80L) {
                    val maxIdx = minOf(nodes.lastIndex, i + 12)
                    for (k in (i + 1)..maxIdx) {
                        val next = nodes[k]
                        if ((next.actionType == ActionType.SWIPE || next.actionType == ActionType.HOLD) &&
                            hypot((next.x - node.endX).toDouble(), (next.y - node.endY).toDouble()) <= 22.0
                        ) {
                            seamlesslyContinues = true
                            break
                        }
                    }
                }
                if (!seamlesslyContinues) {
                    fillPaint.color = accentColor
                    fillPaint.alpha = (36 * mScale).roundToInt().coerceIn(0, 255)
                    endRingPaint.color = accentColor
                    endRingPaint.alpha = (255 * ringAlphaScale).roundToInt().coerceIn(0, 255)

                    canvas.drawCircle(node.endX, node.endY, endRadius, fillPaint)
                    canvas.drawCircle(node.endX, node.endY, endRadius, endRingPaint)

                    endTextPaint.alpha = (255 * ringAlphaScale).roundToInt().coerceIn(0, 255)
                    val ty = node.endY - (endFontMetrics.ascent + endFontMetrics.descent) * 0.5f
                    canvas.drawText("终", node.endX, ty, endTextPaint)
                }
            }
        }
    }

    companion object {
        private const val SPOT_FADE_DURATION_MS = 135L
        private const val MAX_FADING_SPOTS = 32
    }
}
