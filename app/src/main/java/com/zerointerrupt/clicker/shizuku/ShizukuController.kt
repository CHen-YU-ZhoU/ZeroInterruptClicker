package com.zerointerrupt.clicker.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.ITouchMuxCallback
import com.zerointerrupt.clicker.ITouchMuxService
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.model.RawMacroCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

enum class ShizukuConnectionState {
    SHIZUKU_NOT_RUNNING,
    NO_PERMISSION,
    BINDING,
    CONNECTED,
    ERROR
}

data class EngineSnapshot(
    val engineMode: Int = TouchMuxUserService.MODE_IDLE,
    val preferredBackend: Int = ClickGlobalSettings.BACKEND_AUTO,
    val uinputWritable: Boolean = false,
    val uinputErrno: Int = -1,
    val uhidWritable: Boolean = false,
    val uhidErrno: Int = -1,
    val touchDeviceReadable: Boolean = false,
    val touchErrno: Int = -1,
    val fallbackReady: Boolean = false,
    val touchDeviceInfo: String = "",
    val absRangeText: String = "",
    val statusDetail: String = "未连接 Shizuku 服务",
    val uid: Int = -1,
    val pid: Int = -1,
    val nativeProbeError: String = ""
)

data class DisplayScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val rotation: Int
)

data class RootConnectResult(
    val success: Boolean,
    val message: String
)

/**
 * Process-wide privileged TouchMux service controller supporting both
 * Shizuku UserService (UID 2000/0) and direct Root `app_process` daemon (UID 0).
 */
object ShizukuController {
    private const val TAG = "ShizukuController"
    const val REQUEST_CODE_SHIZUKU = 10042

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()

    private var appContext: Context? = null
    private var listenersRegistered = false
    private var boundArgs: Shizuku.UserServiceArgs? = null
    @Volatile
    private var service: ITouchMuxService? = null
    @Volatile
    private var serviceSourceMode: Int = -1

    private val rootClientDeathSentinel = Binder()
    private val rootHandshakeLock = Any()
    private var pendingRootToken: String? = null
    private var pendingRootDeferred: CompletableDeferred<IBinder>? = null
    private val rootConnectInFlight = AtomicBoolean(false)

    private val _activeAuthMode = MutableStateFlow(ClickGlobalSettings.AUTH_MODE_SHIZUKU)
    val activeAuthMode: StateFlow<Int> = _activeAuthMode.asStateFlow()

    private val _rootConnecting = MutableStateFlow(false)
    val rootConnecting: StateFlow<Boolean> = _rootConnecting.asStateFlow()

    private val _rootErrorMessage = MutableStateFlow<String?>(null)
    val rootErrorMessage: StateFlow<String?> = _rootErrorMessage.asStateFlow()

    private val _connectionState = MutableStateFlow(ShizukuConnectionState.SHIZUKU_NOT_RUNNING)
    val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    private val _engineSnapshot = MutableStateFlow(EngineSnapshot())
    val engineSnapshot: StateFlow<EngineSnapshot> = _engineSnapshot.asStateFlow()

    private val _slotStates = MutableStateFlow(List(16) { 0 })
    val slotStates: StateFlow<List<Int>> = _slotStates.asStateFlow()

    private val _volumeKeyToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val volumeKeyToggleEvents: SharedFlow<Unit> = _volumeKeyToggleEvents.asSharedFlow()

    private val _emergencyUngrabEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val emergencyUngrabEvents: SharedFlow<Unit> = _emergencyUngrabEvents.asSharedFlow()

    private val _clickingCompletedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val clickingCompletedEvents: SharedFlow<Unit> = _clickingCompletedEvents.asSharedFlow()

    private val _targetTriggerEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val targetTriggerEvents: SharedFlow<Int> = _targetTriggerEvents.asSharedFlow()

