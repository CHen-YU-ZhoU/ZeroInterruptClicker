package com.zerointerrupt.clicker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.QuickTileService
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.automation.LocalRpcServer
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.overlay.OverlayService
import com.zerointerrupt.clicker.overlay.OverlayStateHub
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState
import com.zerointerrupt.clicker.shizuku.ShizukuController
import com.zerointerrupt.clicker.ui.theme.ZeroInterruptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var selfCheckTimer: CountDownTimer? = null
    private lateinit var snackbarHostState: SnackbarHostState
    private var currentActiveTab by mutableStateOf(MainTab.HOME)

    private var uiModel by mutableStateOf(
        MainUiState(
            connectionState = ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
            engineSnapshot = ShizukuController.engineSnapshot.value,
            slotStates = ShizukuController.slotStates.value,
            overlayVisible = false,
            clickingRunning = false,
            overlayPermGranted = false,
            notifPermGranted = true,
            showNotifRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            selfCheckPassed = false,
            selfCheckRemainingMs = 0L,
            selfCheckTimeoutMs = SELF_CHECK_TIMEOUT_MS,
            tuningTier = ClickGlobalSettings.TIER_CUSTOM,
            intervalMs = ClickTarget.DEFAULT_INTERVAL_MS,
            pressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
            microJitter = true,
            jitterRadiusPx = 4.0f,
            lockedAnchorAlpha = ClickGlobalSettings.DEFAULT_LOCKED_ALPHA,
            autoLockOnStart = true,
            autoCollapseBar = false,
            presets = emptyList(),
            activePresetId = "preset_1",
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            themeMode = ClickGlobalSettings.THEME_SYSTEM,
            dynamicColor = true,
            volumeKeyToggleEnabled = true,
            proModeEnabled = false,
            preferredBackend = ClickGlobalSettings.BACKEND_AUTO,
            rpcBridgeEnabled = false,
            rpcToken = "",
            rpcServerRunning = false,
            appVersionName = BuildConfig.VERSION_NAME
        )
    )

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        syncFromStorageAndSystem()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        syncFromStorageAndSystem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuController.initialize(this)
        LocalRpcServer.syncWithSettings(this)
        syncFromStorageAndSystem()

        setContent {
            val connectionState by ShizukuController.connectionState.collectAsState()
            val engineSnapshot by ShizukuController.engineSnapshot.collectAsState()
            val slotStates by ShizukuController.slotStates.collectAsState()
            val overlayVisible by OverlayStateHub.overlayVisible.collectAsState()
            val clickingRunning by OverlayStateHub.clickingRunning.collectAsState()
            val configVersion by OverlayStateHub.configVersion.collectAsState()

            val scope = rememberCoroutineScope()
            snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(configVersion) {
                syncFromStorageAndSystem()
            }

            LaunchedEffect(ShizukuController.emergencyUngrabEvents) {
                ShizukuController.emergencyUngrabEvents.collect {
                    OverlayStateHub.setClickingRunning(this@MainActivity, false)
                    snackbarHostState.showSnackbar("已响应长按音量减键：紧急释放物理屏独占")
                }
            }

            LaunchedEffect(ShizukuController.clickingCompletedEvents) {
                ShizukuController.clickingCompletedEvents.collect {
                    OverlayStateHub.setClickingRunning(this@MainActivity, false)
                }
            }

            LaunchedEffect(currentActiveTab, uiModel.proModeEnabled, connectionState) {
                while (uiModel.proModeEnabled &&
                    currentActiveTab == MainTab.LAB &&
                    connectionState == ShizukuConnectionState.CONNECTED
                ) {
                    ShizukuController.refreshSlotStates()
                    delay(400L)
                }
            }

            val mergedState = uiModel.copy(
                connectionState = connectionState,
                engineSnapshot = engineSnapshot,
                slotStates = slotStates,
                overlayVisible = overlayVisible,
                clickingRunning = clickingRunning,
                rpcServerRunning = LocalRpcServer.isRunning()
            )

            val callbacks = remember(scope) { buildCallbacks(scope) }

            val darkTheme = when (mergedState.themeMode) {
                ClickGlobalSettings.THEME_DARK -> true
                ClickGlobalSettings.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }

            ZeroInterruptTheme(
                darkTheme = darkTheme,
                dynamicColor = mergedState.dynamicColor
            ) {
                MainScreen(
                    state = mergedState,
                    callbacks = callbacks,
                    snackbarHostState = snackbarHostState,
                    onCurrentTabChanged = { currentActiveTab = it }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuController.evaluateShizukuState()
        LocalRpcServer.syncWithSettings(this)
        syncFromStorageAndSystem()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ShizukuController.updateDisplayMetrics()
        syncFromStorageAndSystem()
    }

    override fun onDestroy() {
        selfCheckTimer?.cancel()
        selfCheckTimer = null
        super.onDestroy()
    }

    private fun syncFromStorageAndSystem() {
        val settings = ClickConfigStore.loadSettings(this)
        val presets = ClickConfigStore.loadPresets(this)
        val activeId = ClickConfigStore.getActivePresetId(this)
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        presets.forEach { preset ->
            preset.targets.forEach { t -> t.remapToScreen(metrics.widthPx, metrics.heightPx) }
        }

        uiModel = uiModel.copy(
            overlayPermGranted = overlayGranted,
            notifPermGranted = notifGranted,
            selfCheckPassed = settings.selfCheckPassed,
            tuningTier = settings.tuningTier,
            intervalMs = settings.defaultIntervalMs,
            pressDurationMs = settings.defaultPressDurationMs,
            microJitter = settings.defaultMicroJitter,
            jitterRadiusPx = settings.jitterRadiusPx,
            lockedAnchorAlpha = settings.lockedAnchorAlpha,
            autoLockOnStart = settings.autoLockOnStart,
            autoCollapseBar = settings.autoCollapseBar,
            presets = presets,
            activePresetId = activeId,
            screenWidthPx = metrics.widthPx,
            screenHeightPx = metrics.heightPx,
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            volumeKeyToggleEnabled = settings.volumeKeyToggleEnabled,
            proModeEnabled = settings.proModeEnabled,
            preferredBackend = settings.preferredBackend,
            rpcBridgeEnabled = settings.rpcBridgeEnabled,
            rpcToken = settings.rpcToken,
            rpcServerRunning = LocalRpcServer.isRunning()
        )
    }

    private fun saveCurrentSettings(
        mutate: (ClickGlobalSettings) -> ClickGlobalSettings,
        notifyOverlay: Boolean = true,
        syncRpc: Boolean = false,
        syncTapPresets: Boolean = false
    ) {
        val next = mutate(ClickConfigStore.loadSettings(this))
        ClickConfigStore.saveSettings(this, next)
        if (syncTapPresets) {
            ClickConfigStore.applyGlobalTapSettingsToAllPresets(this, next)
        }
        if (syncRpc) {
            LocalRpcServer.syncWithSettings(this)
        }
        syncFromStorageAndSystem()
        if (notifyOverlay) {
            notifyOverlayRefresh()
        }
    }

    private fun notifyOverlayRefresh() {
        OverlayStateHub.bumpConfigVersion()
        if (OverlayStateHub.overlayVisible.value) {
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_REFRESH_SETTINGS
                }
            )
        }
    }

    private fun showMessage(scope: kotlinx.coroutines.CoroutineScope, message: String) {
        if (!::snackbarHostState.isInitialized) return
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    private fun copyToClipboard(scope: kotlinx.coroutines.CoroutineScope, label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        showMessage(scope, "已复制 $label")
    }

    private fun buildCallbacks(scope: kotlinx.coroutines.CoroutineScope): MainScreenCallbacks {
        return MainScreenCallbacks(
            onToggleOverlay = {
                if (!Settings.canDrawOverlays(this)) {
                    openOverlayPermissionSettings()
                } else if (OverlayStateHub.overlayVisible.value) {
                    startService(
                        Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_STOP_OVERLAY
                        }
                    )
                } else {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_START_OVERLAY
                        }
                    )
                }
            },
            onGrantOverlay = { openOverlayPermissionSettings() },
            onGrantShizuku = {
                ShizukuController.requestPermission()
                if (ShizukuController.connectionState.value == ShizukuConnectionState.SHIZUKU_NOT_RUNNING) {
                    showMessage(scope, "未检测到 Shizuku 后台服务，请先打开 Shizuku 并启动")
                }
            },
            onGrantNotif = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onStartSelfCheck = {
                selfCheckTimer?.cancel()
                scope.launch {
                    val rc = withContext(Dispatchers.IO) {
                        ShizukuController.startSelfCheckGrab(SELF_CHECK_TIMEOUT_MS.toInt())
                    }
                    if (rc < 0) {
                        uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                        showMessage(scope, "启动 5 秒校准失败（code=$rc），请检查 Shizuku 与设备节点")
                    } else {
                        uiModel = uiModel.copy(selfCheckRemainingMs = SELF_CHECK_TIMEOUT_MS)
                        selfCheckTimer = object : CountDownTimer(SELF_CHECK_TIMEOUT_MS, 500L) {
                            override fun onTick(millisUntilFinished: Long) {
                                uiModel = uiModel.copy(selfCheckRemainingMs = millisUntilFinished)
                            }

                            override fun onFinish() {
                                uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                                scope.launch(Dispatchers.IO) {
                                    ShizukuController.cancelSelfCheckAndUngrab()
                                }
                                syncFromStorageAndSystem()
                                showMessage(scope, "校准倒计时结束，已自动安全回滚")
                            }
                        }.start()
                    }
                }
            },
            onConfirmSelfCheck = {
                selfCheckTimer?.cancel()
                selfCheckTimer = null
                uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        ShizukuController.confirmSelfCheck()
                    }
                    syncFromStorageAndSystem()
                    showMessage(
                        scope,
                        if (ok) "触控校准通过，零断触就绪" else "校准确认失败，请重试"
                    )
                }
            },
            onRollbackSelfCheck = {
                selfCheckTimer?.cancel()
                selfCheckTimer = null
                uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                scope.launch(Dispatchers.IO) {
                    ShizukuController.cancelSelfCheckAndUngrab()
                }
                syncFromStorageAndSystem()
                showMessage(scope, "已撤销硬件独占")
            },
            onReleaseNative = {
                selfCheckTimer?.cancel()
                selfCheckTimer = null
                uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                scope.launch(Dispatchers.IO) {
                    ShizukuController.shutdownEngine()
                }
                showMessage(scope, "已释放底层硬件独占与槽位")
            },
            onCleanupOrphans = {
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        ShizukuController.cleanupOrphanProcesses()
                    }
                    showMessage(scope, "已清理 $count 个异常残留进程")
                }
            },
            onRefreshCapabilities = {
                ShizukuController.evaluateShizukuState()
                ShizukuController.refreshCapabilitiesAsync()
                showMessage(scope, "正在重新探测硬件与权限")
            },
            onSelectTuningTier = { tier ->
                saveCurrentSettings(
                    mutate = { cur ->
                        when (tier) {
                            ClickGlobalSettings.TIER_CASUAL -> cur.copy(
                                tuningTier = ClickGlobalSettings.TIER_CASUAL,
                                defaultIntervalMs = ClickGlobalSettings.CASUAL_INTERVAL_MS,
                                defaultPressDurationMs = ClickGlobalSettings.CASUAL_PRESS_MS
                            )
                            ClickGlobalSettings.TIER_STANDARD -> cur.copy(
                                tuningTier = ClickGlobalSettings.TIER_STANDARD,
                                defaultIntervalMs = ClickGlobalSettings.STANDARD_INTERVAL_MS,
                                defaultPressDurationMs = ClickGlobalSettings.STANDARD_PRESS_MS
                            )
                            ClickGlobalSettings.TIER_FAST -> cur.copy(
                                tuningTier = ClickGlobalSettings.TIER_FAST,
                                defaultIntervalMs = ClickGlobalSettings.FAST_INTERVAL_MS,
                                defaultPressDurationMs = ClickGlobalSettings.FAST_PRESS_MS
                            )
                            else -> cur.copy(
                                tuningTier = ClickGlobalSettings.TIER_CUSTOM,
                                defaultIntervalMs = cur.customIntervalMs,
                                defaultPressDurationMs = cur.customPressDurationMs
                            )
                        }
                    },
                    syncTapPresets = true
                )
            },
            onResetTuningDefaults = {
                saveCurrentSettings(
                    mutate = {
                        it.copy(
                            tuningTier = ClickGlobalSettings.TIER_STANDARD,
                            defaultIntervalMs = ClickGlobalSettings.STANDARD_INTERVAL_MS,
                            defaultPressDurationMs = ClickGlobalSettings.STANDARD_PRESS_MS,
                            customIntervalMs = ClickTarget.DEFAULT_INTERVAL_MS,
                            customPressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
                            defaultMicroJitter = true,
                            jitterRadiusPx = 4.0f,
                            lockedAnchorAlpha = ClickGlobalSettings.DEFAULT_LOCKED_ALPHA,
                            autoLockOnStart = true,
                            autoCollapseBar = false
                        )
                    },
                    syncTapPresets = true
                )
                showMessage(scope, "已恢复默认参数")
            },
            onIntervalChange = { value, commit ->
                val clamped = value.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                uiModel = uiModel.copy(
                    tuningTier = ClickGlobalSettings.TIER_CUSTOM,
                    intervalMs = clamped
                )
                if (commit) {
                    saveCurrentSettings(
                        mutate = {
                            it.copy(
                                tuningTier = ClickGlobalSettings.TIER_CUSTOM,
                                defaultIntervalMs = clamped,
                                customIntervalMs = clamped
                            )
                        },
                        syncTapPresets = true
                    )
                }
            },
            onPressChange = { value, commit ->
                val clamped = value.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                uiModel = uiModel.copy(
                    tuningTier = ClickGlobalSettings.TIER_CUSTOM,
                    pressDurationMs = clamped
                )
                if (commit) {
                    saveCurrentSettings(
                        mutate = {
                            it.copy(
                                tuningTier = ClickGlobalSettings.TIER_CUSTOM,
                                defaultPressDurationMs = clamped,
                                customPressDurationMs = clamped
                            )
                        },
                        syncTapPresets = true
                    )
                }
            },
            onMicroJitterChange = { enabled ->
                saveCurrentSettings({ it.copy(defaultMicroJitter = enabled) })
            },
            onJitterRadiusChange = { radius, commit ->
                val clamped = radius.coerceIn(0f, 16f)
                uiModel = uiModel.copy(jitterRadiusPx = clamped)
                if (commit) {
                    saveCurrentSettings({ it.copy(jitterRadiusPx = clamped) })
                }
            },
            onLockedAlphaChange = { alpha, commit ->
                val clamped = alpha.coerceIn(
                    ClickGlobalSettings.MIN_LOCKED_ALPHA,
                    ClickGlobalSettings.MAX_LOCKED_ALPHA
                )
                uiModel = uiModel.copy(lockedAnchorAlpha = clamped)
                if (commit) {
                    saveCurrentSettings({ it.copy(lockedAnchorAlpha = clamped) })
                }
            },
            onAutoLockChange = { enabled ->
                saveCurrentSettings({ it.copy(autoLockOnStart = enabled) })
            },
            onAutoCollapseChange = { enabled ->
                saveCurrentSettings({ it.copy(autoCollapseBar = enabled) })
            },
            onApplyDefaultsToGlobal = {
                val settings = ClickConfigStore.loadSettings(this)
                ClickConfigStore.applyGlobalTapSettingsToAllPresets(this, settings)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
                showMessage(scope, "已将连点设置应用到全部方案")
            },
            onSelectPreset = { presetId ->
                ClickConfigStore.setActivePresetId(this, presetId)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onCyclePreset = { delta ->
                val presets = ClickConfigStore.loadPresets(this)
                if (presets.size > 1) {
                    val activeId = ClickConfigStore.getActivePresetId(this)
                    val idx = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                    val nextIdx = Math.floorMod(idx + delta, presets.size)
                    ClickConfigStore.setActivePresetId(this, presets[nextIdx].id)
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                }
            },
            onCreatePreset = { name, copyCurrent ->
                val baseTargets = if (copyCurrent) {
                    ClickConfigStore.getActivePreset(this).targets
                } else {
                    emptyList()
                }
                val mode = if (copyCurrent) {
                    ClickConfigStore.getActivePreset(this).executionMode
                } else {
                    ExecutionMode.CONCURRENT
                }
                ClickConfigStore.createPreset(this, name, baseTargets, mode, activate = true)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onRenamePreset = { presetId, newName ->
                ClickConfigStore.renamePreset(this, presetId, newName)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onDeletePreset = { presetId ->
                if (ClickConfigStore.deletePreset(this, presetId)) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                }
            },
            onSetPresetExecutionMode = { presetId, mode ->
                ClickConfigStore.setPresetExecutionMode(this, presetId, mode)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onSetPresetLoopCondition = { presetId, loopCount, durationSec ->
                ClickConfigStore.setPresetLoopCondition(this, presetId, loopCount, durationSec)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onExportPresetJson = { preset ->
                val json = ClickConfigStore.exportPresetToJson(preset)
                copyToClipboard(scope, "方案「${preset.name}」JSON", json)
            },
            onImportPresetJson = { rawJson ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val imported = ClickConfigStore.importPresetFromJson(
                    this,
                    rawJson,
                    metrics.widthPx,
                    metrics.heightPx
                )
                if (imported != null) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "成功导入方案「${imported.name}」")
                } else {
                    showMessage(scope, "JSON 解析失败，请检查内容格式")
                }
            },
            onSavePresetTarget = { targetId, x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val settings = ClickConfigStore.loadSettings(this)
                val current = ClickConfigStore.loadTargetsForScreen(this, metrics.widthPx, metrics.heightPx)
                val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
                val resolvedInterval = if (actionType == ActionType.TAP) settings.defaultIntervalMs else intervalMs
                val resolvedPress = if (actionType == ActionType.TAP) settings.defaultPressDurationMs else pressMs
                val safeRepeat = repeatCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                if (targetId == null) {
                    val t = ClickTarget(
                        id = nextId,
                        index = current.size + 1,
                        x = x,
                        y = y,
                        intervalMs = resolvedInterval,
                        pressDurationMs = resolvedPress,
                        microJitter = jitter,
                        enabled = enabled,
                        actionType = actionType,
                        endX = endX,
                        endY = endY,
                        swipeDurationMs = swipeDur,
                        repeatCount = safeRepeat
                    ).apply {
                        setStartPixel(x, y, metrics.widthPx, metrics.heightPx)
                        setEndPixel(endX, endY, metrics.widthPx, metrics.heightPx)
                    }
                    current += t
                } else {
                    val idx = current.indexOfFirst { it.id == targetId }
                    if (idx >= 0) {
                        val updated = current[idx].copy(
                            intervalMs = resolvedInterval,
                            pressDurationMs = resolvedPress,
                            microJitter = jitter,
                            enabled = enabled,
                            actionType = actionType,
                            swipeDurationMs = swipeDur,
                            repeatCount = safeRepeat
                        ).apply {
                            setStartPixel(x, y, metrics.widthPx, metrics.heightPx)
                            setEndPixel(endX, endY, metrics.widthPx, metrics.heightPx)
                        }
                        current[idx] = updated
                    }
                }
                ClickConfigStore.saveTargets(this, current)
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onTogglePresetTargetEnabled = { targetId, enabled ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val current = ClickConfigStore.loadTargetsForScreen(this, metrics.widthPx, metrics.heightPx)
                val idx = current.indexOfFirst { it.id == targetId }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(enabled = enabled)
                    ClickConfigStore.saveTargets(this, current)
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                }
            },
            onDeletePresetTarget = { targetId ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val current = ClickConfigStore.loadTargetsForScreen(this, metrics.widthPx, metrics.heightPx)
                if (current.removeAll { it.id == targetId }) {
                    ClickConfigStore.saveTargets(this, current)
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                }
            },
            onThemeModeChange = { mode ->
                saveCurrentSettings({ it.copy(themeMode = mode) }, notifyOverlay = true)
            },
            onDynamicColorChange = { enabled ->
                saveCurrentSettings({ it.copy(dynamicColor = enabled) }, notifyOverlay = true)
            },
            onVolumeKeyToggleChange = { enabled ->
                saveCurrentSettings({ it.copy(volumeKeyToggleEnabled = enabled) }, notifyOverlay = false)
                scope.launch(Dispatchers.IO) {
                    ShizukuController.setVolumeKeyControlEnabled(enabled)
                }
            },
            onRequestAddQuickTile = {
                requestAddQuickSettingsTile(scope)
            },
            onProModeChange = { enabled ->
                saveCurrentSettings(
                    mutate = {
                        if (enabled) it.copy(proModeEnabled = true)
                        else it.copy(proModeEnabled = false, rpcBridgeEnabled = false)
                    },
                    notifyOverlay = false,
                    syncRpc = true
                )
                if (enabled) {
                    showMessage(scope, "已解锁极客专业模式")
                }
            },
            onPreferredBackendChange = { backend ->
                saveCurrentSettings({ it.copy(preferredBackend = backend) }, notifyOverlay = false)
                scope.launch(Dispatchers.IO) {
                    ShizukuController.setPreferredBackend(backend, reinitIfActive = false)
                }
                showMessage(scope, "下次启停将优先使用选定注入后端")
            },
            onRpcBridgeEnabledChange = { enabled ->
                saveCurrentSettings(
                    mutate = { it.copy(rpcBridgeEnabled = enabled) },
                    notifyOverlay = false,
                    syncRpc = true
                )
                showMessage(
                    scope,
                    if (enabled) "自动化 RPC 接口桥已开启 (127.0.0.1:${LocalRpcServer.RPC_PORT})"
                    else "自动化 RPC 接口桥已关闭"
                )
            },
            onResetRpcToken = {
                val nextToken = ClickConfigStore.generateRpcToken()
                saveCurrentSettings(
                    mutate = { it.copy(rpcToken = nextToken) },
                    notifyOverlay = false,
                    syncRpc = true
                )
                showMessage(scope, "已重置 Access Token")
            },
            onCopyText = { label, text ->
                copyToClipboard(scope, label, text)
            }
        )
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    @SuppressLint("WrongConstant")
    private fun requestAddQuickSettingsTile(scope: kotlinx.coroutines.CoroutineScope) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = getSystemService(StatusBarManager::class.java)
            if (sbm == null) {
                showMessage(scope, "当前系统不支持自动添加磁贴，请在下拉栏手动编辑添加")
                return
            }
            try {
                sbm.requestAddTileService(
                    ComponentName(this, QuickTileService::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    mainExecutor
                ) { resultCode ->
                    val msg = when (resultCode) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到下拉快捷设置磁贴"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已在状态栏中"
                        else -> "已取消添加（或可在下拉栏手工添加）"
                    }
                    showMessage(scope, msg)
                }
            } catch (_: Throwable) {
                showMessage(scope, "请在系统下拉通知栏点击编辑，添加「零断触悬浮窗」磁贴")
            }
        } else {
            showMessage(scope, "请下拉系统状态栏点击编辑，添加「零断触悬浮窗」快捷磁贴")
        }
    }

    companion object {
        private const val SELF_CHECK_TIMEOUT_MS = 5000L
    }
}
