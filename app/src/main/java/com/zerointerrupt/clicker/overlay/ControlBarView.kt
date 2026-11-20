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
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SoundEffectConstants
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
 *    Unfolds +, -, Lock, Record, Close above the symmetric collapse tools chevron (^).
 * 3. Edge-docked collapsed state (isCollapsed = true):
 *    Ultra-slim 20dp x 80dp edge-docked tab with live TouchMux LED + inward chevron.
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
        fun onStartMacroRecord()
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
    private val edgeMarginPx = (12f * density).roundToInt()
    private val morphHostWidthPx = expandedWidthPx + edgeMarginPx
    private val btnSizePx = (38f * density).roundToInt()
    // Outer radius = 26dp semicircle, padPx = 5dp -> cap width = 42dp -> concentric cap radius = 21dp.
    private val capHeightPx = (34f * density).roundToInt()
    private val padPx = (5f * density).roundToInt()
    private val gapPx = (4f * density).roundToInt()

    // Top cap + Play + Switch + Bottom cap + 3 gaps + outer vertical padding
    private val compactBarHeightPx = padPx * 2 + capHeightPx * 2 + btnSizePx * 2 + gapPx * 3
    // Unfolded height includes 5 secondary tool buttons (+, -, lock, record, close) each + gapPx
    private val fullBarHeightPx = compactBarHeightPx + (btnSizePx + gapPx) * 5

    private val collapsedWidthPx = (20f * density).roundToInt()
    private val collapsedHeightPx = (80f * density).roundToInt()

    private var colors: OverlayThemeColors = initialColors
    private var isCollapsedState: Boolean = false
    private var isToolsExpandedState: Boolean = false
    private var dockRightState: Boolean = false
    private var runningState: Boolean = false
    private var lockedState: Boolean = false
    private var engineModeState: Int = TouchMuxUserService.MODE_IDLE
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
    private val recordBtn: ImageView
    private val recordBg = ovalGradient()
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
            setRobustClickListener(recoverWhenIdle = false) { listener.onCollapseRequested() }
        }
        expandedContainer.addView(
            headerCollapseView,
            LinearLayout.LayoutParams(expandedWidthPx - padPx * 2, capHeightPx).apply {
                bottomMargin = gapPx
            }
        )

        playStopBtn = createActionButton(R.drawable.ic_play, playStopBg, recoverWhenIdle = true) { listener.onTogglePlayStop() }
        expandedContainer.addView(
            playStopBtn,
            LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                bottomMargin = gapPx
            }
        )

        switchPresetBtn = PresetSwitchButtonView(context).apply {
            isClickable = true
            background = initialColors.createRippleDrawable(switchPresetBg)
            setRobustClickListener(recoverWhenIdle = false) { listener.onSwitchPreset() }
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
        recordBtn = createActionButton(R.drawable.ic_record, recordBg) { listener.onStartMacroRecord() }
        closeBtn = createActionButton(R.drawable.ic_close, closeBg) { listener.onCloseOverlay() }

        listOf(addBtn, removeBtn, lockBtn, recordBtn, closeBtn).forEach { btn ->
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
            setRobustClickListener(recoverWhenIdle = false) { listener.onToggleToolsExpanded() }
        }
        expandedContainer.addView(
            footerExpandView,
            LinearLayout.LayoutParams(expandedWidthPx - padPx * 2, capHeightPx)
        )

        collapsedPillView = CollapsedEdgePillView(context).apply {
            isClickable = true
            visibility = View.GONE
            setRobustClickListener(recoverWhenIdle = true) { listener.onExpandRequested() }
        }

        addView(
            expandedContainer,
            LayoutParams(expandedWidthPx, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )
        addView(
            collapsedPillView,
            LayoutParams(collapsedWidthPx, collapsedHeightPx, Gravity.TOP or Gravity.START)
        )

        applyThemeColors(initialColors)
    }

    private var hasActiveTouch = false
    private var externalCancelInProgress = false
    private var pendingTouchIdleCallback: (() -> Unit)? = null

    fun hasActiveTouchGesture(): Boolean = hasActiveTouch

    fun runWhenTouchIdle(onIdle: () -> Unit) {
        if (!hasActiveTouch) {
            onIdle()
            return
        }
        pendingTouchIdleCallback = onIdle
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasActiveTouch = true
                externalCancelInProgress = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                externalCancelInProgress =
                    (ev.actionMasked == MotionEvent.ACTION_CANCEL) && !isDragging
                val handled = try {
                    super.dispatchTouchEvent(ev)
                } finally {
                    externalCancelInProgress = false
                }
                hasActiveTouch = false
                val idleCallback = pendingTouchIdleCallback
                pendingTouchIdleCallback = null
                idleCallback?.invoke()
                return handled
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setRobustClickListener(
        recoverWhenIdle: Boolean = false,
        onClick: () -> Unit
    ) {
        var downTimeMs = 0L
        var downRawX = 0f
        var downRawY = 0f
        var isDown = false
        var movedBeyondSlop = false

        setOnClickListener { onClick() }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTimeMs = SystemClock.uptimeMillis()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDown = true
                    movedBeyondSlop = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging || hypot(event.rawX - downRawX, event.rawY - downRawY) >= touchSlop) {
                        movedBeyondSlop = true
                        isDown = false
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    isDown = false
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasDown = isDown
                    val wasMoved = movedBeyondSlop
                    isDown = false
                    movedBeyondSlop = false
                    if (wasDown &&
                        !wasMoved &&
                        !isDragging &&
                        externalCancelInProgress &&
                        (recoverWhenIdle || runningState) &&
                        v.isAttachedToWindow &&
                        v.isShown &&
                        this@ControlBarView.alpha > 0.1f
                    ) {
                        val elapsedMs = SystemClock.uptimeMillis() - downTimeMs
                        val hasValidCancelCoords = (event.rawX != 0f || event.rawY != 0f)
                        val cancelDistOk = !hasValidCancelCoords ||
                            hypot(event.rawX - downRawX, event.rawY - downRawY) < touchSlop * 2f
                        if (elapsedMs in 0L..600L && cancelDistOk) {
                            v.playSoundEffect(SoundEffectConstants.CLICK)
                            onClick()
                        }
                    }
                    // Return false so View.onTouchEvent(ACTION_CANCEL) still clears pressed/ripple cleanly
                    false
                }
                else -> false
            }
        }
    }

    private fun ovalGradient(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
    }

    private fun createActionButton(
        iconRes: Int,
        bgDrawable: GradientDrawable,
        recoverWhenIdle: Boolean = false,
        onClick: () -> Unit
    ): ImageView {
        val iv = ImageView(context)
        val iconPad = (9f * density).roundToInt()
        iv.setPadding(iconPad, iconPad, iconPad, iconPad)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
        iv.isClickable = true
        iv.background = colors.createRippleDrawable(bgDrawable)
        iv.setRobustClickListener(recoverWhenIdle = recoverWhenIdle, onClick = onClick)
        return iv
    }

    fun applyThemeColors(newColors: OverlayThemeColors) {
        colors = newColors
        val outerRadiusPx = expandedWidthPx * 0.5f
        expandedContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = outerRadiusPx
            setColor(newColors.surfaceContainerHigh)
            setStroke((1f * density).roundToInt().coerceAtLeast(1), newColors.outlineVariant)
        }

        val rippleTint = ColorStateList.valueOf(newColors.ripple)
        listOf(playStopBtn, switchPresetBtn, addBtn, removeBtn, lockBtn, recordBtn, closeBtn).forEach { btn ->
            (btn.background as? RippleDrawable)?.setColor(rippleTint)
        }

        val capW = (expandedWidthPx - padPx * 2).toFloat()
        val capH = capHeightPx.toFloat()
        val outerCapR = capW * 0.5f
        val innerCapR = (capH - outerCapR).coerceAtLeast(8f * density)
        val topCapRadii = floatArrayOf(outerCapR, outerCapR, outerCapR, outerCapR, innerCapR, innerCapR, innerCapR, innerCapR)
        val bottomCapRadii = floatArrayOf(innerCapR, innerCapR, innerCapR, innerCapR, outerCapR, outerCapR, outerCapR, outerCapR)
        headerCollapseView.foreground = newColors.createRippleDrawable(null, maskCornerRadii = topCapRadii)
        footerExpandView.foreground = newColors.createRippleDrawable(null, maskCornerRadii = bottomCapRadii)
        updateCollapsedPillForeground()

        redrawButtonStates()
        headerCollapseView.invalidate()
        switchPresetBtn.invalidate()
        footerExpandView.invalidate()
        collapsedPillView.invalidate()
    }

    private fun updateCollapsedPillForeground() {
        val r = 14f * density
        val radii = if (dockRightState) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        collapsedPillView.foreground = colors.createRippleDrawable(null, maskCornerRadii = radii)
    }

    private fun updateCollapsedPillPlacement(dockRight: Boolean, topMarginPx: Int) {
        val lp = collapsedPillView.layoutParams as? LayoutParams ?: return
        val desiredGravity = Gravity.TOP or if (dockRight) Gravity.END else Gravity.START
        val clampedTop = topMarginPx.coerceAtLeast(0)
        if (lp.gravity != desiredGravity || lp.topMargin != clampedTop) {
            lp.gravity = desiredGravity
            lp.topMargin = clampedTop
            collapsedPillView.layoutParams = lp
        }
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

    fun getEdgeMarginPx(): Int = edgeMarginPx

    fun getMorphHostWidthPx(): Int = morphHostWidthPx

    fun getExpandedBarHeightPx(): Int = if (isToolsExpandedState) fullBarHeightPx else compactBarHeightPx

    fun getCollapsedWidthPx(): Int = collapsedWidthPx

    fun getCollapsedHeightPx(): Int = collapsedHeightPx

    fun isToolsExpanded(): Boolean = isToolsExpandedState

    fun setToolsExpanded(expanded: Boolean) {
        isToolsExpandedState = expanded
        secondaryToolsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        footerExpandView.invalidate()
    }

    private fun linearRamp(value: Float, edge0: Float, edge1: Float): Float =
        ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)

    fun prepareMorphLayout(
        dockRight: Boolean,
        pillTopMarginPx: Int,
        initiallyCollapsed: Boolean
    ) {
        isCollapsedState = false
        val dockChanged = dockRightState != dockRight
        dockRightState = dockRight
        if (dockChanged) {
            updateCollapsedPillForeground()
        }
        updateCollapsedPillPlacement(dockRight, pillTopMarginPx)
        expandedContainer.visibility = View.VISIBLE
        collapsedPillView.visibility = View.VISIBLE
        applyMorphProgress(if (initiallyCollapsed) 0f else 1f, dockRight, pillTopMarginPx)
        if (dockChanged) {
            headerCollapseView.invalidate()
            collapsedPillView.invalidate()
        }
    }

    /**
     * Pure local GPU transform inside the locked morphHostWidthPx (expandedWidthPx + edgeMarginPx)
     * host window: zero WindowManager IPC calls during animation, strictly monotonic, 100% mirror-symmetric.
     */
    fun applyMorphProgress(progress: Float, dockRight: Boolean, pillTopMarginPx: Int) {
        val p = progress.coerceIn(0f, 1f)
        val expandedH = getExpandedBarHeightPx().toFloat().coerceAtLeast(1f)
        val pillCenterY = (pillTopMarginPx + collapsedHeightPx * 0.5f).coerceIn(0f, expandedH)
        val sign = if (dockRight) -1f else 1f

        // Direct linear-in-progress mapping so outer M3 velocity curve is never double-eased or stalled mid-flight.
        expandedContainer.translationX = if (dockRight) {
            edgeMarginPx * (1f - p)
        } else {
            edgeMarginPx * p
        }
        expandedContainer.pivotX = if (dockRight) expandedWidthPx.toFloat() else 0f
        expandedContainer.pivotY = pillCenterY

        expandedContainer.scaleX = 0.42f + 0.58f * p
        expandedContainer.scaleY = 0.52f + 0.48f * p
        expandedContainer.alpha = linearRamp(p, 0.06f, 0.52f)

        val peelT = linearRamp(p, 0.0f, 0.45f)
        collapsedPillView.pivotX = if (dockRight) collapsedWidthPx.toFloat() else 0f
        collapsedPillView.pivotY = collapsedHeightPx * 0.5f
        collapsedPillView.translationX = sign * (edgeMarginPx * 0.36f) * peelT
        collapsedPillView.scaleX = 1f
        collapsedPillView.scaleY = 1f
        collapsedPillView.alpha = 1f - linearRamp(p, 0.12f, 0.48f)
        collapsedPillView.setExpandMorph(peelT)
    }

    fun setCollapsed(collapsed: Boolean, dockRight: Boolean) {
        isCollapsedState = collapsed
        val dockChanged = dockRightState != dockRight
        dockRightState = dockRight
        if (dockChanged) {
            updateCollapsedPillForeground()
        }
        updateCollapsedPillPlacement(dockRight, topMarginPx = 0)
        expandedContainer.visibility = if (collapsed) View.GONE else View.VISIBLE
        expandedContainer.translationX = 0f
        expandedContainer.alpha = 1f
        expandedContainer.scaleX = 1f
        expandedContainer.scaleY = 1f
        collapsedPillView.visibility = if (collapsed) View.VISIBLE else View.GONE
        collapsedPillView.translationX = 0f
        collapsedPillView.alpha = 1f
        collapsedPillView.scaleX = 1f
        collapsedPillView.scaleY = 1f
        collapsedPillView.setExpandMorph(0f)
        headerCollapseView.invalidate()
        footerExpandView.invalidate()
        collapsedPillView.invalidate()
    }

    fun setDockRight(dockRight: Boolean) {
        if (dockRightState != dockRight) {
            dockRightState = dockRight
            updateCollapsedPillForeground()
            updateCollapsedPillPlacement(
                dockRight,
                if (isCollapsedState) 0 else (collapsedPillView.layoutParams as? LayoutParams)?.topMargin ?: 0
            )
            headerCollapseView.invalidate()
            collapsedPillView.invalidate()
        }
    }

    fun updateState(
        isRunning: Boolean,
        isLocked: Boolean,
        engineMode: Int,
        presetIndex: Int,
        presetCount: Int
    ) {
        runningState = isRunning
        lockedState = isLocked
        engineModeState = engineMode
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

        recordBg.setColor(ColorUtils.setAlphaComponent(colors.tertiaryContainer, 220))
        recordBtn.setColorFilter(colors.onTertiaryContainer)

        closeBg.setColor(ColorUtils.setAlphaComponent(colors.errorContainer, 110))
        closeBtn.setColorFilter(colors.error)
    }

    private fun statusLedColor(): Int = when {
        TouchMuxUserService.isNativeMode(engineModeState) -> colors.activeRunningGreen
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
        private val bgPath = Path()
        private val bgRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val outerR = w * 0.5f
            val innerR = (h - outerR).coerceAtLeast(8f * density)
            val radii = floatArrayOf(outerR, outerR, outerR, outerR, innerR, innerR, innerR, innerR)
            bgPaint.color = ColorUtils.setAlphaComponent(colors.surfaceContainerHighest, 185)
            bgRect.set(0f, 0f, w, h)
            bgPath.reset()
            bgPath.addRoundRect(bgRect, radii, Path.Direction.CW)
            canvas.drawPath(bgPath, bgPaint)

            val ledColor = statusLedColor()
            val cy = h * 0.53f
            val ledX = if (dockRightState) w * 0.31f else w * 0.69f
            val chevronX = if (dockRightState) w * 0.67f else w * 0.33f

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
        private val bgPath = Path()
        private val bgRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val outerR = w * 0.5f
            val innerR = (h - outerR).coerceAtLeast(8f * density)
            val radii = floatArrayOf(innerR, innerR, innerR, innerR, outerR, outerR, outerR, outerR)
            bgPaint.color = ColorUtils.setAlphaComponent(
                if (isToolsExpandedState) colors.primaryContainer else colors.surfaceContainerHighest,
                if (isToolsExpandedState) 210 else 185
            )
            bgRect.set(0f, 0f, w, h)
            bgPath.reset()
            bgPath.addRoundRect(bgRect, radii, Path.Direction.CW)
            canvas.drawPath(bgPath, bgPaint)

            strokePaint.color = if (isToolsExpandedState) colors.onPrimaryContainer else colors.onSurfaceVariant
            val cx = w * 0.5f
            val cy = h * 0.47f
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
     * Sleek MP4-inspired Material 3 edge dock capsule:
     * Flush against the docked screen boundary with smooth rounded inner caps and subtle Monet fill.
     */
    private inner class CollapsedEdgePillView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.85f * density
        }
        private val fillPath = Path()
        private var expandMorph: Float = 0f

        fun setExpandMorph(morph: Float) {
            val clamped = morph.coerceIn(0f, 1f)
            if (expandMorph != clamped) {
                expandMorph = clamped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val sign = if (dockRightState) -1f else 1f
            val wallX = if (dockRightState) w else 0f
            val visibleW = w
            val innerX = wallX + sign * visibleW
            val outerCornerR = 14f * density
            val wallCornerR = (4f * expandMorph) * density
            val vPad = 0f

            val leftX = minOf(wallX, innerX)
            val rightX = maxOf(wallX, innerX)
            val radii = if (dockRightState) {
                floatArrayOf(
                    outerCornerR, outerCornerR,
                    wallCornerR, wallCornerR,
                    wallCornerR, wallCornerR,
                    outerCornerR, outerCornerR
                )
            } else {
                floatArrayOf(
                    wallCornerR, wallCornerR,
                    outerCornerR, outerCornerR,
                    outerCornerR, outerCornerR,
                    wallCornerR, wallCornerR
                )
            }

            fillPath.reset()
            fillPath.addRoundRect(leftX, vPad, rightX, h - vPad, radii, Path.Direction.CW)
            fillPaint.color = colors.secondaryContainer
            canvas.drawPath(fillPath, fillPaint)

            val cx = wallX + sign * (visibleW * 0.50f)
            val ledY = h * 0.36f
            val ledColor = statusLedColor()
            if (runningState) {
                haloPaint.color = ColorUtils.setAlphaComponent(ledColor, 95)
                canvas.drawCircle(cx, ledY, 5.5f * density, haloPaint)
            }
            ledPaint.color = ledColor
            canvas.drawCircle(cx, ledY, 3.0f * density, ledPaint)

            chevronPaint.color = colors.onSecondaryContainer
            val chevY = h * 0.60f
            val aw = 2.8f * density
            val ah = 4.0f * density
            val tipX = cx + sign * aw * 0.55f
            val tailX = cx - sign * aw * 0.45f
            canvas.drawLine(tailX, chevY - ah, tipX, chevY, chevronPaint)
            canvas.drawLine(tailX, chevY + ah, tipX, chevY, chevronPaint)
        }
    }
}
