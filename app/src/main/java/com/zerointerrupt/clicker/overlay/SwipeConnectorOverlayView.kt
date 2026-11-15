package com.zerointerrupt.clicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickTarget
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full-screen non-touchable overlay drawing dashed directional vectors for SWIPE gestures.
 */
class SwipeConnectorOverlayView(context: Context) : View(context) {

    private data class SwipeSegment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val enabled: Boolean
    )

    private var segments: List<SwipeSegment> = emptyList()
    private var running: Boolean = false
    private var locked: Boolean = false

    private val d = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * d
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()

    fun updateSwipes(targets: List<ClickTarget>, isRunning: Boolean, isLocked: Boolean) {
        running = isRunning
        locked = isLocked
        segments = targets
            .filter { it.actionType == ActionType.SWIPE }
            .map { SwipeSegment(it.x, it.y, it.endX, it.endY, it.enabled) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        for (seg in segments) {
            val dist = hypot((seg.x2 - seg.x1).toDouble(), (seg.y2 - seg.y1).toDouble()).toFloat()
            if (dist < 4f) continue

            val baseColor = when {
                !seg.enabled -> Color.parseColor("#64748B")
                running -> Color.parseColor("#00E676")
                else -> Color.parseColor("#7C4DFF")
            }
            val alpha = if (locked) 130 else 220

            linePaint.color = baseColor
            linePaint.alpha = alpha
            arrowPaint.color = baseColor
            arrowPaint.alpha = alpha

            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, linePaint)

            val angle = atan2((seg.y2 - seg.y1).toDouble(), (seg.x2 - seg.x1).toDouble())
            val tipBack = 14f * d
            val tx = seg.x2 - (cos(angle) * tipBack).toFloat()
            val ty = seg.y2 - (sin(angle) * tipBack).toFloat()
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
    }
}
