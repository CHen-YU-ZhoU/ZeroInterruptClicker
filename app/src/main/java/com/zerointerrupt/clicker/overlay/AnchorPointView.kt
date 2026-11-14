package com.zerointerrupt.clicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Custom Canvas-drawn target anchor ring.
 *
 * Designed for zero WMS relayout: visual state switches (edit/locked, idle/running, selected)
 * update local Paint state and call invalidate() only.
 */
@SuppressLint("ViewConstructor")
class AnchorPointView(
    context: Context,
    val targetId: Int,
    private val onDragMove: (targetId: Int, newLpX: Int, newLpY: Int) -> Unit,
    private val onDragFinish: (targetId: Int) -> Unit,
    private val onTap: (targetId: Int) -> Unit
) : View(context) {

    val viewSizePx: Int = (52f * resources.displayMetrics.density).roundToInt().let {
        if (it % 2 == 0) it else it + 1
    }
    val halfSizePx: Int = viewSizePx / 2

    private var indexNumber: Int = 1
    private var targetEnabled: Boolean = true
    private var microJitter: Boolean = true
    private var locked: Boolean = false
    private var running: Boolean = false
    private var selected: Boolean = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0
    private var currentLpX = 0
    private var currentLpY = 0
    private var isDragging = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * resources.displayMetrics.density
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val jitterRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * resources.displayMetrics.density
        pathEffect = DashPathEffect(
            floatArrayOf(3f * resources.displayMetrics.density, 3f * resources.displayMetrics.density),
            0f
        )
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13f * resources.displayMetrics.density
        setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f, 0xCC000000.toInt())
    }

    init {
        val rippleMask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        foreground = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, rippleMask)
    }

    fun syncWindowPosition(lpX: Int, lpY: Int) {
        currentLpX = lpX
        currentLpY = lpY
    }

    fun setVisualState(
        index: Int,
        enabled: Boolean,
        jitter: Boolean,
        isLocked: Boolean,
        isRunning: Boolean,
        isSelected: Boolean
    ) {
        this.indexNumber = index
        this.targetEnabled = enabled
        this.microJitter = jitter
        this.locked = isLocked
        this.running = isRunning
        this.selected = isSelected
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewSizePx, viewSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val d = resources.displayMetrics.density
        val radius = 19f * d

        val accentColor = when {
            !targetEnabled -> Color.parseColor("#64748B")
            running -> Color.parseColor("#00E676")
            selected && !locked -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#00E5FF")
        }

        val alphaScale = if (locked) (if (running) 0.52f else 0.65f) else 0.95f

        fillPaint.color = accentColor
        fillPaint.alpha = (if (locked) 28 else 56)

        outerRingPaint.color = accentColor
        outerRingPaint.alpha = (255 * alphaScale).roundToInt()
        outerRingPaint.strokeWidth = (if (selected && !locked) 3.0f else 2.2f) * d

        crosshairPaint.color = accentColor
        crosshairPaint.alpha = (210 * alphaScale).roundToInt()

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, outerRingPaint)

        if (microJitter && targetEnabled && !locked) {
            jitterRingPaint.color = accentColor
            jitterRingPaint.alpha = 150
            canvas.drawCircle(cx, cy, 8f * d, jitterRingPaint)
        }

        // Precision outer crosshair ticks
        val innerTick = radius + 1.5f * d
        val outerTick = radius + 5.0f * d
        canvas.drawLine(cx, cy - outerTick, cx, cy - innerTick, crosshairPaint)
        canvas.drawLine(cx, cy + innerTick, cx, cy + outerTick, crosshairPaint)
        canvas.drawLine(cx - outerTick, cy, cx - innerTick, cy, crosshairPaint)
        canvas.drawLine(cx + innerTick, cy, cx + outerTick, cy, crosshairPaint)

        // Subtle center dot
        badgeBgPaint.color = accentColor
        badgeBgPaint.alpha = (if (locked) 170 else 230)
        canvas.drawCircle(cx, cy, 2.2f * d, badgeBgPaint)

        // Index number cleanly drawn inside upper/center area with shadow
        textPaint.alpha = (255 * alphaScale).roundToInt()
        val fontMetrics = textPaint.fontMetrics
        val textY = cy - (fontMetrics.ascent + fontMetrics.descent) * 0.5f - (if (locked) 0f else 0f)
        canvas.drawText(indexNumber.toString(), cx, textY, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startLpX = currentLpX
                startLpY = currentLpY
                isDragging = false
                drawableHotspotChanged(event.x, event.y)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx, dy) >= touchSlop) {
                    isDragging = true
                    isPressed = false
                }
                if (isDragging) {
                    val nx = (startLpX + dx).roundToInt()
                    val ny = (startLpY + dy).roundToInt()
                    currentLpX = nx
                    currentLpY = ny
                    onDragMove(targetId, nx, ny)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (isDragging) {
                    onDragFinish(targetId)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    onTap(targetId)
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
