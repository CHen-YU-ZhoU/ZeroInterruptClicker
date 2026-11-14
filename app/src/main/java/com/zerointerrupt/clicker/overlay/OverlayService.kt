package com.zerointerrupt.clicker.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.shizuku.ShizukuController
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import com.zerointerrupt.clicker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object OverlayStateHub {
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _clickingRunning = MutableStateFlow(false)
    val clickingRunning: StateFlow<Boolean> = _clickingRunning.asStateFlow()

    private val _configVersion = MutableStateFlow(0L)
    val configVersion: StateFlow<Long> = _configVersion.asStateFlow()

    internal fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
    }

    internal fun setClickingRunning(running: Boolean) {
        _clickingRunning.value = running
    }

    internal fun bumpConfigVersion() {
        _configVersion.value = _configVersion.value + 1L
    }
}

/**
 * Foreground overlay service implementing zero-relayout window orchestration.
 *
 * Key properties:
 * 1. All overlay windows use FLAG_NOT_FOCUSABLE | FLAG_SPLIT_TOUCH | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS.
 * 2. When locked, anchor windows carry FLAG_NOT_TOUCHABLE. Toggling Start/Stop NEVER calls
 *    WindowManager.updateViewLayout() or triggers WMS relayout — only local View.invalidate().
 * 3. Tracks DisplayManager rotation changes in real time for landscape games.
 */
