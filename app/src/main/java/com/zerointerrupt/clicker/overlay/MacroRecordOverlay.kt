package com.zerointerrupt.clicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.MacroCompiledPlan
import com.zerointerrupt.clicker.model.MacroCompiler
import com.zerointerrupt.clicker.shizuku.ShizukuController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Self-contained overlay controller managing floating macro recording controls
 * (Ready -> Armed waiting for first touch -> Live Recording -> Result Preview Modal).
 */
class MacroRecordOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private var colors: OverlayThemeColors,
    private val onFinished: (savedPlan: MacroCompiledPlan?, openPresetsTab: Boolean) -> Unit
) {

    enum class Stage {
        READY,
        ARMED,
        RECORDING,
        PREVIEW
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stage: Stage = Stage.READY

    private var barView: RecordControlBarView? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var previewView: RecordPreviewOverlayView? = null

    private var activePlan: MacroCompiledPlan? = null
    private var lastElapsedMs: Long = 0L
    private var lastStrokes: Int = 0
    private var lastFingers: Int = 0
    private var pollingActive = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!pollingActive) return
            val self = this
            scope.launch(Dispatchers.IO) {
                val stats = ShizukuController.getTouchRecordingStats()
                val recState = stats.getOrNull(0)?.toInt() ?: 0
                val elapsedMs = stats.getOrNull(1) ?: 0L
                val strokes = (stats.getOrNull(2) ?: 0L).toInt()
                val fingers = (stats.getOrNull(3) ?: 0L).toInt()
                withContext(Dispatchers.Main) {
                    if (!pollingActive) return@withContext
                    if (stage == Stage.ARMED && recState == 2) {
                        stage = Stage.RECORDING
                    }
                    lastElapsedMs = elapsedMs
                    lastStrokes = strokes
                    lastFingers = fingers
                    barView?.renderState(stage, lastElapsedMs, lastStrokes, lastFingers)
                    mainHandler.postDelayed(self, 100L)
                }
            }
        }
    }

    fun start() {
        dismissAll()
        stage = Stage.READY
        lastElapsedMs = 0L
        lastStrokes = 0
        lastFingers = 0
        attachControlBar()
    }

    fun updateTheme(newColors: OverlayThemeColors) {
        colors = newColors
        barView?.applyColors(newColors)
    }

    fun isActive(): Boolean = barView != null || previewView != null

    fun dismissAll() {
        stopPolling()
        scope.launch(Dispatchers.IO) {
            ShizukuController.cancelTouchRecording()
        }
        removeBar()
        removePreview()
    }

    private fun attachControlBar() {
        removeBar()
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val view = RecordControlBarView(
            context = context,
            initialColors = colors,
            onPrimaryClick = { handlePrimaryAction() },
            onCancelClick = { handleCancel() },
            onDragMove = { nx, ny -> updateBarPosition(nx, ny) },
            onBoundsChanged = { syncExcludeRect() }
        )
        view.renderState(stage, lastElapsedMs, lastStrokes, lastFingers)

        val d = context.resources.displayMetrics.density
        view.elevation = 16f * d
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (20f * d).roundToInt()
            y = (metrics.heightPx * 0.14f).roundToInt().coerceAtLeast((56f * d).roundToInt())
        }
        windowManager.addView(view, lp)
        barView = view
        barLp = lp
    }

    private fun updateBarPosition(nx: Int, ny: Int) {
        val v = barView ?: return
        val lp = barLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val vw = v.width.coerceAtLeast((180f * context.resources.displayMetrics.density).roundToInt())
        val vh = v.height.coerceAtLeast((48f * context.resources.displayMetrics.density).roundToInt())
        lp.x = nx.coerceIn(0, (metrics.widthPx - vw).coerceAtLeast(0))
        lp.y = ny.coerceIn(0, (metrics.heightPx - vh).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
        syncExcludeRect()
    }

    private fun currentExcludeBounds(): IntArray {
        val v = barView
        val lp = barLp
        if (v == null || lp == null) return intArrayOf(0, 0, 0, 0)
        val w = if (v.width > 0) v.width else (260f * context.resources.displayMetrics.density).roundToInt()
        val h = if (v.height > 0) v.height else (64f * context.resources.displayMetrics.density).roundToInt()
        return intArrayOf(lp.x, lp.y, lp.x + w, lp.y + h)
    }

    private fun syncExcludeRect() {
        if (stage != Stage.ARMED && stage != Stage.RECORDING) return
        val b = currentExcludeBounds()
        scope.launch(Dispatchers.IO) {
            ShizukuController.updateRecordingExcludeRect(b[0], b[1], b[2], b[3])
        }
    }

    private fun handlePrimaryAction() {
        when (stage) {
            Stage.READY -> {
                if (!ShizukuController.isServiceConnected()) {
                    ShizukuController.evaluateShizukuState()
                    Toast.makeText(context, "请先在主界面授权并连接 Shizuku 服务", Toast.LENGTH_SHORT).show()
                    return
                }
                val b = currentExcludeBounds()
                scope.launch(Dispatchers.IO) {
                    val ok = ShizukuController.startTouchRecording(b[0], b[1], b[2], b[3])
                    withContext(Dispatchers.Main) {
                        if (barView == null) return@withContext
                        if (!ok) {
                            Toast.makeText(
                                context,
                                "无法打开物理触屏节点进行录制，请在实验室刷新硬件状态",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            stage = Stage.ARMED
                            lastElapsedMs = 0L
                            lastStrokes = 0
                            lastFingers = 0
                            barView?.renderState(stage, 0L, 0, 0)
                            startPolling()
                            syncExcludeRect()
                        }
                    }
                }
            }

            Stage.RECORDING -> {
                stopPolling()
                scope.launch(Dispatchers.IO) {
                    val json = ShizukuController.stopTouchRecording()
                    val presets = ClickConfigStore.loadPresets(context)
                    val settings = ClickConfigStore.loadSettings(context)
                    val metrics = ShizukuController.getDisplayScreenMetrics(context)
                    val compiled = MacroCompiler.compileFromJson(
                        rawJson = json,
                        existingPresets = presets,
                        fallbackScreenW = metrics.widthPx,
                        fallbackScreenH = metrics.heightPx,
                        microJitter = settings.defaultMicroJitter
                    )
                    withContext(Dispatchers.Main) {
                        if (compiled == null) {
                            stage = Stage.READY
                            lastElapsedMs = 0L
                            lastStrokes = 0
                            lastFingers = 0
                            barView?.renderState(stage, 0L, 0, 0)
                            Toast.makeText(context, "未识别到有效的触控轨迹，请重新录制", Toast.LENGTH_SHORT).show()
                        } else {
                            activePlan = compiled
                            stage = Stage.PREVIEW
                            removeBar()
                            showPreviewModal(compiled)
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    private fun handleCancel() {
        stopPolling()
        scope.launch(Dispatchers.IO) {
            ShizukuController.cancelTouchRecording()
        }
        removeBar()
        removePreview()
        onFinished(null, false)
    }

    private fun showPreviewModal(plan: MacroCompiledPlan) {
        removePreview()
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val d = context.resources.displayMetrics.density
        val dialogWidth = minOf(metrics.widthPx - (32f * d).roundToInt(), (368f * d).roundToInt())
            .coerceAtLeast((260f * d).roundToInt())

        val modal = RecordPreviewOverlayView(
            context = context,
            themeColors = colors,
            plan = plan,
            onRetake = {
                activePlan = null
                removePreview()
                stage = Stage.READY
                attachControlBar()
            },
            onCancel = {
                activePlan = null
                removePreview()
                onFinished(null, false)
            },
            onSave = { customName, openPresets ->
                val finalPlan = plan.withCustomMainName(customName)
                val savedMain = ClickConfigStore.createPresetBatchAndActivate(
                    context = context,
                    subPresets = finalPlan.subPresets,
                    mainPreset = finalPlan.mainPreset
                )
                activePlan = null
                removePreview()
                Toast.makeText(
                    context,
                    "已保存「${savedMain.name}」并设为当前方案",
                    Toast.LENGTH_SHORT
                ).show()
                onFinished(finalPlan.copy(mainPreset = savedMain), openPresets)
            }
        )

        val lp = WindowManager.LayoutParams(
            dialogWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.52f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(modal, lp)
        previewView = modal
    }

    private fun startPolling() {
        if (pollingActive) return
        pollingActive = true
        mainHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        pollingActive = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun removeBar() {
        val v = barView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        barView = null
        barLp = null
    }

    private fun removePreview() {
        val v = previewView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        previewView = null
    }
}

@SuppressLint("ViewConstructor")
private class RecordControlBarView(
    context: Context,
    initialColors: OverlayThemeColors,
    private val onPrimaryClick: () -> Unit,
    private val onCancelClick: () -> Unit,
    private val onDragMove: (Int, Int) -> Unit,
    private val onBoundsChanged: () -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var colors = initialColors
    private val statusDot: View
    private val titleView: TextView
    private val subtitleView: TextView
    private val primaryBtn: TextView
    private val cancelBtn: ImageView

    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0
    private var dragging = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12f * d).roundToInt(), (8f * d).roundToInt(), (8f * d).roundToInt(), (8f * d).roundToInt())

        statusDot = View(context)
        addView(
            statusDot,
            LayoutParams((10f * d).roundToInt(), (10f * d).roundToInt()).apply {
                marginEnd = (10f * d).roundToInt()
            }
        )

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)
        textCol.addView(subtitleView)
        addView(
            textCol,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12f * d).roundToInt()
            }
        )

        primaryBtn = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14f * d).roundToInt(), (7f * d).roundToInt(), (14f * d).roundToInt(), (7f * d).roundToInt())
            isClickable = true
            setOnClickListener { onPrimaryClick() }
        }
        addView(
            primaryBtn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )

        cancelBtn = ImageView(context).apply {
            val sz = (32f * d).roundToInt()
            val pad = (7f * d).roundToInt()
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close))
            isClickable = true
            setOnClickListener { onCancelClick() }
            layoutParams = LayoutParams(sz, sz)
        }
        addView(cancelBtn)

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onBoundsChanged() }
        applyColors(initialColors)
    }

    fun applyColors(newColors: OverlayThemeColors) {
        colors = newColors
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f * d
            setColor(newColors.surfaceContainerHigh)
            setStroke((1f * d).roundToInt().coerceAtLeast(1), newColors.outlineVariant)
        }
        titleView.setTextColor(newColors.onSurface)
        subtitleView.setTextColor(newColors.onSurfaceVariant)
        cancelBtn.setColorFilter(newColors.onSurfaceVariant)
        cancelBtn.background = newColors.createRippleDrawable(null, isOvalMask = true)
    }

    fun renderState(stage: MacroRecordOverlay.Stage, elapsedMs: Long, strokes: Int, fingers: Int) {
        val dotColor: Int
        when (stage) {
            MacroRecordOverlay.Stage.READY -> {
                dotColor = colors.primary
                titleView.text = "连招录制就绪"
                subtitleView.text = "拖移到角落后点「就绪」"
                primaryBtn.visibility = View.VISIBLE
                primaryBtn.text = "就绪"
                stylePrimaryPill(colors.primary, colors.onPrimary)
            }
            MacroRecordOverlay.Stage.ARMED -> {
                dotColor = Color.parseColor("#F59E0B")
                titleView.text = "等待首次触屏…"
                subtitleView.text = "点击目标应用任意操作即开始"
                primaryBtn.visibility = View.GONE
            }
            MacroRecordOverlay.Stage.RECORDING -> {
                dotColor = colors.error
                val secStr = String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)
                titleView.text = "录制中 $secStr"
                subtitleView.text = if (fingers > 0) "已采 $strokes 笔 · 当前 $fingers 指按下" else "已采 $strokes 笔触控"
                primaryBtn.visibility = View.VISIBLE
                primaryBtn.text = "结束"
                stylePrimaryPill(colors.error, Color.WHITE)
            }
            else -> dotColor = colors.primary
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
    }

    private fun stylePrimaryPill(bgColor: Int, textColor: Int) {
        primaryBtn.setTextColor(textColor)
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * d
            setColor(bgColor)
        }
        primaryBtn.background = colors.createRippleDrawable(pill)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lp = layoutParams as? WindowManager.LayoutParams
                downRawX = ev.rawX
                downRawY = ev.rawY
                startLpX = lp?.x ?: 0
                startLpY = lp?.y ?: 0
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    dragging = true
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
                val lp = layoutParams as? WindowManager.LayoutParams
                downRawX = event.rawX
                downRawY = event.rawY
                startLpX = lp?.x ?: 0
                startLpY = lp?.y ?: 0
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    onDragMove((startLpX + dx).roundToInt(), (startLpY + dy).roundToInt())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

