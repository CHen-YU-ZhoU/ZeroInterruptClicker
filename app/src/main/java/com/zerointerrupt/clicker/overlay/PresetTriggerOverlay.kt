package com.zerointerrupt.clicker.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.PresetQuickTriggerConfig
import com.zerointerrupt.clicker.shizuku.ShizukuController
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Manages standalone per-preset floating quick-trigger bubbles.
 * - Single tap toggles execution of that specific preset (start / stop).
 * - Drag repositions freely and persists normalized coordinates.
 * - While dragging, an Android-native-style circular "×" dismiss target rises from the
 *   lower-center of the screen with magnetic pull; dropping into it destroys the bubble.
 */
class PresetTriggerOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private var colors: OverlayThemeColors,
    private val onTapPreset: (presetId: String) -> Unit,
    private val onDestroyedPreset: (presetId: String) -> Unit
) {
    private data class Entry(
        val presetId: String,
        var presetName: String,
        var config: PresetQuickTriggerConfig,
        val view: PresetTriggerBubbleView,
        val lp: WindowManager.LayoutParams
    )

    private val density = context.resources.displayMetrics.density
    private val entries = LinkedHashMap<String, Entry>()
    private var runningPresetId: String? = null

    private var dismissOverlayView: BottomMagneticDismissView? = null
    private var dismissOverlayLp: WindowManager.LayoutParams? = null
    private var dismissCenterX: Float = 0f
    private var dismissCenterY: Float = 0f

    fun hasAny(): Boolean = entries.isNotEmpty()

    fun activePresetIds(): Set<String> = entries.keys.toSet()

    fun showOrUpdate(preset: ClickPreset, config: PresetQuickTriggerConfig) {
        val existing = entries[preset.id]
        if (existing != null) {
            existing.presetName = preset.name
            existing.config = config
            existing.view.updateModel(
                name = preset.name,
                config = config,
                colors = colors,
                isRunning = (runningPresetId == preset.id)
            )
            repositionEntry(existing)
            return
        }

        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val view = PresetTriggerBubbleView(
            context = context,
            initialName = preset.name,
            initialConfig = config,
            initialColors = colors,
            isRunningInitial = (runningPresetId == preset.id),
            onTap = { onTapPreset(preset.id) },
            onDragStarted = { showDismissTarget() },
            onDragMove = { rawX, rawY, prevArmed ->
                handleDragMove(preset.id, rawX, rawY, prevArmed)
            },
            onDragEnded = { finalX, finalY, shouldDestroy ->
                if (shouldDestroy) {
                    destroyWithAnimation(preset.id)
                } else {
                    hideDismissTarget(animated = true)
                    persistEntryPosition(preset.id, finalX, finalY)
                }
            }
        )

        val estW = view.estimatedWidthPx()
        val estH = view.estimatedHeightPx()
        val lp = createWindowParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        ).apply {
            x = (metrics.widthPx * config.normX - estW * 0.5f)
                .roundToInt()
                .coerceIn(0, (metrics.widthPx - estW).coerceAtLeast(0))
            y = (metrics.heightPx * config.normY - estH * 0.5f)
                .roundToInt()
                .coerceIn(0, (metrics.heightPx - estH).coerceAtLeast(0))
        }
        view.syncWindowPosition(lp.x, lp.y)

        try {
            windowManager.addView(view, lp)
            entries[preset.id] = Entry(
                presetId = preset.id,
                presetName = preset.name,
                config = config,
                view = view,
                lp = lp
            )
            view.scaleX = 0.72f
            view.scaleY = 0.72f
            view.alpha = 0f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (_: Throwable) {
        }
    }

    fun hide(presetId: String) {
        val entry = entries.remove(presetId) ?: return
        removeViewSafely(entry.view)
        if (entries.isEmpty()) {
            hideDismissTarget(animated = false)
        }
    }

    fun setRunningPreset(presetId: String?) {
        runningPresetId = presetId
        for ((id, entry) in entries) {
            entry.view.setRunning(id == presetId)
        }
    }

    fun updateTheme(newColors: OverlayThemeColors) {
        colors = newColors
        for ((id, entry) in entries) {
            entry.view.updateModel(
                name = entry.presetName,
                config = entry.config,
                colors = newColors,
                isRunning = (id == runningPresetId)
            )
        }
        dismissOverlayView?.updateColors(newColors)
    }

    fun syncPresetsAndGeometry(allPresets: List<ClickPreset>) {
        val byId = allPresets.associateBy { it.id }
        val toRemove = mutableListOf<String>()
        for ((id, entry) in entries) {
            val preset = byId[id]
            if (preset == null) {
                toRemove += id
            } else {
                if (entry.presetName != preset.name) {
                    entry.presetName = preset.name
                    entry.view.updateModel(
                        name = preset.name,
                        config = entry.config,
                        colors = colors,
                        isRunning = (id == runningPresetId)
                    )
                }
                repositionEntry(entry)
            }
        }
        toRemove.forEach { id ->
            hide(id)
            onDestroyedPreset(id)
        }
    }

    fun dismissAll() {
        hideDismissTarget(animated = false)
        val copy = entries.values.toList()
        entries.clear()
        copy.forEach { removeViewSafely(it.view) }
        runningPresetId = null
    }

    private fun repositionEntry(entry: Entry) {
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val w = if (entry.view.width > 0) entry.view.width else entry.view.estimatedWidthPx()
        val h = if (entry.view.height > 0) entry.view.height else entry.view.estimatedHeightPx()
        entry.lp.x = (metrics.widthPx * entry.config.normX - w * 0.5f)
            .roundToInt()
            .coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
        entry.lp.y = (metrics.heightPx * entry.config.normY - h * 0.5f)
            .roundToInt()
            .coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
        entry.view.syncWindowPosition(entry.lp.x, entry.lp.y)
        try {
            windowManager.updateViewLayout(entry.view, entry.lp)
        } catch (_: Throwable) {
        }
    }

    /**
     * Updates bubble position with Android SystemUI MagnetizedObject-style magnetic pull
     * into the lower-center circular "×" dismiss target. Returns whether currently armed for dismiss.
     */
    private fun handleDragMove(
        presetId: String,
        rawLpX: Int,
        rawLpY: Int,
        currentlyArmed: Boolean
    ): Boolean {
        val entry = entries[presetId] ?: return false
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val w = if (entry.view.width > 0) entry.view.width else entry.view.estimatedWidthPx()
        val h = if (entry.view.height > 0) entry.view.height else entry.view.estimatedHeightPx()

        val clampedX = rawLpX.coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
        val clampedY = rawLpY.coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
        val bubbleCenterX = clampedX + w * 0.5f
        val bubbleCenterY = clampedY + h * 0.5f

        val dxToTarget = bubbleCenterX - dismissCenterX
        val dyToTarget = bubbleCenterY - dismissCenterY
        val dist = hypot(dxToTarget.toDouble(), dyToTarget.toDouble()).toFloat()

        // Hysteresis prevents flicker at the boundary of the magnetic capture ring
        val captureRadius = 74f * density
        val releaseRadius = 96f * density
        val nowArmed = if (currentlyArmed) dist <= releaseRadius else dist <= captureRadius

        if (nowArmed) {
            // Pull bubble smoothly onto the "×" center with gentle 14% elastic follow toward finger
            val magnetCenterX = dismissCenterX + dxToTarget * 0.14f
            val magnetCenterY = dismissCenterY + dyToTarget * 0.14f
            entry.lp.x = (magnetCenterX - w * 0.5f)
                .roundToInt()
                .coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
            entry.lp.y = (magnetCenterY - h * 0.5f)
                .roundToInt()
                .coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
            dismissOverlayView?.setMagnetState(
                armed = true,
                pullOffsetX = (dxToTarget * 0.12f).coerceIn(-14f * density, 14f * density),
                pullOffsetY = (dyToTarget * 0.12f).coerceIn(-14f * density, 14f * density)
            )
        } else {
            entry.lp.x = clampedX
            entry.lp.y = clampedY
            dismissOverlayView?.setMagnetState(armed = false, pullOffsetX = 0f, pullOffsetY = 0f)
        }

        entry.view.syncWindowPosition(entry.lp.x, entry.lp.y)
        try {
            windowManager.updateViewLayout(entry.view, entry.lp)
        } catch (_: Throwable) {
        }
        return nowArmed
    }

    private fun persistEntryPosition(presetId: String, lpX: Int, lpY: Int) {
        val entry = entries[presetId] ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val w = if (entry.view.width > 0) entry.view.width else entry.view.estimatedWidthPx()
        val h = if (entry.view.height > 0) entry.view.height else entry.view.estimatedHeightPx()
        val cx = (lpX + w * 0.5f) / metrics.widthPx.coerceAtLeast(1).toFloat()
        val cy = (lpY + h * 0.5f) / metrics.heightPx.coerceAtLeast(1).toFloat()
        val updated = entry.config.copy(
            normX = cx.coerceIn(0.04f, 0.96f),
            normY = cy.coerceIn(0.06f, 0.94f)
        )
        entry.config = updated
        ClickConfigStore.saveQuickTriggerConfig(context, updated)
    }

    private fun destroyWithAnimation(presetId: String) {
        val entry = entries.remove(presetId) ?: return
        entry.view.animate()
            .scaleX(0.22f)
            .scaleY(0.22f)
            .alpha(0f)
            .setDuration(155L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeViewSafely(entry.view)
                }
            })
            .start()
        hideDismissTarget(animated = true)
        onDestroyedPreset(presetId)
    }

    private fun showDismissTarget() {
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val bottomMarginPx = (74f * density).roundToInt()
        val circleSizePx = (60f * density).roundToInt()
        dismissCenterX = metrics.widthPx * 0.5f
        dismissCenterY = metrics.heightPx - bottomMarginPx - circleSizePx * 0.5f

        val existing = dismissOverlayView
        if (existing != null) {
            existing.animateIn()
            return
        }

        val overlayHeightPx = (210f * density).roundToInt()
        val view = BottomMagneticDismissView(
            context = colors.themedContext,
            initialColors = colors,
            circleSizePx = circleSizePx,
            bottomMarginPx = bottomMarginPx
        )
        val lp = createWindowParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = overlayHeightPx,
            touchable = false
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        try {
            windowManager.addView(view, lp)
            dismissOverlayView = view
            dismissOverlayLp = lp
            view.animateIn()
        } catch (_: Throwable) {
        }
    }

    private fun hideDismissTarget(animated: Boolean) {
        val view = dismissOverlayView ?: return
        dismissOverlayView = null
        dismissOverlayLp = null
        if (!animated) {
            view.cancelAnimations()
            removeViewSafely(view)
            return
        }
        view.animateOut {
            removeViewSafely(view)
        }
    }

    private fun createWindowParams(
        width: Int,
        height: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun removeViewSafely(view: View?) {
        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
    }
}

