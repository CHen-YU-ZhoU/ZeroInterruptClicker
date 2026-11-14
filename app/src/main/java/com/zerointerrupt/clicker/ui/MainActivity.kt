package com.zerointerrupt.clicker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.overlay.OverlayService
import com.zerointerrupt.clicker.overlay.OverlayStateHub
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState
import com.zerointerrupt.clicker.shizuku.ShizukuController
import com.zerointerrupt.clicker.ui.theme.ZeroInterruptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var overlayPermGranted by mutableStateOf(false)
    private var notifPermGranted by mutableStateOf(false)
    private var selfCheckPassed by mutableStateOf(false)
    private var intervalMs by mutableLongStateOf(ClickTarget.DEFAULT_INTERVAL_MS)
    private var pressDurationMs by mutableLongStateOf(ClickTarget.DEFAULT_PRESS_DURATION_MS)
    private var microJitter by mutableStateOf(true)
    private var autoLockOnStart by mutableStateOf(true)
    private var autoCollapseBar by mutableStateOf(true)
    private var selfCheckRemainingMs by mutableLongStateOf(0L)

    private var presets by mutableStateOf<List<ClickPreset>>(emptyList())
    private var activePresetId by mutableStateOf("")
    private var screenWidthPx by mutableIntStateOf(1080)
    private var screenHeightPx by mutableIntStateOf(2400)

    private var selfCheckTimer: CountDownTimer? = null
    private val snackbarHostState = SnackbarHostState()

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshLocalPermissionsAndSettings()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshLocalPermissionsAndSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ShizukuController.initialize(this)
        loadSettingsIntoState()
        reloadPresetsState()
        refreshPermissionStates()

        setContent {
            val connState by ShizukuController.connectionState.collectAsStateWithLifecycle()
            val engineSnap by ShizukuController.engineSnapshot.collectAsStateWithLifecycle()
            val overlayVisible by OverlayStateHub.overlayVisible.collectAsStateWithLifecycle()
            val clickingRunning by OverlayStateHub.clickingRunning.collectAsStateWithLifecycle()
            val configVersion by OverlayStateHub.configVersion.collectAsStateWithLifecycle()

            LaunchedEffect(configVersion) {
                reloadPresetsState()
            }

            val uiState = MainUiState(
                connectionState = connState,
                engineSnapshot = engineSnap,
                overlayVisible = overlayVisible,
                clickingRunning = clickingRunning,
                overlayPermGranted = overlayPermGranted,
                notifPermGranted = notifPermGranted,
                showNotifRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                selfCheckPassed = selfCheckPassed,
                intervalMs = intervalMs,
                pressDurationMs = pressDurationMs,
                microJitter = microJitter,
                autoLockOnStart = autoLockOnStart,
                autoCollapseBar = autoCollapseBar,
                selfCheckRemainingMs = selfCheckRemainingMs,
                selfCheckTimeoutMs = SELF_CHECK_TIMEOUT_MS.toLong(),
                presets = presets,
                activePresetId = activePresetId,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )

            val callbacks = MainScreenCallbacks(
                onToggleOverlay = ::toggleOverlayService,
                onGrantOverlay = ::requestOverlayPermission,
                onGrantShizuku = ::handleShizukuAction,
                onGrantNotif = ::requestNotificationPermission,
                onStartSelfCheck = ::launchTouchSelfCheckFlow,
                onConfirmSelfCheck = ::confirmTouchSelfCheck,
                onRollbackSelfCheck = ::rollbackTouchSelfCheck,
                onReleaseNative = ::releaseNativeGrab,
                onIntervalChange = { value, commit ->
                    intervalMs = value.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                    persistGlobalSettingsAuto(notifyOverlay = commit)
                },
                onPressChange = { value, commit ->
                    pressDurationMs = value.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    persistGlobalSettingsAuto(notifyOverlay = commit)
                },
                onJitterChange = { checked ->
                    microJitter = checked
                    persistGlobalSettingsAuto(notifyOverlay = true)
                },
                onAutoLockChange = { checked ->
                    autoLockOnStart = checked
                    persistGlobalSettingsAuto(notifyOverlay = true)
                },
                onAutoCollapseChange = { checked ->
                    autoCollapseBar = checked
                    persistGlobalSettingsAuto(notifyOverlay = true)
                },
                onApplyDefaultsToCurrentPreset = ::applyDefaultsToCurrentPresetTargets,
                onSelectPreset = ::selectPreset,
                onCreatePreset = ::createPreset,
                onRenamePreset = ::renamePreset,
                onDeletePreset = ::deletePreset,
                onSavePresetTarget = ::upsertPresetTarget,
                onTogglePresetTargetEnabled = ::togglePresetTargetEnabled,
                onDeletePresetTarget = ::deletePresetTarget
            )

            ZeroInterruptTheme {
                MainScreen(
                    state = uiState,
                    callbacks = callbacks,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalPermissionsAndSettings()
        reloadPresetsState()
        ShizukuController.evaluateShizukuState()
    }

    override fun onDestroy() {
        selfCheckTimer?.cancel()
        selfCheckTimer = null
        super.onDestroy()
    }

    private fun loadSettingsIntoState() {
        val s = ClickConfigStore.loadSettings(this)
        intervalMs = s.defaultIntervalMs
        pressDurationMs = s.defaultPressDurationMs
        microJitter = s.defaultMicroJitter
        autoLockOnStart = s.autoLockOnStart
        autoCollapseBar = s.autoCollapseBar
        selfCheckPassed = s.selfCheckPassed
    }

    private fun reloadPresetsState() {
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        screenWidthPx = metrics.widthPx.coerceAtLeast(1)
        screenHeightPx = metrics.heightPx.coerceAtLeast(1)
        presets = ClickConfigStore.loadPresets(this)
        activePresetId = ClickConfigStore.getActivePresetId(this)
    }

    private fun refreshPermissionStates() {
        overlayPermGranted = Settings.canDrawOverlays(this)
        notifPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun refreshLocalPermissionsAndSettings() {
        refreshPermissionStates()
        selfCheckPassed = ClickConfigStore.loadSettings(this).selfCheckPassed
    }

    private fun showSnackbar(message: String) {
        lifecycleScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    private fun handleShizukuAction() {
        when (ShizukuController.connectionState.value) {
            ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> {
                ShizukuController.evaluateShizukuState()
                if (ShizukuController.connectionState.value == ShizukuConnectionState.SHIZUKU_NOT_RUNNING) {
                    val shizukuLaunch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (shizukuLaunch != null) {
                        startActivity(shizukuLaunch)
                    } else {
                        showSnackbar("未检测到 Shizuku App，请先安装并激活 Shizuku")
                    }
                }
            }
            ShizukuConnectionState.NO_PERMISSION -> ShizukuController.requestPermission()
            else -> {
                ShizukuController.evaluateShizukuState()
                showSnackbar("已刷新 Shizuku 硬件能力探测")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleOverlayService() {
        if (OverlayStateHub.overlayVisible.value) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP_OVERLAY
            })
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showSnackbar("请先开启「悬浮窗显示权限」")
            return
        }
        if (!ShizukuController.isServiceConnected()) {
            ShizukuController.evaluateShizukuState()
            showSnackbar("请先启动并授权 Shizuku 服务")
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_OVERLAY
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun persistGlobalSettingsAuto(notifyOverlay: Boolean) {
        val clampedInterval = intervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val clampedPress = pressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        val old = ClickConfigStore.loadSettings(this)
        ClickConfigStore.saveSettings(
            this,
            ClickGlobalSettings(
                defaultIntervalMs = clampedInterval,
                defaultPressDurationMs = clampedPress,
                defaultMicroJitter = microJitter,
                jitterRadiusPx = old.jitterRadiusPx,
                selfCheckPassed = old.selfCheckPassed,
                autoLockOnStart = autoLockOnStart,
                autoCollapseBar = autoCollapseBar
            )
        )
        if (notifyOverlay) {
            notifyOverlayRefreshSettings()
        }
    }

    private fun applyDefaultsToCurrentPresetTargets() {
        val targets = ClickConfigStore.loadTargets(this)
        if (targets.isEmpty()) {
            showSnackbar("当前方案暂无连点位，请先添加点位")
            return
        }
        for (t in targets) {
            t.intervalMs = intervalMs
            t.pressDurationMs = pressDurationMs
            t.microJitter = microJitter
        }
        ClickConfigStore.saveTargets(this, targets)
        reloadPresetsState()
        notifyOverlayRefreshSettings()
        showSnackbar("已自动应用至当前方案的 ${targets.size} 个点位")
    }

    private fun selectPreset(presetId: String) {
        val preset = ClickConfigStore.setActivePresetId(this, presetId)
        reloadPresetsState()
        notifyOverlayRefreshSettings()
        showSnackbar("已切换至「${preset.name}」")
    }

    private fun createPreset(name: String, copyCurrent: Boolean) {
        val initialTargets = if (copyCurrent) ClickConfigStore.loadTargets(this) else emptyList()
        val created = ClickConfigStore.createPreset(this, name, initialTargets, activate = true)
        reloadPresetsState()
        notifyOverlayRefreshSettings()
        showSnackbar("已新建并启用「${created.name}」")
    }

    private fun renamePreset(presetId: String, newName: String) {
        if (ClickConfigStore.renamePreset(this, presetId, newName)) {
            reloadPresetsState()
            notifyOverlayRefreshSettings()
        }
    }

    private fun deletePreset(presetId: String) {
        if (ClickConfigStore.deletePreset(this, presetId)) {
            reloadPresetsState()
            notifyOverlayRefreshSettings()
            showSnackbar("已删除方案")
        } else {
            showSnackbar("至少保留 1 个预设方案")
        }
    }

    private fun upsertPresetTarget(
        existingTargetId: Int?,
        x: Float,
        y: Float,
        targetIntervalMs: Long,
        targetPressMs: Long,
        targetJitter: Boolean,
        targetEnabled: Boolean
    ) {
        val clampedX = x.coerceIn(0f, (screenWidthPx - 1).coerceAtLeast(1).toFloat())
        val clampedY = y.coerceIn(0f, (screenHeightPx - 1).coerceAtLeast(1).toFloat())
        val clampedInterval = targetIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val clampedPress = targetPressMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)

        val list = ClickConfigStore.loadTargets(this)
        val idx = existingTargetId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) {
            val t = list[idx]
            t.x = clampedX
            t.y = clampedY
            t.intervalMs = clampedInterval
            t.pressDurationMs = clampedPress
            t.microJitter = targetJitter
            t.enabled = targetEnabled
        } else {
            val nextId = (list.maxOfOrNull { it.id } ?: 0) + 1
            list += ClickTarget(
                id = nextId,
                index = list.size + 1,
                x = clampedX,
                y = clampedY,
                intervalMs = clampedInterval,
                pressDurationMs = clampedPress,
                microJitter = targetJitter,
                enabled = targetEnabled
            )
        }
        ClickConfigStore.saveTargets(this, list)
        reloadPresetsState()
        notifyOverlayRefreshSettings()
    }

    private fun togglePresetTargetEnabled(targetId: Int, enabled: Boolean) {
        val list = ClickConfigStore.loadTargets(this)
        val target = list.firstOrNull { it.id == targetId } ?: return
        target.enabled = enabled
        ClickConfigStore.saveTargets(this, list)
        reloadPresetsState()
        notifyOverlayRefreshSettings()
    }

    private fun deletePresetTarget(targetId: Int) {
        val list = ClickConfigStore.loadTargets(this)
        if (list.removeAll { it.id == targetId }) {
            ClickConfigStore.saveTargets(this, list)
            reloadPresetsState()
            notifyOverlayRefreshSettings()
        }
    }

    private fun notifyOverlayRefreshSettings() {
        if (OverlayStateHub.overlayVisible.value) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_SETTINGS
            })
        }
    }

    private fun releaseNativeGrab() {
        lifecycleScope.launch(Dispatchers.IO) {
            ShizukuController.shutdownEngine()
            ShizukuController.refreshCapabilitiesAsync()
        }
        showSnackbar("已释放触控独占")
    }

    private fun launchTouchSelfCheckFlow() {
        if (!ShizukuController.isServiceConnected()) {
            showSnackbar("请先连接 Shizuku 服务")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val rc = ShizukuController.startSelfCheckGrab(SELF_CHECK_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                if (rc != 0) {
                    showSnackbar("无法开启 EVIOCGRAB 自检 (err=$rc)，请确认设备节点权限")
                    return@withContext
                }
                startSelfCheckCountdown()
            }
        }
    }

    private fun startSelfCheckCountdown() {
        selfCheckTimer?.cancel()
        selfCheckRemainingMs = SELF_CHECK_TIMEOUT_MS.toLong()
        selfCheckTimer = object : CountDownTimer(SELF_CHECK_TIMEOUT_MS.toLong(), 100L) {
            override fun onTick(millisUntilFinished: Long) {
                selfCheckRemainingMs = millisUntilFinished.coerceAtLeast(1L)
            }

            override fun onFinish() {
                selfCheckRemainingMs = 0L
                lifecycleScope.launch(Dispatchers.IO) {
                    ShizukuController.cancelSelfCheckAndUngrab()
                }
                ClickConfigStore.markSelfCheckPassed(this@MainActivity, false)
                selfCheckPassed = false
                showSnackbar("5 秒未确认，已自动安全回滚原厂触控")
            }
        }.start()
    }

    private fun confirmTouchSelfCheck() {
        selfCheckTimer?.cancel()
        selfCheckTimer = null
        selfCheckRemainingMs = 0L
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ShizukuController.confirmSelfCheck()
            withContext(Dispatchers.Main) {
                selfCheckPassed = ok
                if (ok) {
                    showSnackbar("自检通过！零断触原生合流通道已解锁")
                } else {
                    showSnackbar("确认超时，内核已自动回滚至原厂触控")
                }
            }
        }
    }

    private fun rollbackTouchSelfCheck() {
        selfCheckTimer?.cancel()
        selfCheckTimer = null
        selfCheckRemainingMs = 0L
        lifecycleScope.launch(Dispatchers.IO) {
            ShizukuController.cancelSelfCheckAndUngrab()
        }
        ClickConfigStore.markSelfCheckPassed(this, false)
        selfCheckPassed = false
        showSnackbar("已手动安全回滚至原厂触控")
    }

    companion object {
        private const val SELF_CHECK_TIMEOUT_MS = 5000
    }
}