@SuppressLint("ViewConstructor")
private class RecordPreviewOverlayView(
    context: Context,
    private val themeColors: OverlayThemeColors,
    private val plan: MacroCompiledPlan,
    private val onRetake: () -> Unit,
    private val onCancel: () -> Unit,
    private val onSave: (customName: String, openPresetsTab: Boolean) -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setPadding((18f * d).roundToInt(), (18f * d).roundToInt(), (18f * d).roundToInt(), (16f * d).roundToInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * d
            setColor(themeColors.surfaceContainerHigh)
            setStroke((1f * d).roundToInt().coerceAtLeast(1), themeColors.outlineVariant)
        }

        // Top header row
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "连招录制完成"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColors.onSurface)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeIv = ImageView(context).apply {
            val sz = (30f * d).roundToInt()
            val pad = (6f * d).roundToInt()
            setPadding(pad, pad, pad, pad)
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close))
            setColorFilter(themeColors.onSurfaceVariant)
            background = themeColors.createRippleDrawable(null, isOvalMask = true)
            setOnClickListener { onCancel() }
            layoutParams = LayoutParams(sz, sz)
        }
        header.addView(titleTv)
        header.addView(closeIv)
        addView(header)

        // Summary line
        val durStr = formatDurationShort(plan.totalDurationMs)
        val subCount = plan.subPresets.size
        val summaryParts = mutableListOf("耗时 $durStr", "${plan.rawStrokeCount} 笔原操作", "共 ${plan.mainPreset.targets.size} 步")
        if (subCount > 0) summaryParts += "生成 $subCount 个并发子方案"
        val summaryTv = TextView(context).apply {
            text = summaryParts.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, (4f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        addView(summaryTv)

        if (plan.mergedContinuousHolds > 0) {
            val mergeTv = TextView(context).apply {
                text = "✓ 已自动合并 ${plan.mergedContinuousHolds} 处跨阶段持续按住不断触"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.activeRunningGreen)
                setPadding(0, 0, 0, (8f * d).roundToInt())
            }
            addView(mergeTv)
        }

        // Scheme name EditText + child scheme hint
        val nameInput = EditText(themeColors.themedContext).apply {
            setText(plan.mainPreset.name)
            hint = "输入主方案名称"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(20))
            setPadding((12f * d).roundToInt(), (10f * d).roundToInt(), (12f * d).roundToInt(), (10f * d).roundToInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * d
                setColor(themeColors.surfaceContainer)
                setStroke(1, themeColors.outlineVariant)
            }
        }
        addView(
            nameInput,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4f * d).roundToInt()
            }
        )

        val structureHint = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding((2f * d).roundToInt(), (6f * d).roundToInt(), 0, (8f * d).roundToInt())
        }
        fun refreshHint(baseName: String) {
            val clean = baseName.trim().ifEmpty { plan.mainPreset.name }
            val subBase = com.zerointerrupt.clicker.model.ClickPreset.unfreezeName(clean).ifEmpty { clean }
            structureHint.text = if (subCount > 0) {
                val children = (1..subCount).joinToString("、") { "#$subBase.$it" }
                "将保存：主方案「$clean」（顺序连招）+ 子方案「$children」（并发·默认冻结）"
            } else {
                "将保存：主方案「$clean」（顺序连招）"
            }
        }
        refreshHint(plan.mainPreset.name)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshHint(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        addView(structureHint)

        // Scrollable step breakdown list
        val allTemp = plan.subPresets + plan.mainPreset
        val seamlessIds = ClickConfigStore.findSeamlessMergeStepIds(
            presets = allTemp,
            activePreset = plan.mainPreset,
            screenW = plan.screenW,
            screenH = plan.screenH
        )

        val stepsBox = ScrollView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(themeColors.surfaceContainer)
            }
        }
        val stepsList = LinearLayout(context).apply {
            orientation = VERTICAL
            val p = (10f * d).roundToInt()
            setPadding(p, p, p, p)
        }
        plan.mainPreset.targets.forEach { step ->
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding((8f * d).roundToInt(), (6f * d).roundToInt(), (8f * d).roundToInt(), (6f * d).roundToInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * d
                    setColor(ColorUtils.setAlphaComponent(themeColors.surfaceContainerHighest, 175))
                }
            }
            val title = when (step.actionType) {
                ActionType.SUB_PRESET -> {
                    val sub = plan.subPresets.firstOrNull { it.id == step.refPresetId }
                    "#${step.index} 并发子方案: ${sub?.name ?: "并发段"} (${sub?.targets?.size ?: 0} 触点)"
                }
                ActionType.SWIPE ->
                    "#${step.index} 滑动 (${step.x.roundToInt()},${step.y.roundToInt()} → ${step.endX.roundToInt()},${step.endY.roundToInt()})"
                else -> {
                    val clickTag = if (step.pressDurationMs <= 120L && step.repeatCount > 1) "连点(长按实现)" else "长按"
                    "#${step.index} $clickTag (${step.x.roundToInt()},${step.y.roundToInt()})"
                }
            }
            val detail = when (step.actionType) {
                ActionType.SUB_PRESET -> {
                    val gapStr = if (step.intervalMs <= 0L) "0ms无缝" else "停顿 ${step.intervalMs}ms"
                    val mergeStr = if (step.id in seamlessIds) " · 不断触衔接" else ""
                    "持续 ${formatDurationShort(step.subDurationMs)} · $gapStr$mergeStr"
                }
                ActionType.SWIPE -> {
                    val gapStr = if (step.intervalMs <= 0L) "0ms" else "停顿 ${step.intervalMs}ms"
                    "滑动 ${step.swipeDurationMs}ms · $gapStr"
                }
                else -> {
                    val gapStr = if (step.intervalMs <= 0L) "0ms无缝" else "停顿 ${step.intervalMs}ms"
                    val mergeStr = if (step.id in seamlessIds) " · 不断触衔接" else ""
                    "按住 ${step.pressDurationMs}ms · $gapStr · ${step.repeatCount}次$mergeStr"
                }
            }

            row.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(TextView(context).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (step.id in seamlessIds) themeColors.activeRunningGreen else themeColors.onSurfaceVariant)
                maxLines = 2
            })
            stepsList.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (6f * d).roundToInt()
                }
            )
        }
        stepsBox.addView(stepsList)
        val maxListHeight = (220f * d).roundToInt()
        addView(
            stepsBox,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (plan.mainPreset.targets.size > 3) {
                    height = maxListHeight
                }
                bottomMargin = (14f * d).roundToInt()
            }
        )

        // Footer actions
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        fun makeActionBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
                setPadding((14f * d).roundToInt(), (9f * d).roundToInt(), (14f * d).roundToInt(), (9f * d).roundToInt())
                background = themeColors.createRippleDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 18f * d
                        setColor(bg)
                    }
                )
                setOnClickListener { onClick() }
            }
        }

        btnRow.addView(
            makeActionBtn("重录", themeColors.secondaryContainer, themeColors.onSecondaryContainer, onRetake),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )
        btnRow.addView(
            makeActionBtn("进方案页", themeColors.primaryContainer, themeColors.onPrimaryContainer) {
                onSave(nameInput.text.toString(), true)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )
        btnRow.addView(
            makeActionBtn("保存并使用", themeColors.primary, themeColors.onPrimary) {
                onSave(nameInput.text.toString(), false)
            }
        )
        addView(btnRow)
    }

    private fun formatDurationShort(ms: Long): String {
        return if (ms >= 1000L) {
            val sec = ms / 1000.0
            if (ms % 1000L == 0L) "${ms / 1000L}秒" else String.format(Locale.US, "%.2f秒", sec)
        } else {
            "${ms}ms"
        }
    }
}