/**
 * 1:1 Android SystemUI Bubbles / PiP lower-center circular "×" dismiss target with soft bottom
 * gradient scrim, spring rise entrance, and magnetic pull + scale highlight.
 */
@SuppressLint("ViewConstructor")
private class BottomMagneticDismissView(
    context: Context,
    initialColors: OverlayThemeColors,
    private val circleSizePx: Int,
    bottomMarginPx: Int
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val circleView: DismissCircleIconView
    private var colors: OverlayThemeColors = initialColors
    private var armed: Boolean = false

    private val riseSpring: SpringAnimation
    private val scaleXSpring: SpringAnimation
    private val scaleYSpring: SpringAnimation

    init {
        clipChildren = false
        clipToPadding = false
        alpha = 0f

        // Subtle Android-style bottom vignette scrim
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(if (initialColors.isDark) 108 else 70, 0, 0, 0)
            )
        )

        circleView = DismissCircleIconView(context, initialColors).apply {
            elevation = 10f * density
            translationY = 110f * density
        }
        val lp = LayoutParams(circleSizePx, circleSizePx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = bottomMarginPx
        }
        addView(circleView, lp)

        riseSpring = SpringAnimation(circleView, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
        }
        scaleXSpring = SpringAnimation(circleView, DynamicAnimation.SCALE_X, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }
        scaleYSpring = SpringAnimation(circleView, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }
    }

    fun updateColors(newColors: OverlayThemeColors) {
        colors = newColors
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(if (newColors.isDark) 108 else 70, 0, 0, 0)
            )
        )
        circleView.updateColors(newColors)
    }

    fun animateIn() {
        animate().cancel()
        animate()
            .alpha(1f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        riseSpring.animateToFinalPosition(0f)
    }

    fun setMagnetState(armed: Boolean, pullOffsetX: Float, pullOffsetY: Float) {
        if (this.armed != armed) {
            this.armed = armed
            circleView.setArmed(armed)
            val targetScale = if (armed) 1.16f else 1.0f
            scaleXSpring.animateToFinalPosition(targetScale)
            scaleYSpring.animateToFinalPosition(targetScale)
        }
        circleView.animate()
            .translationX(pullOffsetX)
            .setDuration(55L)
            .start()
        if (armed) {
            riseSpring.animateToFinalPosition(pullOffsetY)
        } else {
            riseSpring.animateToFinalPosition(0f)
        }
    }

    fun animateOut(onDone: () -> Unit) {
        riseSpring.cancel()
        scaleXSpring.cancel()
        scaleYSpring.cancel()
        circleView.animate()
            .translationY(110f * density)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(175L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        animate()
            .alpha(0f)
            .setDuration(175L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDone()
                }
            })
            .start()
    }

    fun cancelAnimations() {
        riseSpring.cancel()
        scaleXSpring.cancel()
        scaleYSpring.cancel()
        animate().cancel()
        circleView.animate().cancel()
    }
}