    private val serviceCallback = object : ITouchMuxCallback.Stub() {
        override fun onVolumeKeyToggleRequested() {
            _volumeKeyToggleEvents.tryEmit(Unit)
        }

        override fun onEmergencyUngrabTriggered() {
            _slotStates.value = List(16) { 0 }
            _emergencyUngrabEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }

        override fun onSlotStatesChanged(slots: IntArray?) {
            if (slots != null && slots.size >= 16) {
                _slotStates.value = slots.take(16)
            }
        }

        override fun onClickingCompleted() {
            _clickingCompletedEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }

        override fun onTargetTriggerEvent(eventType: Int) {
            _targetTriggerEvents.tryEmit(eventType)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received.")
        if (_activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
            evaluateShizukuState()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died; resetting service reference.")
        if (_activeAuthMode.value != ClickGlobalSettings.AUTH_MODE_SHIZUKU) return@OnBinderDeadListener
        if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
            service = null
            serviceSourceMode = -1
        }
        _slotStates.value = List(16) { 0 }
        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
        _engineSnapshot.value = _engineSnapshot.value.copy(
            engineMode = TouchMuxUserService.MODE_IDLE,
            statusDetail = "Shizuku 服务已断开，等待重新连接"
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU &&
            _activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_SHIZUKU
        ) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                _connectionState.value = ShizukuConnectionState.NO_PERMISSION
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "用户拒绝了 Shizuku 授权"
                )
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (_activeAuthMode.value != ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                try {
                    boundArgs?.let { Shizuku.unbindUserService(it, this, true) }
                } catch (_: Throwable) {
                }
                return
            }
            if (binder == null || !binder.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.ERROR
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "UserService Binder 无效"
                )
                return
            }
            val svc = ITouchMuxService.Stub.asInterface(binder)
            service = svc
            serviceSourceMode = ClickGlobalSettings.AUTH_MODE_SHIZUKU
            _connectionState.value = ShizukuConnectionState.CONNECTED
            try {
                svc.registerCallback(serviceCallback)
                appContext?.let { ctx ->
                    val settings = ClickConfigStore.loadSettings(ctx)
                    svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                    svc.setPreferredBackend(settings.preferredBackend)
                }
            } catch (_: Throwable) {
            }
            try {
                binder.linkToDeath({
                    Log.w(TAG, "TouchMuxUserService remote process died.")
                    if (service?.asBinder() !== binder) return@linkToDeath
                    service = null
                    serviceSourceMode = -1
                    _slotStates.value = List(16) { 0 }
                    if (_activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                        if (Shizuku.pingBinder() && hasPermission()) {
                            _connectionState.value = ShizukuConnectionState.BINDING
                            bindUserService()
                        } else {
                            _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                        }
                    }
                }, 0)
            } catch (_: Throwable) {
            }
            refreshCapabilitiesAsync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                service = null
                serviceSourceMode = -1
                _slotStates.value = List(16) { 0 }
            }
            if (_activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                if (Shizuku.pingBinder() && hasPermission()) {
                    _connectionState.value = ShizukuConnectionState.BINDING
                } else {
                    _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        val ctx = appContext ?: return
        val savedMode = ClickConfigStore.loadSettings(ctx).authMode
        _activeAuthMode.value = savedMode
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
        }
        evaluateShizukuState()
    }

    fun evaluateShizukuState() {
        val ctx = appContext
        val mode = if (ctx != null) {
            ClickConfigStore.loadSettings(ctx).authMode.also { _activeAuthMode.value = it }
        } else {
            _activeAuthMode.value
        }

        if (mode == ClickGlobalSettings.AUTH_MODE_ROOT) {
            evaluateRootStateInternal()
            return
        }

        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                service = null
                serviceSourceMode = -1
            }
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
            _engineSnapshot.value = EngineSnapshot(statusDetail = "请先启动 Shizuku 服务")
            return
        }
        if (!hasPermission()) {
            _connectionState.value = ShizukuConnectionState.NO_PERMISSION
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Shizuku 已运行，等待授权")
            return
        }
        if (service?.asBinder()?.pingBinder() == true &&
            serviceSourceMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU
        ) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            refreshCapabilitiesAsync()
        } else {
            bindUserService()
        }
    }

    private fun evaluateRootStateInternal() {
        val ctx = appContext ?: return
        if (service?.asBinder()?.pingBinder() == true &&
            serviceSourceMode == ClickGlobalSettings.AUTH_MODE_ROOT
        ) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            refreshCapabilitiesAsync()
            return
        }
        if (rootConnectInFlight.get()) {
            _connectionState.value = ShizukuConnectionState.BINDING
            return
        }
        val settings = ClickConfigStore.loadSettings(ctx)
        if (settings.rootAuthorizedEver) {
            _connectionState.value = ShizukuConnectionState.BINDING
            scope.launch {
                val needOverlayGrant = !Settings.canDrawOverlays(ctx)
                requestRootAuthorizationAndConnect(grantPermissions = needOverlayGrant)
            }
        } else {
            _connectionState.value = ShizukuConnectionState.NO_PERMISSION
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Root 模式待授权")
        }
    }

    /**
     * Called by [RootBinderProvider.call] from UID 0 [RootMuxServerMain] during handshake.
     * Returns the client death-sentinel Binder on valid token match, or null to reject.
     */
    internal fun onRootServiceBinderReceived(token: String, binder: IBinder?): IBinder? {
        if (binder == null || !binder.pingBinder()) return null
        val targetDeferred: CompletableDeferred<IBinder>? = synchronized(rootHandshakeLock) {
            val expected = pendingRootToken
            val def = pendingRootDeferred
            if (expected != null && def != null && token == expected) {
                pendingRootToken = null
                pendingRootDeferred = null
                def
            } else {
                null
            }
        }
        if (targetDeferred == null) {
            Log.w(TAG, "Ignored unexpected Root handshake token")
            return null
        }
        targetDeferred.complete(binder)
        return rootClientDeathSentinel
    }

    /**
     * Requests Root superuser permission once, optionally grants all required Android app
     * permissions in the same `su` session, launches the UID 0 TouchMux daemon, and binds it.
     *
     * If Root authorization fails while Shizuku is already connected, the existing Shizuku
     * connection is preserved intact.
     */
    suspend fun requestRootAuthorizationAndConnect(
        grantPermissions: Boolean = true
    ): RootConnectResult = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext RootConnectResult(
            success = false,
            message = "应用上下文尚未初始化"
        )
        if (!rootConnectInFlight.compareAndSet(false, true)) {
            return@withContext RootConnectResult(
                success = false,
                message = "正在连接 Root 通道，请稍候…"
            )
        }
        _rootConnecting.value = true
        _rootErrorMessage.value = null
        val wasAlreadyRootMode = _activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_ROOT
        if (wasAlreadyRootMode) {
            _connectionState.value = ShizukuConnectionState.BINDING
        }

        val sessionToken = generateRootSessionToken()
        val deferred = CompletableDeferred<IBinder>()
        synchronized(rootHandshakeLock) {
            pendingRootToken = sessionToken
            pendingRootDeferred = deferred
        }

        try {
            val authority = RootBinderProvider.authorityFor(ctx.packageName)
            val launchRes = RootHelper.requestRootGrantAndLaunchDaemon(
                context = ctx,
                authority = authority,
                sessionToken = sessionToken,
                grantAllPermissions = grantPermissions
            )
            if (!launchRes.rootGranted || !launchRes.daemonLaunched) {
                clearPendingRootHandshake(sessionToken)
                _rootErrorMessage.value = launchRes.message
                if (wasAlreadyRootMode && service?.asBinder()?.pingBinder() != true) {
                    _connectionState.value = ShizukuConnectionState.NO_PERMISSION
                    _engineSnapshot.value = EngineSnapshot(statusDetail = launchRes.message)
                }
                return@withContext RootConnectResult(success = false, message = launchRes.message)
            }

            val binder = withTimeoutOrNull(8_500L) {
                deferred.await()
            }
            if (binder == null || !binder.pingBinder()) {
                clearPendingRootHandshake(sessionToken)
                val msg = "Root 已授权，但底层通道握手超时，请重试"
                _rootErrorMessage.value = msg
                if (wasAlreadyRootMode && service?.asBinder()?.pingBinder() != true) {
                    _connectionState.value = ShizukuConnectionState.ERROR
                    _engineSnapshot.value = EngineSnapshot(statusDetail = msg)
                }
                return@withContext RootConnectResult(success = false, message = msg)
            }

            // Release any previously active service from the other channel or old Root daemon
            val previousService = service
            val previousMode = serviceSourceMode
            if (previousMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                unbindShizukuSilently()
            } else if (previousService != null && previousService.asBinder() !== binder) {
                try {
                    previousService.destroy()
                } catch (_: Throwable) {
                }
            }

            ClickConfigStore.saveAuthMode(
                context = ctx,
                mode = ClickGlobalSettings.AUTH_MODE_ROOT,
                markRootAuthorized = true
            )
            _activeAuthMode.value = ClickGlobalSettings.AUTH_MODE_ROOT

            val svc = ITouchMuxService.Stub.asInterface(binder)
            service = svc
            serviceSourceMode = ClickGlobalSettings.AUTH_MODE_ROOT
            _connectionState.value = ShizukuConnectionState.CONNECTED
            _rootErrorMessage.value = null

            try {
                svc.registerCallback(serviceCallback)
                val settings = ClickConfigStore.loadSettings(ctx)
                svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                svc.setPreferredBackend(settings.preferredBackend)
            } catch (_: Throwable) {
            }

            try {
                binder.linkToDeath({
                    Log.w(TAG, "Root TouchMux daemon process died.")
                    if (service?.asBinder() !== binder) return@linkToDeath
                    service = null
                    serviceSourceMode = -1
                    _slotStates.value = List(16) { 0 }
                    if (_activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_ROOT) {
                        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                        _engineSnapshot.value = _engineSnapshot.value.copy(
                            engineMode = TouchMuxUserService.MODE_IDLE,
                            statusDetail = "Root 底层守护进程已断开，点击重连"
                        )
                    }
                }, 0)
            } catch (_: Throwable) {
            }

            // Synchronously probe hardware capabilities once so wizard immediately unlocks 5s calibration
            probeCapabilitiesSyncInternal(ctx, svc)

            RootConnectResult(
                success = true,
                message = "Root 授权成功，权限与 UID 0 原生引擎已就绪"
            )
        } catch (t: Throwable) {
            clearPendingRootHandshake(sessionToken)
            val msg = "Root 通道启动失败: ${t.message ?: "未知错误"}"
            Log.e(TAG, msg, t)
            _rootErrorMessage.value = msg
            if (wasAlreadyRootMode && service?.asBinder()?.pingBinder() != true) {
                _connectionState.value = ShizukuConnectionState.ERROR
                _engineSnapshot.value = EngineSnapshot(statusDetail = msg)
            }
            RootConnectResult(success = false, message = msg)
        } finally {
            _rootConnecting.value = false
            rootConnectInFlight.set(false)
        }
    }

    /**
     * Activates Shizuku mode (disconnecting Root daemon if active) and requests Shizuku permission/binding.
     * Returns false if Shizuku daemon is not running at all.
     */
    fun activateShizukuModeAndRequestPermission(): Boolean {
        val ctx = appContext ?: return false
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            if (_activeAuthMode.value == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                _engineSnapshot.value = EngineSnapshot(statusDetail = "请先启动 Shizuku 服务")
            }
            return false
        }

        if (_activeAuthMode.value != ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
            disconnectRootServiceSilently()
            ClickConfigStore.saveAuthMode(ctx, ClickGlobalSettings.AUTH_MODE_SHIZUKU)
            _activeAuthMode.value = ClickGlobalSettings.AUTH_MODE_SHIZUKU
            _rootErrorMessage.value = null
        }

        requestPermission()
        return true
    }

    private fun disconnectRootServiceSilently() {
        val oldSvc = service
        if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_ROOT && oldSvc != null) {
            service = null
            serviceSourceMode = -1
            _slotStates.value = List(16) { 0 }
            scope.launch {
                try {
                    oldSvc.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun unbindShizukuSilently() {
        val args = boundArgs
        if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_SHIZUKU) {
            try {
                service?.shutdownEngine()
            } catch (_: Throwable) {
            }
            if (args != null) {
                try {
                    Shizuku.unbindUserService(args, userServiceConnection, true)
                } catch (_: Throwable) {
                }
            }
            service = null
            serviceSourceMode = -1
            _slotStates.value = List(16) { 0 }
        }
    }

    private fun clearPendingRootHandshake(token: String) {
        synchronized(rootHandshakeLock) {
            if (pendingRootToken == token) {
                pendingRootToken = null
                pendingRootDeferred?.cancel()
                pendingRootDeferred = null
            }
        }
    }

    private fun generateRootSessionToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                return
            }
            if (hasPermission()) {
                bindUserService()
                return
            }
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        } catch (t: Throwable) {
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "请求 Shizuku 权限失败: ${t.message}"
            )
        }
    }

    fun bindUserService() {
        val ctx = appContext ?: return
        if (!Shizuku.pingBinder() || !hasPermission()) {
            evaluateShizukuState()
            return
        }
        if (service?.asBinder()?.pingBinder() == true) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            return
        }
        _connectionState.value = ShizukuConnectionState.BINDING
        val args = boundArgs ?: Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, TouchMuxUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("touchmux")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .also { boundArgs = it }

        try {
            Shizuku.bindUserService(args, userServiceConnection)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService error", t)
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "绑定 UserService 失败: ${t.message}"
            )
        }
    }

    fun refreshCapabilitiesAsync() {
        val ctx = appContext ?: return
        val svc = service ?: return
        scope.launch {
            probeCapabilitiesSyncInternal(ctx, svc)
        }
    }

    private fun probeCapabilitiesSyncInternal(ctx: Context, svc: ITouchMuxService) {
        try {
            val jsonStr = svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
            val obj = JSONObject(jsonStr)
            val touchPath = obj.optString("touchDevicePath", "")
            val touchName = obj.optString("touchDeviceName", "")
            val touchInfo = if (touchPath.isNotEmpty()) "$touchPath ($touchName)" else "未检测到可读触控节点"
            val detail = obj.optString("statusDetail", "Ready")
            val uinputOk = obj.optBoolean("uinputWritable", false)
            val uinputErr = obj.optInt("uinputErrno", -1)
            val uhidOk = obj.optBoolean("uhidWritable", false)
            val uhidErr = obj.optInt("uhidErrno", -1)
            val touchOk = obj.optBoolean("touchDeviceReadable", false)
            val touchErr = obj.optInt("touchDeviceErrno", -1)
            val minX = obj.optInt("absMinX", 0)
            val maxX = obj.optInt("absMaxX", 0)
            val minY = obj.optInt("absMinY", 0)
            val maxY = obj.optInt("absMaxY", 0)
            val absText = if (touchOk) "X[$minX..$maxX] Y[$minY..$maxY]" else "-"

            val probeErr = buildString {
                val loadErr = obj.optString("nativeLoadError", "")
                if (loadErr.isNotEmpty()) append(loadErr)
                if (!uinputOk) {
                    if (isNotEmpty()) append("; ")
                    append("/dev/uinput errno=$uinputErr")
                }
                if (!uhidOk) {
                    if (isNotEmpty()) append("; ")
                    append("/dev/uhid errno=$uhidErr")
                }
                if (!touchOk) {
                    if (isNotEmpty()) append("; ")
                    append("/dev/input errno=$touchErr")
                }
            }

            _engineSnapshot.value = EngineSnapshot(
                engineMode = obj.optInt("currentMode", TouchMuxUserService.MODE_IDLE),
                preferredBackend = obj.optInt("preferredBackend", ClickGlobalSettings.BACKEND_AUTO),
                uinputWritable = uinputOk,
                uinputErrno = uinputErr,
                uhidWritable = uhidOk,
                uhidErrno = uhidErr,
                touchDeviceReadable = touchOk,
                touchErrno = touchErr,
                fallbackReady = obj.optBoolean("fallbackInjectorReady", false),
                touchDeviceInfo = touchInfo,
                absRangeText = absText,
                statusDetail = detail,
                uid = obj.optInt("uid", -1),
                pid = obj.optInt("pid", -1),
                nativeProbeError = probeErr
            )
        } catch (t: Throwable) {
            Log.w(TAG, "probeCapabilities failed", t)
        }
    }

    fun startSelfCheckGrab(timeoutMs: Int = 5000): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        val settings = ClickConfigStore.loadSettings(ctx)
        val metrics = getDisplayScreenMetrics(ctx)
        return try {
            val rc = svc.startSelfCheckGrab(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                timeoutMs,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc
        } catch (t: Throwable) {
            Log.e(TAG, "startSelfCheckGrab failed", t)
            -3
        }
    }

    fun confirmSelfCheck(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            val ok = svc.confirmSelfCheck()
            ClickConfigStore.markSelfCheckPassed(ctx, ok)
            refreshCapabilitiesAsync()
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "confirmSelfCheck failed", t)
            false
        }
    }

    fun cancelSelfCheckAndUngrab() {
        val svc = service ?: return
        try {
            svc.cancelSelfCheckAndUngrab()
            _slotStates.value = List(16) { 0 }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun cleanupOrphanProcesses(): Int {
        val svc = service ?: return 0
        return try {
            val count = svc.cleanupOrphanProcesses()
            refreshCapabilitiesAsync()
            count
        } catch (_: Throwable) {
            0
        }
    }

    fun refreshSlotStates() {
        val svc = service ?: return
        scope.launch {
            try {
                val arr = svc.slotStates
                if (arr != null && arr.size >= 16) {
                    _slotStates.value = arr.take(16)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun setVolumeKeyControlEnabled(enabled: Boolean) {
        val svc = service ?: return
        try {
            svc.setVolumeKeyControlEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    fun setTargetTriggerConfig(
        mode: Int,
        zoneXs: FloatArray,
        zoneYs: FloatArray,
        zoneRadii: FloatArray,
        excludeLeft: Int,
        excludeTop: Int,
        excludeRight: Int,
        excludeBottom: Int,
        touchSlopPx: Float
    ) {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            if (mode != ClickGlobalSettings.TARGET_TRIGGER_OFF && _engineSnapshot.value.touchDeviceInfo.isEmpty()) {
                svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
            }
            val metrics = getDisplayScreenMetrics(ctx)
            svc.setTargetTriggerConfig(
                mode,
                zoneXs,
                zoneYs,
                zoneRadii,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                excludeLeft,
                excludeTop,
                excludeRight,
                excludeBottom,
                touchSlopPx
            )
        } catch (_: Throwable) {
        }
    }

    private fun clearTargetTriggerConfigInternal() {
        val svc = service ?: return
        try {
            svc.setTargetTriggerConfig(
                ClickGlobalSettings.TARGET_TRIGGER_OFF,
                FloatArray(0),
                FloatArray(0),
                FloatArray(0),
                1080,
                2400,
                0,
                0,
                0,
                0,
                0,
                48f
            )
        } catch (_: Throwable) {
        }
    }

    fun clearTargetTriggerConfigAsync() {
        if (service == null) return
        scope.launch {
            clearTargetTriggerConfigInternal()
        }
    }

    fun releaseOverlayEngineAsync(shouldReleaseHardware: () -> Boolean) {
        if (service == null) return
        scope.launch {
            clearTargetTriggerConfigInternal()
            stopClicking()
            if (shouldReleaseHardware()) {
                shutdownEngine()
            }
        }
    }

    fun setPreferredBackend(backend: Int, reinitIfActive: Boolean = false) {
        val ctx = appContext
        val svc = service ?: return
        try {
            svc.setPreferredBackend(backend)
            if (reinitIfActive && ctx != null) {
                val settings = ClickConfigStore.loadSettings(ctx)
                val metrics = getDisplayScreenMetrics(ctx)
                if (settings.selfCheckPassed) {
                    svc.initNativeEngine(
                        ctx.applicationInfo.nativeLibraryDir,
                        metrics.widthPx,
                        metrics.heightPx,
                        metrics.rotation,
                        backend
                    )
                }
            }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun ensureNativeIfVerified(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        val settings = ClickConfigStore.loadSettings(ctx)
        if (!settings.selfCheckPassed) return false
        if (settings.preferredBackend == ClickGlobalSettings.BACKEND_FALLBACK) return false
        return try {
            val curMode = svc.engineMode
            val matchesPref = settings.preferredBackend == ClickGlobalSettings.BACKEND_AUTO ||
                curMode == settings.preferredBackend
            if (TouchMuxUserService.isNativeMode(curMode) && matchesPref) return true
            val metrics = getDisplayScreenMetrics(ctx)
            val rc = svc.initNativeEngine(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun startClicking(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        return try {
            val snapshot = _engineSnapshot.value
            val settings = ClickConfigStore.loadSettings(ctx)
            if (settings.selfCheckPassed &&
                settings.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK &&
                snapshot.touchDeviceReadable &&
                (snapshot.uinputWritable || snapshot.uhidWritable || snapshot.fallbackReady)
            ) {
                ensureNativeIfVerified()
            }
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            if (targets == null && activePreset.isRawMacro) {
                return startRawMacro(
                    rawMacroJson = activePreset.rawMacroJson,
                    loopCount = effectiveLoopCount
                )
            }
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            if (parceled.isEmpty()) return -4
            val mode = svc.startClicking(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                true,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx,
                settings.preferredBackend
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = if (mode > 0) mode else TouchMuxUserService.MODE_IDLE,
                preferredBackend = settings.preferredBackend,
                statusDetail = svc.statusDetail
            )
            mode
        } catch (t: Throwable) {
            Log.e(TAG, "startClicking failed", t)
            -3
        }
    }

    fun startRawMacro(
        rawMacroJson: String,
        loopCount: Int = 0
    ): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        val plan = RawMacroCodec.buildPlaybackPlan(rawMacroJson) ?: return -4
        return try {
            val snapshot = _engineSnapshot.value
            val settings = ClickConfigStore.loadSettings(ctx)
            if (settings.selfCheckPassed &&
                settings.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK &&
                snapshot.touchDeviceReadable &&
                (snapshot.uinputWritable || snapshot.uhidWritable || snapshot.fallbackReady)
            ) {
                ensureNativeIfVerified()
            }
            val metrics = getDisplayScreenMetrics(ctx)
            val mode = svc.startRawMacro(
                plan.timesMs,
                plan.fingerIds,
                plan.eventKinds,
                plan.normXs,
                plan.normYs,
                plan.totalDurationMs,
                loopCount.coerceIn(0, 9999),
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                true,
                settings.preferredBackend
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = if (mode > 0) mode else TouchMuxUserService.MODE_IDLE,
                preferredBackend = settings.preferredBackend,
                statusDetail = svc.statusDetail
            )
            mode
        } catch (t: Throwable) {
            Log.e(TAG, "startRawMacro failed", t)
            -3
        }
    }

    fun updateTargets(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ) {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val settings = ClickConfigStore.loadSettings(ctx)
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            svc.updateTargets(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx
            )
        } catch (t: Throwable) {
            Log.w(TAG, "updateTargets failed", t)
        }
    }

    fun updateDisplayMetrics() {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val metrics = getDisplayScreenMetrics(ctx)
            svc.updateDisplayMetrics(metrics.widthPx, metrics.heightPx, metrics.rotation)
        } catch (_: Throwable) {
        }
    }

    fun stopClicking() {
        val svc = service ?: return
        try {
            svc.stopClicking()
            _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = svc.statusDetail)
        } catch (t: Throwable) {
            Log.w(TAG, "stopClicking failed", t)
        }
    }

    fun shutdownEngine() {
        val svc = service ?: return
        try {
            svc.shutdownEngine()
            _slotStates.value = List(16) { 0 }
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = svc.statusDetail
            )
        } catch (_: Throwable) {
        }
    }

    fun rpcTap(x: Float, y: Float, durationMs: Long = 25L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcTap(x, y, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcSwipe(x1, y1, x2, y2, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcHold(holdId: Int, down: Boolean, x: Float, y: Float): Boolean {
        val svc = service ?: return false
        return try {
            svc.setRpcHold(holdId, down, x, y)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun startTouchRecording(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            // Ensure libtouch_mux.so is loaded inside UserService before recording
            if (_engineSnapshot.value.touchDeviceInfo.isEmpty()) {
                svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
            }
            val metrics = getDisplayScreenMetrics(ctx)
            svc.startTouchRecording(
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                excludeLeft,
                excludeTop,
                excludeRight,
                excludeBottom
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startTouchRecording failed", t)
            false
        }
    }

    fun updateRecordingExcludeRect(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int) {
        val svc = service ?: return
        try {
            svc.updateRecordingExcludeRect(excludeLeft, excludeTop, excludeRight, excludeBottom)
        } catch (_: Throwable) {
        }
    }

    fun getTouchRecordingStats(): LongArray {
        val svc = service ?: return longArrayOf(0L, 0L, 0L, 0L)
        return try {
            svc.touchRecordingStats ?: longArrayOf(0L, 0L, 0L, 0L)
        } catch (_: Throwable) {
            longArrayOf(0L, 0L, 0L, 0L)
        }
    }

    fun stopTouchRecording(): String {
        val svc = service ?: return ""
        return try {
            svc.stopTouchRecording().orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "stopTouchRecording failed", t)
            ""
        }
    }

    fun cancelTouchRecording() {
        val svc = service ?: return
        try {
            svc.cancelTouchRecording()
        } catch (_: Throwable) {
        }
    }

    fun launchActivityViaShell(
        packageName: String,
        className: String,
        openTab: String
    ): Boolean {
        val svc = service ?: return false
        return try {
            svc.launchActivityViaShell(packageName, className, openTab)
        } catch (t: Throwable) {
            Log.w(TAG, "launchActivityViaShell failed", t)
            false
        }
    }

    fun unbindUserService() {
        if (serviceSourceMode == ClickGlobalSettings.AUTH_MODE_ROOT) {
            disconnectRootServiceSilently()
            return
        }
        val args = boundArgs ?: return
        try {
            service?.shutdownEngine()
        } catch (_: Throwable) {
        }
        try {
            Shizuku.unbindUserService(args, userServiceConnection, true)
        } catch (_: Throwable) {
        }
        service = null
        serviceSourceMode = -1
        _slotStates.value = List(16) { 0 }
    }

    fun isServiceConnected(): Boolean = service?.asBinder()?.pingBinder() == true

    @Suppress("DEPRECATION")
    fun getDisplayScreenMetrics(context: Context): DisplayScreenMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            DisplayScreenMetrics(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), rotation)
        } else {
            val metrics = DisplayMetrics()
            display?.getRealMetrics(metrics)
            DisplayScreenMetrics(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), rotation)
        }
    }
}
