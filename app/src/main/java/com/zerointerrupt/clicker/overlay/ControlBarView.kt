package com.zerointerrupt.clicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Three-state Material Design 3 floating control capsule:
 *
 * 1. Compact initial state (isToolsExpanded = false):
 *    [ Top edge-dock (< / >) ] -> [ Play / Stop ] -> [ Switch Preset (1..N) ] -> [ Expand tools (v, rotated 90 deg) ]
 * 2. Tools expanded state (isToolsExpanded = true):
 *    Unfolds +, -, Lock, Tune, Close above the symmetric collapse tools chevron (^).
 * 3. Edge-docked collapsed state (isCollapsed = true):
 *    Ultra-slim 26dp x 74dp edge-docked tab with live TouchMux LED + inward chevron.
 *
 * Every touch target uses Android native Material RippleDrawable for classic ripple wave feedback.
 */
@SuppressLint("ViewConstructor")
class ControlBarView(
    context: Context,
    initialColors: OverlayThemeColors,
    private val listener: Listener
) : FrameLayout(context) {

    interface Listener {
        fun onTogglePlayStop()
        fun onSwitchPreset()
        fun onToggleToolsExpanded()
        fun onAddTarget()
        fun onRemoveTarget()
        fun onToggleLockEdit()
        fun onToggleTunePanel()
        fun onCloseOverlay()
        fun onCollapseRequested()
        fun onExpandRequested()
        fun onDragStart()
        fun onDragMove(newLpX: Int, newLpY: Int)
        fun onDragEnd()
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val expandedWidthPx = (52f * density).roundToInt()
    private val btnSizePx = (38f * density).roundToInt()
    private val capHeightPx = (30f * density).roundToInt()
    private val padPx = (5f * density).roundToInt()
    private val gapPx = (4f * density).roundToInt()

    // Top cap + Play + Switch + Bottom cap + 3 gaps + outer vertical padding
    private val compactBarHeightPx = padPx * 2 + capHeightPx * 2 + btnSizePx * 2 + gapPx * 3
    // Unfolded height includes 5 secondary tool buttons (+, -, lock, tune, close) each + gapPx
    private val fullBarHeightPx = compactBarHeightPx + (btnSizePx + gapPx) * 5

    private val collapsedWidthPx = (26f * density).roundToInt()
    private val collapsedHeightPx = (74f * density).roundToInt()

    private var colors: OverlayThemeColors = initialColors
    private var isCollapsedState: Boolean = false
    private var isToolsExpandedState: Boolean = false
    private var dockRightState: Boolean = false
    private var runningState: Boolean = false
    private var lockedState: Boolean = false
    private var engineModeState: Int = TouchMuxUserService.MODE_IDLE
    private var tunePanelOpenState: Boolean = false
    private var activePresetIndex: Int = 0
    private var totalPresetCount: Int = 1

    private val expandedContainer: LinearLayout
    private val headerCollapseView: ExpandedHeaderView
    private val playStopBtn: ImageView
    private val playStopBg = ovalGradient()
    private val switchPresetBtn: PresetSwitchButtonView
    private val switchPresetBg = ovalGradient()
    private val secondaryToolsContainer: LinearLayout
    private val addBtn: ImageView
    private val addBg = ovalGradient()
    private val removeBtn: ImageView
    private val removeBg = ovalGradient()
    private val lockBtn: ImageView
    private val lockBg = ovalGradient()
    private val tuneBtn: ImageView
    private val tuneBg = ovalGradient()
    private val closeBtn: ImageView
    private val closeBg = ovalGradient()
    private val footerExpandView: ExpandToolsFooterView
    private val collapsedPillView: CollapsedEdgePillView

    private var currentLpX = 0
    private var currentLpY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0
    private var isDragging = false

    init {
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padPx, padPx, padPx, padPx)
        }

        headerCollapseView = ExpandedHeaderView(context).apply {
            isClickable = true
            setOnClickListener { listener.onCollapseRequested() }
        }
        expandedContainer.addView(
            headerCollapseView,
            LinearLayout.LayoutParams(expandedWidthPx - padPx * 2, capHeightPx).apply {
                bottomMargin = gapPx
            }
        )

        playStopBtn = createActionButton(R.drawable.ic_play, playStopBg) { listener.onTogglePlayStop() }
        expandedContainer.addView(
            playStopBtn,
            LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                bottomMargin = gapPx
            }
        )

        switchPresetBtn = PresetSwitchButtonView(context).apply {
            isClickable = true
            background = initialColors.createRippleDrawable(switchPresetBg)
            setOnClickListener { listener.onSwitchPreset() }
        }
        expandedContainer.addView(
            switchPresetBtn,
            LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                bottomMargin = gapPx
            }
        )

        secondaryToolsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        addBtn = createActionButton(R.drawable.ic_add, addBg) { listener.onAddTarget() }
        removeBtn = createActionButton(R.drawable.ic_remove, removeBg) { listener.onRemoveTarget() }
        lockBtn = createActionButton(R.drawable.ic_lock_open, lockBg) { listener.onToggleLockEdit() }
        tuneBtn = createActionButton(R.drawable.ic_tune, tuneBg) { listener.onToggleTunePanel() }
        closeBtn = createActionButton(R.drawable.ic_close, closeBg) { listener.onCloseOverlay() }

        listOf(addBtn, removeBtn, lockBtn, tuneBtn, closeBtn).forEach { btn ->
            secondaryToolsContainer.addView(
                btn,
                LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                    bottomMargin = gapPx
                }
            )
        }
        expandedContainer.addView(
            secondaryToolsContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        footerExpandView = ExpandToolsFooterView(context).apply {
            isClickable = true
            setOnClickListener { listener.onToggleToolsExpanded() }
        }
        expandedContainer.addView(
            footerExpandView,
            LinearLayout.LayoutParams(expandedWidthPx - padPx * 2, capHeightPx)
        )

        collapsedPillView = CollapsedEdgePillView(context).apply {
            isClickable = true
            visibility = View.GONE
            setOnClickListener { listener.onExpandRequested() }
        }

        addView(expandedContainer, LayoutParams(expandedWidthPx, LayoutParams.WRAP_CONTENT))
        addView(collapsedPillView, LayoutParams(collapsedWidthPx, collapsedHeightPx))

        applyThemeColors(initialColors)
    }

    private fun ovalGradient(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
    }

    private fun createActionButton(
        iconRes: Int,
        bgDrawable: GradientDrawable,
        onClick: () -> Unit
    ): ImageView {
        val iv = ImageView(context)
        val iconPad = (9f * density).roundToInt()
        iv.setPadding(iconPad, iconPad, iconPad, iconPad)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
        iv.isClickable = true
        iv.background = colors.createRippleDrawable(bgDrawable)
        iv.setOnClickListener { onClick() }
        return iv
    }

    fun applyThemeColors(newColors: OverlayThemeColors) {
        colors = newColors
        expandedContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(newColors.surfaceContainerHigh)
            setStroke((1f * density).roundToInt().coerceAtLeast(1), newColors.outlineVariant)
        }

        val rippleTint = ColorStateList.valueOf(newColors.ripple)
        listOf(playStopBtn, switchPresetBtn, addBtn, removeBtn, lockBtn, tuneBtn, closeBtn).forEach { btn ->
            (btn.background as? RippleDrawable)?.setColor(rippleTint)
        }

        val capCornerRadius = capHeightPx * 0.5f
        val capRadii = FloatArray(8) { capCornerRadius }
        headerCollapseView.foreground = newColors.createRippleDrawable(null, maskCornerRadii = capRadii)
        footerExpandView.foreground = newColors.createRippleDrawable(null, maskCornerRadii = capRadii)
        updateCollapsedPillForeground()

        redrawButtonStates()
        headerCollapseView.invalidate()
        switchPresetBtn.invalidate()
        footerExpandView.invalidate()
        collapsedPillView.invalidate()
    }

    private fun updateCollapsedPillForeground() {
        val r = 16f * density
        val radii = if (dockRightState) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        collapsedPillView.foreground = colors.createRippleDrawable(null, maskCornerRadii = radii)
    }

    fun syncWindowPosition(lpX: Int, lpY: Int) {
        currentLpX = lpX
        currentLpY = lpY
    }

    fun getCurrentWidthPx(): Int = if (isCollapsedState) collapsedWidthPx else expandedWidthPx

    fun getCurrentHeightPx(): Int = when {
        isCollapsedState -> collapsedHeightPx
        isToolsExpandedState -> fullBarHeightPx
        else -> compactBarHeightPx
    }

    fun getExpandedWidthPx(): Int = expandedWidthPx

    fun getExpandedBarHeightPx(): Int = if (isToolsExpandedState) fullBarHeightPx else compactBarHeightPx

    fun getCollapsedWidthPx(): Int = collapsedWidthPx

    fun getCollapsedHeightPx(): Int = collapsedHeightPx

    fun isToolsExpanded(): Boolean = isToolsExpandedState

    fun setToolsExpanded(expanded: Boolean) {
        isToolsExpandedState = expanded
        secondaryToolsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        footerExpandView.invalidate()
    }

    fun setCollapsed(collapsed: Boolean, dockRight: Boolean) {
        isCollapsedState = collapsed
        val dockChanged = dockRightState != dockRight
        dockRightState = dockRight
        if (dockChanged) {
            updateCollapsedPillForeground()
        }
        expandedContainer.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedPillView.visibility = if (collapsed) View.VISIBLE else View.GONE
        headerCollapseView.invalidate()
        footerExpandView.invalidate()
        collapsedPillView.invalidate()
    }

    fun setDockRight(dockRight: Boolean) {
        if (dockRightState != dockRight) {
            dockRightState = dockRight
            updateCollapsedPillForeground()
            headerCollapseView.invalidate()
            collapsedPillView.invalidate()
        }
    }

    fun updateState(
        isRunning: Boolean,
        isLocked: Boolean,
        engineMode: Int,
        tunePanelOpen: Boolean,
        presetIndex: Int,
        presetCount: Int
    ) {
        runningState = isRunning
        lockedState = isLocked
        engineModeState = engineMode
        tunePanelOpenState = tunePanelOpen
        activePresetIndex = presetIndex
        totalPresetCount = presetCount.coerceAtLeast(1)
        redrawButtonStates()
        headerCollapseView.invalidate()
        switchPresetBtn.invalidate()
        footerExpandView.invalidate()
        collapsedPillView.invalidate()
    }

    private fun redrawButtonStates() {
        val neutralBg = ColorUtils.setAlphaComponent(colors.surfaceContainerHighest, 220)
        val neutralFg = colors.onSurfaceVariant

        if (runningState) {
            playStopBtn.setImageResource(R.drawable.ic_stop)
            playStopBtn.setColorFilter(colors.onErrorContainer)
            playStopBg.setColor(colors.errorContainer)
        } else {
            playStopBtn.setImageResource(R.drawable.ic_play)
            playStopBtn.setColorFilter(colors.onPrimaryContainer)
            playStopBg.setColor(colors.primaryContainer)
        }

        if (totalPresetCount > 1) {
            switchPresetBg.setColor(colors.secondaryContainer)
        } else {
            switchPresetBg.setColor(neutralBg)
        }

        addBg.setColor(neutralBg)
        addBtn.setColorFilter(colors.onSurface)

        removeBg.setColor(neutralBg)
        removeBtn.setColorFilter(colors.onSurface)

        if (lockedState) {
            lockBtn.setImageResource(R.drawable.ic_lock)
            lockBtn.setColorFilter(colors.onTertiaryContainer)
            lockBg.setColor(colors.tertiaryContainer)
        } else {
            lockBtn.setImageResource(R.drawable.ic_lock_open)
            lockBtn.setColorFilter(colors.primary)
            lockBg.setColor(neutralBg)
        }

        if (tunePanelOpenState) {
            tuneBtn.setColorFilter(colors.onPrimaryContainer)
            tuneBg.setColor(colors.primaryContainer)
        } else {
            tuneBtn.setColorFilter(neutralFg)
            tuneBg.setColor(neutralBg)
        }

        closeBg.setColor(ColorUtils.setAlphaComponent(colors.errorContainer, 110))
        closeBtn.setColorFilter(colors.error)
    }

    private fun statusLedColor(): Int = when {
        engineModeState == TouchMuxUserService.MODE_NATIVE_MUX -> colors.activeRunningGreen
        engineModeState == TouchMuxUserService.MODE_SHIZUKU_FALLBACK -> colors.primary
        else -> colors.tertiary
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startLpX = currentLpX
                startLpY = currentLpY
                isDragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (hypot(dx, dy) >= touchSlop) {
                    isDragging = true
                    listener.onDragStart()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startLpX = currentLpX
                startLpY = currentLpY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx, dy) >= touchSlop) {
                    isDragging = true
                    listener.onDragStart()
                }
                if (isDragging) {
                    val nx = (startLpX + dx).roundToInt()
                    val ny = (startLpY + dy).roundToInt()
                    currentLpX = nx
                    currentLpY = ny
                    listener.onDragMove(nx, ny)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener.onDragEnd()
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Circular 1-tap preset switch button displaying subtle cycle arcs + active scheme index (1..N).
     */
    private inner class PresetSwitchButtonView(context: Context) : View(context) {
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.6f * density
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f * density
        }
        private val arcRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val r = 11.5f * density
            val fg = if (totalPresetCount > 1) colors.onSecondaryContainer else colors.onSurfaceVariant

            arcPaint.color = ColorUtils.setAlphaComponent(fg, 200)
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            // Two subtle symmetric arcs framing the preset number
            canvas.drawArc(arcRect, -55f, 110f, false, arcPaint)
            canvas.drawArc(arcRect, 125f, 110f, false, arcPaint)

            labelPaint.color = fg
            val fm = labelPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText((activePresetIndex + 1).toString(), cx, textY, labelPaint)
        }
    }

    /**
     * Top end-cap: live TouchMux status LED + horizontal dock chevron (< or >).
     * Visually symmetric with ExpandToolsFooterView at the bottom cap.
     */
    private inner class ExpandedHeaderView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ledHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.9f * density
        }
        private val bgRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            bgPaint.color = ColorUtils.setAlphaComponent(colors.surfaceContainerHighest, 185)
            bgRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(bgRect, h * 0.5f, h * 0.5f, bgPaint)

            val ledColor = statusLedColor()
            val cy = h * 0.5f
            val ledX = if (dockRightState) w * 0.30f else w * 0.70f
            val chevronX = if (dockRightState) w * 0.68f else w * 0.32f

            if (runningState) {
                ledHaloPaint.color = ColorUtils.setAlphaComponent(ledColor, 85)
                canvas.drawCircle(ledX, cy, 6.2f * density, ledHaloPaint)
            }
            ledPaint.color = ledColor
            canvas.drawCircle(ledX, cy, 3.6f * density, ledPaint)

            // Horizontal chevron pointing toward active dock edge (> on right, < on left)
            strokePaint.color = colors.onSurfaceVariant
            val aw = 3.5f * density
            val ah = 4.6f * density
            if (dockRightState) {
                canvas.drawLine(chevronX - aw * 0.6f, cy - ah, chevronX + aw * 0.5f, cy, strokePaint)
                canvas.drawLine(chevronX - aw * 0.6f, cy + ah, chevronX + aw * 0.5f, cy, strokePaint)
            } else {
                canvas.drawLine(chevronX + aw * 0.6f, cy - ah, chevronX - aw * 0.5f, cy, strokePaint)
                canvas.drawLine(chevronX + aw * 0.6f, cy + ah, chevronX - aw * 0.5f, cy, strokePaint)
            }
        }
    }

    /**
     * Bottom end-cap symmetric to ExpandedHeaderView:
     * Renders the exact top chevron rotated 90 degrees clockwise (v to expand tools, ^ to fold).
     */
    private inner class ExpandToolsFooterView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.9f * density
        }
        private val bgRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            bgPaint.color = ColorUtils.setAlphaComponent(
                if (isToolsExpandedState) colors.primaryContainer else colors.surfaceContainerHighest,
                if (isToolsExpandedState) 210 else 185
            )
            bgRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(bgRect, h * 0.5f, h * 0.5f, bgPaint)

            strokePaint.color = if (isToolsExpandedState) colors.onPrimaryContainer else colors.onSurfaceVariant
            val cx = w * 0.5f
            val cy = h * 0.5f
            // Exact 90-degree rotation of the top horizontal chevron (aw <-> ah)
            val halfW = 4.6f * density
            val depthH = 3.5f * density
            if (isToolsExpandedState) {
                // Upward chevron (^) to fold secondary tools back
                canvas.drawLine(cx - halfW, cy + depthH * 0.55f, cx, cy - depthH * 0.55f, strokePaint)
                canvas.drawLine(cx + halfW, cy + depthH * 0.55f, cx, cy - depthH * 0.55f, strokePaint)
            } else {
                // Downward chevron (v) = horizontal > rotated 90 degrees
                canvas.drawLine(cx - halfW, cy - depthH * 0.55f, cx, cy + depthH * 0.55f, strokePaint)
                canvas.drawLine(cx + halfW, cy - depthH * 0.55f, cx, cy + depthH * 0.55f, strokePaint)
            }
        }
    }

    /**
     * Ultra-compact edge-docked capsule visible when edge-collapsed.
     */
    private inner class CollapsedEdgePillView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.9f * density
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val inset = 0.8f * density
            val r = 16f * density
            val radii = if (dockRightState) {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            } else {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }

            path.reset()
            rect.set(
                if (dockRightState) inset else -inset,
                inset,
                if (dockRightState) w + inset else w - inset,
                h - inset
            )
            path.addRoundRect(rect, radii, Path.Direction.CW)

            fillPaint.color = colors.surfaceContainerHigh
            canvas.drawPath(path, fillPaint)

            borderPaint.color = colors.outlineVariant
            canvas.drawPath(path, borderPaint)

            val cx = w * 0.5f
            val ledY = h * 0.34f
            val ledColor = statusLedColor()
            if (runningState) {
                haloPaint.color = ColorUtils.setAlphaComponent(ledColor, 95)
                canvas.drawCircle(cx, ledY, 7.2f * density, haloPaint)
            }
            ledPaint.color = ledColor
            canvas.drawCircle(cx, ledY, 4.0f * density, ledPaint)

            // Expand chevron pointing inward from the docked edge
            chevronPaint.color = colors.onSurface
            val chevY = h * 0.66f
            val aw = 3.5f * density
            val ah = 4.6f * density
            if (dockRightState) {
                canvas.drawLine(cx + aw * 0.5f, chevY - ah, cx - aw * 0.6f, chevY, chevronPaint)
                canvas.drawLine(cx + aw * 0.5f, chevY + ah, cx - aw * 0.6f, chevY, chevronPaint)
            } else {
                canvas.drawLine(cx - aw * 0.5f, chevY - ah, cx + aw * 0.6f, chevY, chevronPaint)
                canvas.drawLine(cx - aw * 0.5f, chevY + ah, cx + aw * 0.6f, chevY, chevronPaint)
            }
        }
    }
}