@SuppressLint("ViewConstructor")
private class DismissCircleIconView(
    context: Context,
    initialColors: OverlayThemeColors
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var colors: OverlayThemeColors = initialColors
    private var armed: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.3f * density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        refreshPaints()
    }

    fun updateColors(newColors: OverlayThemeColors) {
        colors = newColors
        refreshPaints()
        invalidate()
    }

    fun setArmed(armed: Boolean) {
        if (this.armed == armed) return
        this.armed = armed
        refreshPaints()
        invalidate()
    }

    private fun refreshPaints() {
        if (armed) {
            bgPaint.color = ColorUtils.setAlphaComponent(colors.error, 232)
            strokePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 90)
            crossPaint.color = Color.WHITE
        } else {
            // Frosted dark/tonal circle matching Android SystemUI DismissCircleView
            bgPaint.color = if (colors.isDark) {
                ColorUtils.setAlphaComponent(colors.surfaceContainerHighest, 230)
            } else {
                Color.argb(214, 34, 38, 44)
            }
            strokePaint.color = Color.argb(64, 255, 255, 255)
            crossPaint.color = Color.WHITE
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = (minOf(width, height) * 0.5f - strokePaint.strokeWidth).coerceAtLeast(4f)

        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        val arm = radius * 0.34f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint)
    }
}