class OverlayService : Service(), ControlBarView.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var themeColors: OverlayThemeColors
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseControlBar() }

    private var controlBarView: ControlBarView? = null
    private var controlBarLp: WindowManager.LayoutParams? = null
    private var isBarCollapsed: Boolean = false
    private var dockRight: Boolean = false

    private var tunePanelView: View? = null
    private var tunePanelLp: WindowManager.LayoutParams? = null

    private val targets = mutableListOf<ClickTarget>()
    private val anchorViews = LinkedHashMap<Int, AnchorPointView>()
    private val anchorLps = LinkedHashMap<Int, WindowManager.LayoutParams>()

    private var selectedTargetId: Int = -1
    private var nextTargetId: Int = 1
    private var activePresetId: String = ""
    private var isLocked: Boolean = false
    private var isClicking: Boolean = false
    private var engineMode: Int = TouchMuxUserService.MODE_IDLE
    private var engineCollectJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                onScreenGeometryChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        themeColors = OverlayThemeColors.resolve(this)
        dockRight = ClickConfigStore.loadControlBarDockRight(this)
        ShizukuController.initialize(this)
        startForegroundWithNotification()
        displayManager.registerDisplayListener(displayListener, null)

        loadInitialTargets()
        attachControlBar()
        attachAllAnchors()
        OverlayStateHub.setOverlayVisible(true)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)

        engineCollectJob = serviceScope.launch {
            ShizukuController.engineSnapshot.collect { snap ->
                engineMode = snap.engineMode
                refreshControlBarState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                stopClickingInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_CLICKING -> {
                stopClickingInternal()
            }
            ACTION_REFRESH_SETTINGS -> {
                reloadTargetSettingsFromStore()
                refreshControlBarState()
                if (ClickConfigStore.loadSettings(this).autoCollapseBar) {
                    scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                } else {
                    cancelAutoCollapse()
                }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        themeColors = OverlayThemeColors.resolve(this)
        controlBarView?.applyThemeColors(themeColors)
        onScreenGeometryChanged()
        if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        engineCollectJob?.cancel()
        stopClickingInternal()
        dismissTunePanel()
        removeAllAnchorWindows()
        removeControlBar()
        OverlayStateHub.setClickingRunning(false)
        OverlayStateHub.setOverlayVisible(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopClickIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_CLICKING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitOverlayIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_OVERLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_touch_app)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop_clicking), stopClickIntent)
            .addAction(R.drawable.ic_close, getString(R.string.action_exit_overlay), exitOverlayIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createBaseOverlayParams(
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

    private fun loadInitialTargets() {
        activePresetId = ClickConfigStore.getActivePresetId(this)
        val loaded = ClickConfigStore.loadTargets(this)
        targets.clear()
        targets.addAll(loaded)
        if (targets.isEmpty()) {
            val metrics = ShizukuController.getDisplayScreenMetrics(this)
            val settings = ClickConfigStore.loadSettings(this)
            targets += ClickTarget(
                id = 1,
                index = 1,
                x = metrics.widthPx * 0.5f,
                y = metrics.heightPx * 0.5f,
                intervalMs = settings.defaultIntervalMs,
                pressDurationMs = settings.defaultPressDurationMs,
                microJitter = settings.defaultMicroJitter,
                enabled = true
            )
            persistCurrentTargets()
        }
        nextTargetId = (targets.maxOfOrNull { it.id } ?: 0) + 1
        selectedTargetId = targets.firstOrNull()?.id ?: -1
    }

    private fun attachControlBar() {
        val bar = ControlBarView(this, themeColors, this)
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val savedPos = ClickConfigStore.loadControlBarPosition(this)
        bar.setToolsExpanded(false)
        val initialW = bar.getExpandedWidthPx()
        val initialH = bar.getExpandedBarHeightPx()
        val defaultX = if (dockRight) {
            (metrics.widthPx - initialW - (16f * resources.displayMetrics.density).roundToInt())
                .coerceAtLeast(0)
        } else {
            (16f * resources.displayMetrics.density).roundToInt()
        }
        val lp = createBaseOverlayParams(
            initialW,
            initialH,
            touchable = true
        ).apply {
            x = (savedPos?.first ?: defaultX)
                .coerceIn(0, (metrics.widthPx - initialW).coerceAtLeast(0))
            y = (savedPos?.second ?: (metrics.heightPx * 0.25f).roundToInt())
                .coerceIn(0, (metrics.heightPx - initialH).coerceAtLeast(0))
        }
        dockRight = (lp.x + initialW / 2) >= (metrics.widthPx / 2)
        bar.setCollapsed(false, dockRight)
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.addView(bar, lp)
        controlBarView = bar
        controlBarLp = lp
        isBarCollapsed = false
        refreshControlBarState()
    }

    private fun reloadTargetSettingsFromStore() {
        val storeActiveId = ClickConfigStore.getActivePresetId(this)
        val stored = ClickConfigStore.loadTargets(this)
        val currentSig = targets.map { listOf(it.id, it.x, it.y, it.intervalMs, it.pressDurationMs, it.microJitter, it.enabled) }
        val storedSig = stored.map { listOf(it.id, it.x, it.y, it.intervalMs, it.pressDurationMs, it.microJitter, it.enabled) }
        if (activePresetId != storeActiveId || currentSig != storedSig) {
            if (isClicking) {
                stopClickingInternal()
            }
            replaceTargetsWithActivePreset()
        }
    }

    private fun replaceTargetsWithActivePreset() {
        activePresetId = ClickConfigStore.getActivePresetId(this)
        dismissTunePanel()
        removeAllAnchorWindows()
        targets.clear()
        targets.addAll(ClickConfigStore.loadTargets(this))
        nextTargetId = (targets.maxOfOrNull { it.id } ?: 0) + 1
        selectedTargetId = targets.firstOrNull()?.id ?: -1
        attachAllAnchors()
        refreshControlBarState()
    }

    private fun persistCurrentTargets() {
        ClickConfigStore.saveTargets(this, targets)
        OverlayStateHub.bumpConfigVersion()
    }

    private fun removeControlBar() {
        val bar = controlBarView ?: return
        try {
            windowManager.removeViewImmediate(bar)
        } catch (_: Throwable) {
        }
        controlBarView = null
        controlBarLp = null
    }

    private fun attachAllAnchors() {
        for (t in targets) {
            attachSingleAnchor(t)
        }
        refreshAnchorVisuals()
    }

    private fun attachSingleAnchor(target: ClickTarget) {
        if (anchorViews.containsKey(target.id)) return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val view = AnchorPointView(
            context = this,
            targetId = target.id,
            onDragMove = { id, nx, ny -> handleAnchorDragMove(id, nx, ny) },
            onDragFinish = { id -> handleAnchorDragFinish(id) },
            onTap = { id -> handleAnchorTap(id) }
        )
        target.x = target.x.coerceIn(0f, (metrics.widthPx - 1).coerceAtLeast(1).toFloat())
        target.y = target.y.coerceIn(0f, (metrics.heightPx - 1).coerceAtLeast(1).toFloat())

        val lp = createBaseOverlayParams(
            view.viewSizePx,
            view.viewSizePx,
            touchable = !isLocked
        ).apply {
            x = (target.x - view.halfSizePx).roundToInt()
            y = (target.y - view.halfSizePx).roundToInt()
            alpha = if (isLocked) 0.80f else 1.0f
        }
        view.syncWindowPosition(lp.x, lp.y)
        windowManager.addView(view, lp)
        anchorViews[target.id] = view
        anchorLps[target.id] = lp
    }

    private fun removeAllAnchorWindows() {
        for (v in anchorViews.values) {
            try {
                windowManager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        anchorViews.clear()
        anchorLps.clear()
    }

    private fun handleAnchorDragMove(targetId: Int, newLpX: Int, newLpY: Int) {
        val view = anchorViews[targetId] ?: return
        val lp = anchorLps[targetId] ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val minX = -view.halfSizePx
        val maxX = metrics.widthPx - 1 - view.halfSizePx
        val minY = -view.halfSizePx
        val maxY = metrics.heightPx - 1 - view.halfSizePx

        lp.x = newLpX.coerceIn(minX, maxX.coerceAtLeast(minX))
        lp.y = newLpY.coerceIn(minY, maxY.coerceAtLeast(minY))
        windowManager.updateViewLayout(view, lp)

        targets.firstOrNull { it.id == targetId }?.let { t ->
            t.x = (lp.x + view.halfSizePx).toFloat()
            t.y = (lp.y + view.halfSizePx).toFloat()
        }
    }

    private fun handleAnchorDragFinish(targetId: Int) {
        selectedTargetId = targetId
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (isClicking) {
            pushTargetsToServiceAsync()
        }
    }

    private fun handleAnchorTap(targetId: Int) {
        selectedTargetId = targetId
        refreshAnchorVisuals()
        showOrUpdateTunePanel(forceShow = true)
    }

    private fun refreshAnchorVisuals() {
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            anchorViews[t.id]?.setVisualState(
                index = t.index,
                enabled = t.enabled,
                jitter = t.microJitter,
                isLocked = isLocked,
                isRunning = isClicking,
                isSelected = (t.id == selectedTargetId)
            )
        }
    }

    private fun refreshControlBarState() {
        val (presetIdx, presetCount) = ClickConfigStore.getActivePresetIndexAndTotal(this)
        controlBarView?.updateState(
            isRunning = isClicking,
            isLocked = isLocked,
            engineMode = engineMode,
            tunePanelOpen = (tunePanelView != null),
            presetIndex = presetIdx,
            presetCount = presetCount
        )
    }

    /**
     * Switches anchor windows between Edit (touchable) and Locked (FLAG_NOT_TOUCHABLE).
     * Only calls updateViewLayout when lock state actually changes.
     */
    private fun applyLockMode(locked: Boolean) {
        if (isLocked == locked) return
        isLocked = locked
        if (locked) {
            dismissTunePanel()
        }
        for ((id, view) in anchorViews) {
            val lp = anchorLps[id] ?: continue
            val updatedFlags = if (locked) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            val updatedAlpha = if (locked) 0.80f else 1.0f
            if (lp.flags != updatedFlags || lp.alpha != updatedAlpha) {
                lp.flags = updatedFlags
                lp.alpha = updatedAlpha
                windowManager.updateViewLayout(view, lp)
            }
        }
        refreshAnchorVisuals()
        refreshControlBarState()
    }

    private fun scheduleAutoCollapse(delayMs: Long) {
        cancelAutoCollapse()
        if (isBarCollapsed) return
        if (tunePanelView != null) return
        if (!ClickConfigStore.loadSettings(this).autoCollapseBar) return
        mainHandler.postDelayed(autoCollapseRunnable, delayMs)
    }

    private fun cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun collapseControlBar() {
        if (isBarCollapsed) return
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        cancelAutoCollapse()
        dismissTunePanel()

        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        dockRight = (lp.x + bar.getCurrentWidthPx() / 2) >= (metrics.widthPx / 2)
        isBarCollapsed = true
        bar.setCollapsed(true, dockRight)

        lp.width = bar.getCollapsedWidthPx()
        lp.height = bar.getCollapsedHeightPx()
        lp.x = if (dockRight) (metrics.widthPx - lp.width).coerceAtLeast(0) else 0
        lp.y = lp.y.coerceIn(0, (metrics.heightPx - lp.height).coerceAtLeast(0))
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.updateViewLayout(bar, lp)
        ClickConfigStore.saveControlBarCollapseState(this, true, dockRight)
    }

    private fun expandControlBar() {
        cancelAutoCollapse()
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val edgeMarginPx = (12f * resources.displayMetrics.density).roundToInt()

        isBarCollapsed = false
        bar.setCollapsed(false, dockRight)

        lp.width = bar.getCurrentWidthPx()
        lp.height = bar.getCurrentHeightPx()
        lp.x = if (dockRight) {
            (metrics.widthPx - lp.width - edgeMarginPx).coerceAtLeast(0)
        } else {
            edgeMarginPx.coerceAtMost((metrics.widthPx - lp.width).coerceAtLeast(0))
        }
        lp.y = lp.y.coerceIn(0, (metrics.heightPx - lp.height).coerceAtLeast(0))
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.updateViewLayout(bar, lp)
        ClickConfigStore.saveControlBarCollapseState(this, false, dockRight)
        ClickConfigStore.saveControlBarPosition(this, lp.x, lp.y)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    override fun onSwitchPreset() {
        val presets = ClickConfigStore.loadPresets(this)
        if (presets.size <= 1) {
            val only = presets.firstOrNull()
            Toast.makeText(
                this,
                "当前仅 1 个方案「${only?.name ?: "方案 1"}」，可在主界面新增方案",
                Toast.LENGTH_SHORT
            ).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }
        if (isClicking) {
            stopClickingInternal()
        }
        val (newIdx, total, nextPreset) = ClickConfigStore.switchToNextPreset(this)
        replaceTargetsWithActivePreset()
        OverlayStateHub.bumpConfigVersion()
        Toast.makeText(
            this,
            "切换方案 ${newIdx + 1}/$total：${nextPreset.name} (${targets.size} 点)",
            Toast.LENGTH_SHORT
        ).show()
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    override fun onToggleToolsExpanded() {
        cancelAutoCollapse()
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val nextExpanded = !bar.isToolsExpanded()
        bar.setToolsExpanded(nextExpanded)
        if (!nextExpanded) {
            dismissTunePanel()
        }
        lp.width = bar.getCurrentWidthPx()
        lp.height = bar.getCurrentHeightPx()
        lp.y = lp.y.coerceIn(0, (metrics.heightPx - lp.height).coerceAtLeast(0))
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.updateViewLayout(bar, lp)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    override fun onCollapseRequested() {
        collapseControlBar()
    }

    override fun onExpandRequested() {
        expandControlBar()
    }

    override fun onDragStart() {
        cancelAutoCollapse()
    }

    override fun onTogglePlayStop() {
        if (isClicking) {
            stopClickingInternal()
            return
        }
        if (targets.none { it.enabled }) {
            Toast.makeText(this, "请至少启用一个点击锚点", Toast.LENGTH_SHORT).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }
        if (!ShizukuController.isServiceConnected()) {
            ShizukuController.evaluateShizukuState()
            Toast.makeText(this, "Shizuku 触控服务尚未连接，请在主界面授权并连接", Toast.LENGTH_SHORT).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }

        // If already locked, zero updateViewLayout calls occur on Start!
        if (!isLocked) {
            applyLockMode(true)
            // Wait one animation frame after applying FLAG_NOT_TOUCHABLE before injecting first tap
            controlBarView?.postOnAnimation {
                startClickingInternal()
            } ?: startClickingInternal()
        } else {
            startClickingInternal()
        }
    }

    private fun startClickingInternal() {
        cancelAutoCollapse()
        val snapshot = targets.map { it.copy() }
        isClicking = true
        OverlayStateHub.setClickingRunning(true)
        refreshAnchorVisuals()
        refreshControlBarState()

        serviceScope.launch(Dispatchers.IO) {
            val mode = ShizukuController.startClicking(snapshot)
            withContext(Dispatchers.Main) {
                if (mode < 0) {
                    isClicking = false
                    OverlayStateHub.setClickingRunning(false)
                    refreshAnchorVisuals()
                    refreshControlBarState()
                    Toast.makeText(
                        this@OverlayService,
                        "启动连点失败 (code=$mode)，请检查 Shizuku 状态",
                        Toast.LENGTH_SHORT
                    ).show()
                    scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                } else {
                    engineMode = mode
                    refreshControlBarState()
                    scheduleAutoCollapse(START_COLLAPSE_DELAY_MS)
                }
            }
        }
    }

    private fun stopClickingInternal() {
        if (!isClicking) return
        cancelAutoCollapse()
        isClicking = false
        OverlayStateHub.setClickingRunning(false)
        // Zero WMS updateViewLayout on Stop: only local invalidate()
        refreshAnchorVisuals()
        refreshControlBarState()
        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.stopClicking()
        }
        if (!isBarCollapsed) {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onAddTarget() {
        if (isClicking) {
            stopClickingInternal()
        }
        if (isLocked) {
            applyLockMode(false)
        }
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val settings = ClickConfigStore.loadSettings(this)
        val offsetIndex = targets.size % 6
        val offsetPx = (offsetIndex - 2) * 34f * resources.displayMetrics.density
        val newTarget = ClickTarget(
            id = nextTargetId++,
            index = targets.size + 1,
            x = (metrics.widthPx * 0.5f + offsetPx).coerceIn(40f, (metrics.widthPx - 40).coerceAtLeast(40).toFloat()),
            y = (metrics.heightPx * 0.5f + offsetPx * 0.6f).coerceIn(40f, (metrics.heightPx - 40).coerceAtLeast(40).toFloat()),
            intervalMs = settings.defaultIntervalMs,
            pressDurationMs = settings.defaultPressDurationMs,
            microJitter = settings.defaultMicroJitter,
            enabled = true
        )
        targets += newTarget
        selectedTargetId = newTarget.id
        attachSingleAnchor(newTarget)
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        } else {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onRemoveTarget() {
        if (targets.isEmpty()) return
        if (isClicking) {
            stopClickingInternal()
        }
        val removeId = if (targets.any { it.id == selectedTargetId }) {
            selectedTargetId
        } else {
            targets.last().id
        }
        targets.removeAll { it.id == removeId }
        anchorViews.remove(removeId)?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        anchorLps.remove(removeId)
        selectedTargetId = targets.lastOrNull()?.id ?: -1
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (targets.isEmpty()) {
            dismissTunePanel()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        } else if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        } else {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onToggleLockEdit() {
        if (isClicking && isLocked) {
            stopClickingInternal()
        }
        applyLockMode(!isLocked)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    override fun onToggleTunePanel() {
        if (tunePanelView != null) {
            dismissTunePanel()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        } else {
            cancelAutoCollapse()
            if (isLocked && !isClicking) {
                applyLockMode(false)
            }
            showOrUpdateTunePanel(forceShow = true)
        }
    }

    override fun onCloseOverlay() {
        cancelAutoCollapse()
        stopClickingInternal()
        stopSelf()
    }

    override fun onDragMove(newLpX: Int, newLpY: Int) {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val w = bar.getCurrentWidthPx()
        val h = bar.getCurrentHeightPx()
        lp.x = newLpX.coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
        lp.y = newLpY.coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
        dockRight = (lp.x + w / 2) >= (metrics.widthPx / 2)
        bar.setDockRight(dockRight)
        windowManager.updateViewLayout(bar, lp)
    }

    override fun onDragEnd() {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val w = bar.getCurrentWidthPx()
        dockRight = (lp.x + w / 2) >= (metrics.widthPx / 2)
        bar.setDockRight(dockRight)
        if (isBarCollapsed) {
            lp.x = if (dockRight) (metrics.widthPx - w).coerceAtLeast(0) else 0
            bar.syncWindowPosition(lp.x, lp.y)
            windowManager.updateViewLayout(bar, lp)
            ClickConfigStore.saveControlBarCollapseState(this, true, dockRight)
        } else {
            ClickConfigStore.saveControlBarPosition(this, lp.x, lp.y)
            ClickConfigStore.saveControlBarCollapseState(this, false, dockRight)
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    private fun onScreenGeometryChanged() {
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        // Clamp control bar
        val bar = controlBarView
        val barLp = controlBarLp
        if (bar != null && barLp != null) {
            val w = bar.getCurrentWidthPx()
            val h = bar.getCurrentHeightPx()
            barLp.width = w
            barLp.height = h
            barLp.x = if (isBarCollapsed) {
                if (dockRight) (metrics.widthPx - w).coerceAtLeast(0) else 0
            } else {
                barLp.x.coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
            }
            barLp.y = barLp.y.coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
            bar.syncWindowPosition(barLp.x, barLp.y)
            windowManager.updateViewLayout(bar, barLp)
        }

        // Clamp anchors inside new display bounds
        var anyChanged = false
        for (t in targets) {
            val clampedX = t.x.coerceIn(0f, (metrics.widthPx - 1).coerceAtLeast(1).toFloat())
            val clampedY = t.y.coerceIn(0f, (metrics.heightPx - 1).coerceAtLeast(1).toFloat())
            if (clampedX != t.x || clampedY != t.y) {
                t.x = clampedX
                t.y = clampedY
                anyChanged = true
            }
            val view = anchorViews[t.id]
            val lp = anchorLps[t.id]
            if (view != null && lp != null) {
                lp.x = (t.x - view.halfSizePx).roundToInt()
                lp.y = (t.y - view.halfSizePx).roundToInt()
                view.syncWindowPosition(lp.x, lp.y)
                windowManager.updateViewLayout(view, lp)
            }
        }
        if (anyChanged) {
            ClickConfigStore.saveTargets(this, targets)
        }

        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.updateDisplayMetrics()
            if (isClicking) {
                ShizukuController.updateTargets(targets.map { it.copy() })
            }
        }
    }

    private fun pushTargetsToServiceAsync() {
        val snapshot = targets.map { it.copy() }
        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.updateTargets(snapshot)
        }
    }

    private fun dismissTunePanel() {
        val panel = tunePanelView ?: return
        try {
            windowManager.removeViewImmediate(panel)
        } catch (_: Throwable) {
        }
        tunePanelView = null
        tunePanelLp = null
        refreshControlBarState()
    }

    private fun showOrUpdateTunePanel(forceShow: Boolean) {
        if (!forceShow && tunePanelView == null) return
        val target = targets.firstOrNull { it.id == selectedTargetId }
            ?: targets.firstOrNull()?.also { selectedTargetId = it.id }
            ?: return

        cancelAutoCollapse()
        dismissTunePanel()

        val colors = themeColors
        val d = resources.displayMetrics.density
        val pad = (14f * d).roundToInt()
        val panelWidth = (256f * d).roundToInt()
        val themedCtx = colors.themedContext

        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * d
                setColor(colors.surfaceContainerHigh)
                setStroke((1f * d).roundToInt().coerceAtLeast(1), colors.outlineVariant)
            }
        }

        val titleRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(themedCtx).apply {
            text = "锚点 #${target.index}  (${target.x.roundToInt()}, ${target.y.roundToInt()})"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeTv = TextView(themedCtx).apply {
            text = "完成"
            isClickable = true
            setTextColor(colors.onPrimaryContainer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            setPadding((12f * d).roundToInt(), (5f * d).roundToInt(), (12f * d).roundToInt(), (5f * d).roundToInt())
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(colors.primaryContainer)
            }
            background = colors.createRippleDrawable(pillBg)
            setOnClickListener {
                dismissTunePanel()
                scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            }
        }
        titleRow.addView(titleTv)
        titleRow.addView(closeTv)
        root.addView(titleRow)

        // Interval section
        val intervalValueTv = TextView(themedCtx).apply {
            text = "点击间隔: ${target.intervalMs} ms"
            setTextColor(colors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, (10f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        root.addView(intervalValueTv)

        val intervalPresetChips = mutableListOf<Pair<Long, TextView>>()
        fun refreshIntervalPresetHighlight() {
            intervalPresetChips.forEach { (preset, chip) ->
                styleChipButton(chip, selected = (target.intervalMs == preset), colors = colors)
            }
        }

        val intervalBtnRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(-50L to "-50", -10L to "-10", 10L to "+10", 50L to "+50").forEach { (delta, label) ->
            intervalBtnRow.addView(createChipButton(themedCtx, label, false, colors) {
                target.intervalMs = (target.intervalMs + delta)
                    .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                intervalValueTv.text = "点击间隔: ${target.intervalMs} ms"
                refreshIntervalPresetHighlight()
                persistCurrentTargets()
                if (isClicking) pushTargetsToServiceAsync()
            })
        }
        root.addView(intervalBtnRow)

        val intervalPresetRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (5f * d).roundToInt(), 0, 0)
        }
        listOf(10L, 30L, 80L, 200L).forEach { preset ->
            val chip = createChipButton(themedCtx, "${preset}ms", target.intervalMs == preset, colors) {
                target.intervalMs = preset
                intervalValueTv.text = "点击间隔: ${target.intervalMs} ms"
                refreshIntervalPresetHighlight()
                persistCurrentTargets()
                if (isClicking) pushTargetsToServiceAsync()
            }
            intervalPresetChips += preset to chip
            intervalPresetRow.addView(chip)
        }
        root.addView(intervalPresetRow)

        // Press duration section
        val pressValueTv = TextView(themedCtx).apply {
            text = "按压时长: ${target.pressDurationMs} ms"
            setTextColor(colors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, (10f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        root.addView(pressValueTv)

        val pressPresetChips = mutableListOf<Pair<Long, TextView>>()
        fun refreshPressPresetHighlight() {
            pressPresetChips.forEach { (preset, chip) ->
                styleChipButton(chip, selected = (target.pressDurationMs == preset), colors = colors)
            }
        }

        val pressPresetRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(15L, 25L, 50L, 120L).forEach { preset ->
            val chip = createChipButton(themedCtx, "${preset}ms", target.pressDurationMs == preset, colors) {
                target.pressDurationMs = preset
                pressValueTv.text = "按压时长: ${target.pressDurationMs} ms"
                refreshPressPresetHighlight()
                persistCurrentTargets()
                if (isClicking) pushTargetsToServiceAsync()
            }
            pressPresetChips += preset to chip
            pressPresetRow.addView(chip)
        }
        root.addView(pressPresetRow)

        val thumbTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.onPrimary, colors.onSurfaceVariant)
        )
        val trackTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.primary, colors.surfaceContainerHighest)
        )

        // Micro jitter switch
        val jitterSwitch = SwitchMaterial(themedCtx).apply {
            text = "随机微抖动 (±4px)"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, (6f * d).roundToInt(), 0, 0)
            thumbTintList = thumbTint
            trackTintList = trackTint
            isChecked = target.microJitter
            setOnCheckedChangeListener { _, checked ->
                target.microJitter = checked
                persistCurrentTargets()
                refreshAnchorVisuals()
                if (isClicking) pushTargetsToServiceAsync()
            }
        }
        root.addView(jitterSwitch)

        val enabledSwitch = SwitchMaterial(themedCtx).apply {
            text = "启用该锚点"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            thumbTintList = thumbTint
            trackTintList = trackTint
            isChecked = target.enabled
            setOnCheckedChangeListener { _, checked ->
                target.enabled = checked
                persistCurrentTargets()
                refreshAnchorVisuals()
                if (isClicking) pushTargetsToServiceAsync()
            }
        }
        root.addView(enabledSwitch)

        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val barLp = controlBarLp
        val barWidth = controlBarView?.getCurrentWidthPx() ?: (52f * d).roundToInt()
        val gap = (10f * d).roundToInt()
        val preferredX = if (barLp != null) {
            if (barLp.x + barWidth + gap + panelWidth <= metrics.widthPx) {
                barLp.x + barWidth + gap
            } else {
                (barLp.x - gap - panelWidth).coerceAtLeast(8)
            }
        } else {
            ((metrics.widthPx - panelWidth) / 2).coerceAtLeast(8)
        }.coerceIn(0, (metrics.widthPx - panelWidth - 8).coerceAtLeast(0))
        val preferredY = (barLp?.y ?: (metrics.heightPx * 0.2f).roundToInt())
            .coerceIn(16, (metrics.heightPx - (260f * d).roundToInt()).coerceAtLeast(16))

        val lp = createBaseOverlayParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        ).apply {
            x = preferredX
            y = preferredY
        }

        windowManager.addView(root, lp)
        tunePanelView = root
        tunePanelLp = lp
        refreshControlBarState()
    }

    private fun createChipButton(
        ctx: Context,
        label: String,
        selected: Boolean,
        colors: OverlayThemeColors,
        onClick: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            isClickable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, (6f * d).roundToInt(), 0, (6f * d).roundToInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = (5f * d).roundToInt()
            }
            styleChipButton(this, selected, colors)
            setOnClickListener { onClick() }
        }
    }

    private fun styleChipButton(chip: TextView, selected: Boolean, colors: OverlayThemeColors) {
        val d = resources.displayMetrics.density
        chip.setTextColor(if (selected) colors.onPrimaryContainer else colors.onSecondaryContainer)
        chip.paint.isFakeBoldText = selected
        val chipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * d
            setColor(if (selected) colors.primaryContainer else colors.secondaryContainer)
            if (!selected) {
                setStroke(1, colors.outlineVariant)
            }
        }
        chip.background = colors.createRippleDrawable(chipBg)
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.zerointerrupt.clicker.ACTION_START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.zerointerrupt.clicker.ACTION_STOP_OVERLAY"
        const val ACTION_STOP_CLICKING = "com.zerointerrupt.clicker.ACTION_STOP_CLICKING"
        const val ACTION_REFRESH_SETTINGS = "com.zerointerrupt.clicker.ACTION_REFRESH_SETTINGS"

        private const val CHANNEL_ID = "zero_interrupt_overlay_channel"
        private const val NOTIFICATION_ID = 2001
        private const val IDLE_COLLAPSE_DELAY_MS = 4500L
        private const val START_COLLAPSE_DELAY_MS = 1500L
    }
}
