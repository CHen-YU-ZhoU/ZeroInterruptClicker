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
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.zerointerrupt.clicker.update.GitHubReleaseInfo
import com.zerointerrupt.clicker.update.GitHubUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var selfCheckTimer: CountDownTimer? = null
    private lateinit var snackbarHostState: SnackbarHostState
    private var currentActiveTab by mutableStateOf(MainTab.HOME)
    private var requestedTab by mutableStateOf<MainTab?>(null)
    private var pendingUpdateRelease by mutableStateOf<GitHubReleaseInfo?>(null)
    private var isCheckingUpdate: Boolean = false

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
            batteryOptIgnored = true,
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
            showMacroTrajectories = true,
            presets = emptyList(),
            activePresetId = "preset_1",
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            themeMode = ClickGlobalSettings.THEME_SYSTEM,
            dynamicColor = true,
            launcherIconMode = ClickGlobalSettings.LAUNCHER_SYSTEM,
            volumeKeyToggleEnabled = true,
            targetLongPressTriggerEnabled = false,
            targetTapTriggerEnabled = false,
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

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        syncFromStorageAndSystem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenTabIntent(intent)
        reconcileLauncherIconMode()
        ShizukuController.initialize(this)
        LocalRpcServer.syncWithSettings(this)
        syncFromStorageAndSystem()

        setContent {
            val connectionState by ShizukuController.connectionState.collectAsState()
            val activeAuthMode by ShizukuController.activeAuthMode.collectAsState()
            val rootConnecting by ShizukuController.rootConnecting.collectAsState()
            val rootErrorMessage by ShizukuController.rootErrorMessage.collectAsState()
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
                authMode = activeAuthMode,
                rootConnecting = rootConnecting,
                rootErrorMessage = rootErrorMessage,
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

            // Silent auto-check on launch
            LaunchedEffect(Unit) {
                val result = GitHubUpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)
                val release = result.getOrNull()
                if (release != null && release.tagName != GitHubUpdateChecker.getIgnoredTag(this@MainActivity)) {
                    pendingUpdateRelease = release
                }
            }

            ZeroInterruptTheme(
                darkTheme = darkTheme,
                dynamicColor = mergedState.dynamicColor
            ) {
                MainScreen(
                    state = mergedState,
                    callbacks = callbacks,
                    snackbarHostState = snackbarHostState,
                    requestedTab = requestedTab,
                    onConsumeRequestedTab = { requestedTab = null },
                    onCurrentTabChanged = { currentActiveTab = it }
                )

                pendingUpdateRelease?.let { release ->
                    val cs = MaterialTheme.colorScheme
                    AlertDialog(
                        onDismissRequest = { pendingUpdateRelease = null },
                        shape = RoundedCornerShape(24.dp),
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "发现新版本 ${release.tagName}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = cs.secondaryContainer
                                ) {
                                    Text(
                                        text = if (release.viaMirror) {
                                            "v${BuildConfig.VERSION_NAME} → ${release.tagName} · 镜像加速"
                                        } else {
                                            "v${BuildConfig.VERSION_NAME} → ${release.tagName}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (release.releaseName.isNotBlank() && release.releaseName != release.tagName) {
                                    Text(
                                        text = release.releaseName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = release.body.ifBlank { "包含最新功能优化与问题修复，点击下方按钮即可下载安装。" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val targetUrl = release.apkDownloadUrl ?: release.htmlUrl
                                    pendingUpdateRelease = null
                                    openExternalUrl(scope, targetUrl)
                                },
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (release.apkDownloadUrl != null) "下载 APK" else "前往下载",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        GitHubUpdateChecker.setIgnoredTag(this@MainActivity, release.tagName)
                                        pendingUpdateRelease = null
                                    }
                                ) {
                                    Text("忽略此版")
                                }
                                TextButton(onClick = { pendingUpdateRelease = null }) {
                                    Text("稍后")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    private fun handleOpenTabIntent(intent: Intent?) {
        val tabName = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return
        val match = MainTab.entries.firstOrNull { it.name == tabName } ?: return
        requestedTab = match
        intent.removeExtra(EXTRA_OPEN_TAB)
    }

    override fun onResume() {
        super.onResume()
        isResumedInForeground = true
        ShizukuController.evaluateShizukuState()
        LocalRpcServer.syncWithSettings(this)
        syncFromStorageAndSystem()
    }

    override fun onPause() {
        isResumedInForeground = false
        super.onPause()
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
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOptIgnored = pm?.isIgnoringBatteryOptimizations(packageName) ?: true

        presets.forEach { preset ->
            preset.targets.forEach { t -> t.remapToScreen(metrics.widthPx, metrics.heightPx) }
        }

        uiModel = uiModel.copy(
            overlayPermGranted = overlayGranted,
            notifPermGranted = notifGranted,
            batteryOptIgnored = batteryOptIgnored,
            selfCheckPassed = settings.selfCheckPassed,
            tuningTier = settings.tuningTier,
            intervalMs = settings.defaultIntervalMs,
            pressDurationMs = settings.defaultPressDurationMs,
            microJitter = settings.defaultMicroJitter,
            jitterRadiusPx = settings.jitterRadiusPx,
            lockedAnchorAlpha = settings.lockedAnchorAlpha,
            autoLockOnStart = settings.autoLockOnStart,
            autoCollapseBar = settings.autoCollapseBar,
            showMacroTrajectories = settings.showMacroTrajectories,
            presets = presets,
            activePresetId = activeId,
            screenWidthPx = metrics.widthPx,
            screenHeightPx = metrics.heightPx,
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            launcherIconMode = settings.launcherIconMode,
            volumeKeyToggleEnabled = settings.volumeKeyToggleEnabled,
            targetLongPressTriggerEnabled = settings.targetLongPressTriggerEnabled,
            targetTapTriggerEnabled = settings.targetTapTriggerEnabled,
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
            snackbarHostState.currentSnackbarData?.dismiss()
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
                val ok = ShizukuController.activateShizukuModeAndRequestPermission()
                syncFromStorageAndSystem()
                if (!ok) {
                    showMessage(scope, "未检测到 Shizuku 后台服务，请先打开 Shizuku 并启动")
                }
            },
            onGrantRoot = {
                scope.launch {
                    val res = ShizukuController.requestRootAuthorizationAndConnect(grantPermissions = true)
                    syncFromStorageAndSystem()
                    if (res.success) {
                        val settings = ClickConfigStore.loadSettings(this@MainActivity)
                        val overlayOk = Settings.canDrawOverlays(this@MainActivity)
                        val msg = when {
                            !overlayOk -> "Root 通道已连接，请点「悬浮窗」标签补授系统权限"
                            !settings.selfCheckPassed -> "Root 授权成功，请继续完成 5 秒触控校准"
                            else -> "Root 通道 (UID 0) 已就绪"
                        }
                        showMessage(scope, msg)
                    } else {
                        showMessage(scope, res.message)
                    }
                }
            },
            onGrantNotif = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onGrantBatteryOpt = { openBatteryOptimizationSettings(scope) },
            onStartSelfCheck = {
                selfCheckTimer?.cancel()
                scope.launch {
                    val rc = withContext(Dispatchers.IO) {
                        ShizukuController.startSelfCheckGrab(SELF_CHECK_TIMEOUT_MS.toInt())
                    }
                    if (rc < 0) {
                        uiModel = uiModel.copy(selfCheckRemainingMs = 0L)
                        showMessage(scope, "启动 5 秒校准失败（code=$rc），请检查底层通道与设备节点")
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
                            autoCollapseBar = false,
                            showMacroTrajectories = true
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
            onShowMacroTrajectoriesChange = { enabled ->
                saveCurrentSettings({ it.copy(showMacroTrajectories = enabled) }, notifyOverlay = true)
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
                val presets = ClickConfigStore.loadPresets(this).filter { !it.isFrozen }
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
                val active = ClickConfigStore.getActivePreset(this)
                val baseTargets = if (copyCurrent) active.targets else emptyList()
                val mode = if (copyCurrent) active.executionMode else ExecutionMode.CONCURRENT
                ClickConfigStore.createPreset(
                    context = this,
                    name = name,
                    initialTargets = baseTargets,
                    executionMode = mode,
                    presetLoopCount = if (copyCurrent) active.presetLoopCount else 0,
                    isRawMacro = copyCurrent && active.isRawMacro,
                    rawMacroJson = if (copyCurrent && active.isRawMacro) active.rawMacroJson else "",
                    activate = true
                )
                notifyOverlayRefresh()
                syncFromStorageAndSystem()
            },
            onStartMacroRecording = {
                if (!Settings.canDrawOverlays(this)) {
                    openOverlayPermissionSettings()
                    showMessage(scope, "请先授予悬浮窗权限以启用录制控件")
                } else if (!ShizukuController.isServiceConnected()) {
                    ShizukuController.evaluateShizukuState()
                    showMessage(scope, "请先在主页初始化向导中完成 Root 或 Shizuku 授权")
                } else {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_START_RECORDING
                        }
                    )
                    showMessage(scope, "录制控件已开启，切到目标画面后点「就绪」")
                }
            },
            onStartRawMacroRecording = {
                if (!Settings.canDrawOverlays(this)) {
                    openOverlayPermissionSettings()
                    showMessage(scope, "请先授予悬浮窗权限以启用录宏控件")
                } else if (!ShizukuController.isServiceConnected()) {
                    ShizukuController.evaluateShizukuState()
                    showMessage(scope, "请先在主页初始化向导中完成 Root 或 Shizuku 授权")
                } else {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_START_RAW_RECORDING
                        }
                    )
                    showMessage(scope, "录宏控件已开启，切到目标画面后点「就绪」")
                }
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
            onSetPresetLoopCount = { presetId, loopCount ->
                ClickConfigStore.setPresetLoopCount(this, presetId, loopCount)
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
            onImportPresetScript = { rawScript ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val imported = ClickConfigStore.importPresetFromScript(
                    this,
                    rawScript,
                    metrics.widthPx,
                    metrics.heightPx
                )
                if (imported != null) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "已通过脚本创建方案「${imported.name}」")
                } else {
                    showMessage(scope, "脚本语法或 JSON 格式无效，请检查后重试")
                }
            },
            onExportAllPresetsJson = {
                val presets = ClickConfigStore.loadPresets(this)
                val activeId = ClickConfigStore.getActivePresetId(this)
                val json = ClickConfigStore.exportPresetsBundleToJson(presets, activeId)
                copyToClipboard(scope, "全部 ${presets.size} 个方案 JSON", json)
            },
            onBatchImportPresetsJson = { rawJson ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val imported = ClickConfigStore.importPresetsBatchFromJson(
                    this,
                    rawJson,
                    metrics.widthPx,
                    metrics.heightPx
                )
                if (imported.isNotEmpty()) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "成功批量导入 ${imported.size} 个方案")
                } else {
                    showMessage(scope, "批量 JSON 解析失败，请检查内容格式")
                }
            },
            onBatchDeletePresets = { presetIds ->
                val deleted = ClickConfigStore.deletePresetsBatch(this, presetIds)
                if (deleted > 0) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "已删除选中的 $deleted 个方案")
                } else {
                    showMessage(scope, "至少需要保留 1 个方案")
                }
            },
            onApplyPresetFreezeStates = { frozenIds ->
                val changed = ClickConfigStore.applyPresetFreezeStates(this, frozenIds)
                if (changed > 0) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "已更新方案冻结状态（共冻结 ${frozenIds.size} 个，悬浮窗切换时跳过）")
                }
            },
            onBatchCreateEmptyPresets = { count, mode ->
                val created = ClickConfigStore.createEmptyPresetsBatch(this, count, mode)
                if (created.isNotEmpty()) {
                    notifyOverlayRefresh()
                    syncFromStorageAndSystem()
                    showMessage(scope, "已批量添加 ${created.size} 个空方案")
                }
            },
            onSavePresetTarget = { targetId, x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs, triggerTargetZone ->
                val metrics = ShizukuController.getDisplayScreenMetrics(this)
                val settings = ClickConfigStore.loadSettings(this)
                val current = ClickConfigStore.loadTargetsForScreen(this, metrics.widthPx, metrics.heightPx)
                val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
                val resolvedInterval = if (actionType == ActionType.TAP) settings.defaultIntervalMs else intervalMs
                val resolvedPress = if (actionType == ActionType.TAP) settings.defaultPressDurationMs else pressMs
                val safeRepeat = repeatCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                val safeSubDur = subDurationMs.coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS)
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
                        repeatCount = safeRepeat,
                        refPresetId = refPresetId,
                        subDurationMs = safeSubDur,
                        triggerTargetZone = triggerTargetZone
                    ).apply {
                        if (actionType != ActionType.SUB_PRESET) {
                            setStartPixel(x, y, metrics.widthPx, metrics.heightPx)
                            setEndPixel(endX, endY, metrics.widthPx, metrics.heightPx)
                        }
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
                            repeatCount = safeRepeat,
                            refPresetId = refPresetId,
                            subDurationMs = safeSubDur,
                            triggerTargetZone = triggerTargetZone
                        ).apply {
                            if (actionType != ActionType.SUB_PRESET) {
                                setStartPixel(x, y, metrics.widthPx, metrics.heightPx)
                                setEndPixel(endX, endY, metrics.widthPx, metrics.heightPx)
                            }
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
            onLauncherIconModeChange = { mode ->
                switchLauncherIcon(scope, mode)
            },
            onVolumeKeyToggleChange = { enabled ->
                saveCurrentSettings({ it.copy(volumeKeyToggleEnabled = enabled) }, notifyOverlay = false)
                scope.launch(Dispatchers.IO) {
                    ShizukuController.setVolumeKeyControlEnabled(enabled)
                }
            },
            onTargetLongPressTriggerChange = { enabled ->
                val cur = ClickConfigStore.loadSettings(this)
                if (enabled && cur.targetTapTriggerEnabled) {
                    showMessage(scope, "连点和长按仅可打开一个")
                } else {
                    saveCurrentSettings(
                        mutate = { it.copy(targetLongPressTriggerEnabled = enabled) },
                        notifyOverlay = true
                    )
                }
            },
            onTargetTapTriggerChange = { enabled ->
                val cur = ClickConfigStore.loadSettings(this)
                if (enabled && cur.targetLongPressTriggerEnabled) {
                    showMessage(scope, "连点和长按仅可打开一个")
                } else {
                    saveCurrentSettings(
                        mutate = { it.copy(targetTapTriggerEnabled = enabled) },
                        notifyOverlay = true
                    )
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
                    showMessage(scope, "已开启专业模式")
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
            },
            onCheckUpdate = {
                if (!isCheckingUpdate) {
                    isCheckingUpdate = true
                    showMessage(scope, "正在检查最新版本…")
                    scope.launch {
                        try {
                            val result = GitHubUpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)
                            result.onSuccess { release ->
                                if (release != null) {
                                    if (::snackbarHostState.isInitialized) {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                    }
                                    pendingUpdateRelease = release
                                } else {
                                    showMessage(scope, "当前已是最新版本 (v${BuildConfig.VERSION_NAME})")
                                }
                            }.onFailure { err ->
                                showMessage(scope, "检查更新失败：${err.message ?: "网络异常"}")
                            }
                        } finally {
                            isCheckingUpdate = false
                        }
                    }
                }
            }
        )
    }

    private fun resolveActiveAliasFromPackageManager(): Int {
        return try {
            val candidates = listOf(
                "$packageName.LauncherLightAlias" to ClickGlobalSettings.LAUNCHER_LIGHT,
                "$packageName.LauncherDarkAlias" to ClickGlobalSettings.LAUNCHER_DARK,
                "$packageName.LauncherSystemAlias" to ClickGlobalSettings.LAUNCHER_SYSTEM
            )
            for ((cls, mode) in candidates) {
                val st = packageManager.getComponentEnabledSetting(ComponentName(this, cls))
                if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return mode
                }
            }
            ClickGlobalSettings.LAUNCHER_SYSTEM
        } catch (_: Throwable) {
            ClickGlobalSettings.LAUNCHER_SYSTEM
        }
    }

    private fun reconcileLauncherIconMode() {
        val currentSettings = ClickConfigStore.loadSettings(this)
        val resolvedMode = if (ClickConfigStore.hasLauncherIconModePref(this)) {
            currentSettings.launcherIconMode
        } else {
            val detected = resolveActiveAliasFromPackageManager()
            ClickConfigStore.saveSettings(
                this,
                currentSettings.copy(launcherIconMode = detected)
            )
            detected
        }
        applyLauncherIconComponents(resolvedMode)
    }

    private fun applyLauncherIconComponents(mode: Int) {
        val entries = listOf(
            ClickGlobalSettings.LAUNCHER_SYSTEM to ComponentName(this, "$packageName.LauncherSystemAlias"),
            ClickGlobalSettings.LAUNCHER_DARK to ComponentName(this, "$packageName.LauncherDarkAlias"),
            ClickGlobalSettings.LAUNCHER_LIGHT to ComponentName(this, "$packageName.LauncherLightAlias")
        )
        val safeMode = mode.coerceIn(
            ClickGlobalSettings.LAUNCHER_SYSTEM,
            ClickGlobalSettings.LAUNCHER_LIGHT
        )
        for ((entryMode, component) in entries) {
            val desiredState = if (entryMode == safeMode) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (packageManager.getComponentEnabledSetting(component) != desiredState) {
                packageManager.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    private fun switchLauncherIcon(
        scope: kotlinx.coroutines.CoroutineScope,
        mode: Int
    ) {
        val safeMode = mode.coerceIn(
            ClickGlobalSettings.LAUNCHER_SYSTEM,
            ClickGlobalSettings.LAUNCHER_LIGHT
        )
        try {
            applyLauncherIconComponents(safeMode)
            saveCurrentSettings(
                mutate = { it.copy(launcherIconMode = safeMode) },
                notifyOverlay = false
            )
            val modeLabel = when (safeMode) {
                ClickGlobalSettings.LAUNCHER_DARK -> "深色"
                ClickGlobalSettings.LAUNCHER_LIGHT -> "浅色"
                else -> "跟随系统"
            }
            showMessage(scope, "桌面图标已设为「$modeLabel」，返回桌面后生效")
        } catch (t: Throwable) {
            showMessage(scope, "切换图标失败: ${t.message ?: "未知错误"}")
        }
    }

    private fun openExternalUrl(scope: kotlinx.coroutines.CoroutineScope, url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showMessage(scope, "无法打开下载链接")
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings(scope: kotlinx.coroutines.CoroutineScope) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            batteryOptLauncher.launch(intent)
        } catch (_: Throwable) {
            try {
                batteryOptLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                showMessage(scope, "请在系统应用信息中将本应用设为「无限制」省电策略")
            }
        }
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
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val SELF_CHECK_TIMEOUT_MS = 5000L

        @Volatile
        var isResumedInForeground: Boolean = false
    }
}