@SuppressLint("ViewConstructor")
private class PresetTriggerBubbleView(
    context: Context,
    initialName: String,
    initialConfig: PresetQuickTriggerConfig,
    initialColors: OverlayThemeColors,
    isRunningInitial: Boolean,
    private val onTap: () -> Unit,
    private val onDragStarted: () -> Unit,
    private val onDragMove: (rawLpX: Int, rawLpY: Int, currentlyArmed: Boolean) -> Boolean,
    private val onDragEnded: (finalX: Int, finalY: Int, shouldDestroy: Boolean) -> Unit
) : FrameLayout(initialColors.themedContext) {

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(8f * density)
    private val contentRow: LinearLayout
    private val statusDot: View
    private val labelTv: TextView

    private var presetName: String = initialName
    private var config: PresetQuickTriggerConfig = initialConfig
    private var colors: OverlayThemeColors = initialColors
    private var isRunning: Boolean = isRunningInitial
    private var isDismissArmed: Boolean = false

    private var currentLpX: Int = 0
    private var currentLpY: Int = 0
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f
    private var startLpX: Int = 0
    private var startLpY: Int = 0
    private var isDragging: Boolean = false

    init {
        clipChildren = false
        clipToPadding = false
        elevation = 8f * density

        contentRow = LinearLayout(initialColors.themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        statusDot = View(initialColors.themedContext)
        labelTv = TextView(initialColors.themedContext).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            includeFontPadding = false
        }

        contentRow.addView(statusDot)
        contentRow.addView(labelTv)
        addView(contentRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        applyLayoutAndStyle()
    }

    fun syncWindowPosition(x: Int, y: Int) {
        currentLpX = x
        currentLpY = y
    }

    fun estimatedHeightPx(): Int = (config.sizeDp * density).roundToInt()

    fun estimatedWidthPx(): Int {
        val h = estimatedHeightPx()
        return if (config.shapeMode == PresetQuickTriggerConfig.SHAPE_CIRCLE) {
            h
        } else {
            (h * 2.2f).roundToInt().coerceIn(h, (132f * density).roundToInt())
        }
    }

    fun updateModel(
        name: String,
        config: PresetQuickTriggerConfig,
        colors: OverlayThemeColors,
        isRunning: Boolean
    ) {
        this.presetName = name
        this.config = config
        this.colors = colors
        this.isRunning = isRunning
        applyLayoutAndStyle()
    }

    fun setRunning(running: Boolean) {
        if (this.isRunning == running) return
        this.isRunning = running
        applyVisualState()
        animate()
            .scaleX(if (running) 1.06f else 1f)
            .scaleY(if (running) 1.06f else 1f)
            .setDuration(120L)
            .withEndAction {
                if (!isDismissArmed) {
                    animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
            }
            .start()
    }

    private fun applyLayoutAndStyle() {
        val hPx = (config.sizeDp * density).roundToInt()
        val isCircle = config.shapeMode == PresetQuickTriggerConfig.SHAPE_CIRCLE
        val textSp = (config.sizeDp * 0.285f).coerceIn(9.5f, 14.5f)
        val dotPx = (config.sizeDp * 0.17f * density).roundToInt().coerceIn((5f * density).roundToInt(), (9f * density).roundToInt())

        if (isCircle) {
            contentRow.layoutParams = LayoutParams(hPx, hPx, Gravity.CENTER)
            contentRow.setPadding((4f * density).roundToInt(), 0, (4f * density).roundToInt(), 0)
            statusDot.visibility = View.GONE
            labelTv.text = presetName.take(2)
            labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp * 0.92f)
            labelTv.maxWidth = (hPx - (8f * density).roundToInt()).coerceAtLeast(16)
        } else {
            val hPad = (config.sizeDp * 0.26f * density).roundToInt().coerceAtLeast((8f * density).roundToInt())
            contentRow.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, hPx, Gravity.CENTER)
            contentRow.setPadding(hPad, 0, hPad, 0)
            contentRow.minimumWidth = (hPx * 1.45f).roundToInt()
            statusDot.visibility = View.VISIBLE
            statusDot.layoutParams = LinearLayout.LayoutParams(dotPx, dotPx).apply {
                marginEnd = (5f * density).roundToInt()
            }
            labelTv.text = presetName
            labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            labelTv.maxWidth = (config.sizeDp * 2.9f * density).roundToInt().coerceIn((64f * density).roundToInt(), (132f * density).roundToInt())
        }

        applyVisualState()
        requestLayout()
    }

    private fun applyVisualState() {
        val hPx = (config.sizeDp * density).roundToInt()
        val cornerPx = when (config.shapeMode) {
            PresetQuickTriggerConfig.SHAPE_CIRCLE -> hPx * 0.5f
            PresetQuickTriggerConfig.SHAPE_SQUIRCLE -> hPx * 0.34f
            else -> hPx * 0.5f
        }

        val bgColor: Int
        val strokeColor: Int
        val strokeWidthPx: Int
        val fgColor: Int
        val dotColor: Int

        when {
            isDismissArmed -> {
                bgColor = ColorUtils.setAlphaComponent(colors.errorContainer, 240)
                strokeColor = colors.error
                strokeWidthPx = (1.6f * density).roundToInt().coerceAtLeast(2)
                fgColor = colors.onErrorContainer
                dotColor = colors.error
            }
            isRunning -> {
                bgColor = ColorUtils.compositeColors(
                    ColorUtils.setAlphaComponent(colors.activeRunningGreen, if (colors.isDark) 72 else 48),
                    ColorUtils.setAlphaComponent(colors.surfaceContainerHigh, 236)
                )
                strokeColor = colors.activeRunningGreen
                strokeWidthPx = (1.8f * density).roundToInt().coerceAtLeast(2)
                fgColor = colors.onSurface
                dotColor = colors.activeRunningGreen
            }
            else -> {
                bgColor = ColorUtils.setAlphaComponent(colors.surfaceContainerHigh, 230)
                strokeColor = colors.outlineVariant
                strokeWidthPx = (1.1f * density).roundToInt().coerceAtLeast(1)
                fgColor = colors.onSurface
                dotColor = colors.primary
            }
        }

        contentRow.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(bgColor)
            setStroke(strokeWidthPx, strokeColor)
        }
        labelTv.setTextColor(fgColor)
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
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
                isDismissArmed = false
                animate().scaleX(0.93f).scaleY(0.93f).setDuration(90L).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) >= touchSlop) {
                    isDragging = true
                    onDragStarted()
                }
                if (isDragging) {
                    val nextX = (startLpX + dx).roundToInt()
                    val nextY = (startLpY + dy).roundToInt()
                    val nowArmed = onDragMove(nextX, nextY, isDismissArmed)
                    if (nowArmed != isDismissArmed) {
                        isDismissArmed = nowArmed
                        applyVisualState()
                        if (nowArmed) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } else {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        animate()
                            .scaleX(if (nowArmed) 0.86f else 0.96f)
                            .scaleY(if (nowArmed) 0.86f else 0.96f)
                            .setDuration(100L)
                            .start()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!isDragging && !cancelled) {
                    animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap()
                } else if (isDragging) {
                    val shouldDestroy = !cancelled && isDismissArmed
                    if (shouldDestroy) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } else {
                        isDismissArmed = false
                        applyVisualState()
                        animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }
                    onDragEnded(currentLpX, currentLpY, shouldDestroy)
                } else {
                    isDismissArmed = false
                    applyVisualState()
                    animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
