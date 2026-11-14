package com.zerointerrupt.clicker.shizuku

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import com.zerointerrupt.clicker.ClickTargetParceled
import com.zerointerrupt.clicker.ITouchMuxService
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * Privileged Shizuku UserService (UID 2000 shell / UID 0 root).
 *
 * Primary path: loads libtouch_mux.so from applicationInfo.nativeLibraryDir and runs
 * single-threaded epoll + EVIOCGRAB + uinput slot-isolated multiplexing.
 *
 * Fallback path: if SELinux/kernel permissions block /dev/uinput or /dev/input/event*,
 * uses in-process IInputManager.injectInputEvent(MotionEvent, ASYNC) without spawning
 * shell processes and without emitting ACTION_CANCEL on stop.
 */
@Keep
class TouchMuxUserService() : ITouchMuxService.Stub() {

    @Suppress("unused")
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val nativeLoaded = AtomicBoolean(false)
    private var nativeLoadError: String? = null

    private val currentMode = AtomicInteger(MODE_IDLE)
    private val clickingActive = AtomicBoolean(false)
    private val selfCheckConfirmed = AtomicBoolean(false)
    private var statusDetail: String = "Idle"

    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var selfCheckWatchdogTask: ScheduledFuture<*>? = null

    // Fallback injector state
    private val fallbackLock = Any()
    private var fallbackTargets: List<ClickTargetParceled> = emptyList()
    private var fallbackDisplayW: Int = 1080
    private var fallbackDisplayH: Int = 2400
    private var fallbackThread: Thread? = null
    private val fallbackRunning = AtomicBoolean(false)

    private var cachedInjectMethod: Method? = null
    private var cachedInputManagerObj: Any? = null

    // JNI declarations matching jni_bridge.cpp
    private external fun nativeProbeCapabilities(): String
    private external fun nativeInitAndGrab(displayW: Int, displayH: Int, rotation: Int, selfCheckTimeoutMs: Int): Int
    private external fun nativeConfirmSelfCheck(): Boolean
    private external fun nativeSetTargetsAndRunning(
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        intervalsMs: LongArray,
        pressDurationsMs: LongArray,
        jitters: BooleanArray,
        enabledFlags: BooleanArray,
        running: Boolean,
        displayW: Int,
        displayH: Int,
        rotation: Int,
        jitterRadiusPx: Float
    )
    private external fun nativeUpdateDisplayMetrics(displayW: Int, displayH: Int, rotation: Int)
    private external fun nativeStopClicking()
    private external fun nativeShutdown()
    private external fun nativeGetState(): Int
    private external fun nativeDidRollback(): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeGetTouchDeviceInfo(): String

    init {
        exemptHiddenApisIfNeeded()
    }

    private fun exemptHiddenApisIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden API exemption notice: ${t.message}")
        }
    }

    private fun ensureNativeLoaded(nativeLibDir: String?): Boolean {
        if (nativeLoaded.get()) return true
        if (nativeLibDir.isNullOrBlank()) {
            nativeLoadError = "nativeLibDir is empty"
            return false
        }
        val soFile = File(nativeLibDir, "libtouch_mux.so")
        if (!soFile.exists()) {
            nativeLoadError = "libtouch_mux.so not found at ${soFile.absolutePath}"
            return false
        }
        return try {
            System.load(soFile.absolutePath)
            nativeLoaded.set(true)
            nativeLoadError = null
            true
        } catch (t: Throwable) {
            nativeLoadError = "System.load failed: ${t.message}"
            Log.e(TAG, nativeLoadError, t)
            false
        }
    }

    private fun ensureFallbackInjectorReady(): Boolean {
        if (cachedInjectMethod != null && cachedInputManagerObj != null) {
            return true
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                val im = asInterface.invoke(null, binder)
                val inject = im.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                cachedInputManagerObj = im
                cachedInjectMethod = inject
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "IInputManager reflection fallback: ${t.message}")
        }

        return try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance")
            val im = getInstance.invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            cachedInputManagerObj = im
            cachedInjectMethod = inject
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize InputManager fallback: ${t.message}", t)
            false
        }
    }

    override fun probeCapabilities(nativeLibDir: String?): String {
        val obj = JSONObject()
        val loaded = ensureNativeLoaded(nativeLibDir)
        obj.put("nativeLibLoaded", loaded)
        obj.put("nativeLoadError", nativeLoadError ?: "")
        obj.put("uid", android.os.Process.myUid())
        obj.put("pid", android.os.Process.myPid())

        if (loaded) {
            try {
                val nativeJson = JSONObject(nativeProbeCapabilities())
                val keys = nativeJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    obj.put(key, nativeJson.get(key))
                }
            } catch (t: Throwable) {
                obj.put("nativeProbeError", t.message ?: "unknown")
            }
        } else {
            obj.put("uinputWritable", false)
            obj.put("touchDeviceReadable", false)
        }

        val fallbackReady = ensureFallbackInjectorReady()
        obj.put("fallbackInjectorReady", fallbackReady)
        obj.put("currentMode", currentMode.get())
        obj.put("statusDetail", statusDetail)
        return obj.toString()
    }

    override fun initNativeEngine(
        nativeLibDir: String?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int
    ): Int {
        if (!ensureNativeLoaded(nativeLibDir)) {
            statusDetail = nativeLoadError ?: "Native load failed"
            return -1001
        }
        val rc = nativeInitAndGrab(displayWidth, displayHeight, rotation, 0)
        if (rc == 0) {
            selfCheckConfirmed.set(true)
            currentMode.set(MODE_NATIVE_MUX)
            statusDetail = "Native TouchMux Active: ${nativeGetTouchDeviceInfo()}"
        } else {
            statusDetail = nativeGetLastError().ifBlank { "Native init failed ($rc)" }
        }
        return rc
    }

    override fun startSelfCheckGrab(
        nativeLibDir: String?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        timeoutMs: Int
    ): Int {
        if (!ensureNativeLoaded(nativeLibDir)) {
            statusDetail = nativeLoadError ?: "Native load failed"
            return -1001
        }
        stopFallbackThread()
        selfCheckConfirmed.set(false)
        cancelWatchdogTask()

        val safeTimeout = timeoutMs.coerceIn(2000, 15000)
        val rc = nativeInitAndGrab(displayWidth, displayHeight, rotation, safeTimeout)
        if (rc != 0) {
            currentMode.set(MODE_IDLE)
            statusDetail = nativeGetLastError().ifBlank { "Native grab failed ($rc)" }
            return rc
        }

        currentMode.set(MODE_NATIVE_MUX)
        statusDetail = "5s Touch Self-Check Pending..."

        // JVM backup watchdog in addition to native CLOCK_MONOTONIC timerfd
        selfCheckWatchdogTask = watchdogExecutor.schedule({
            if (!selfCheckConfirmed.get() && nativeLoaded.get()) {
                Log.w(TAG, "JVM watchdog enforcing self-check rollback.")
                try {
                    nativeShutdown()
                } catch (_: Throwable) {
                }
                if (currentMode.get() == MODE_NATIVE_MUX) {
                    currentMode.set(MODE_IDLE)
                    statusDetail = "Self-check timed out; safely rolled back."
                }
            }
        }, safeTimeout.toLong() + 150L, TimeUnit.MILLISECONDS)

        return 0
    }

    override fun confirmSelfCheck(): Boolean {
        cancelWatchdogTask()
        if (!nativeLoaded.get()) return false
        val ok = nativeConfirmSelfCheck()
        if (ok) {
            selfCheckConfirmed.set(true)
            currentMode.set(MODE_NATIVE_MUX)
            statusDetail = "Native TouchMux Verified: ${nativeGetTouchDeviceInfo()}"
        } else {
            selfCheckConfirmed.set(false)
            currentMode.set(MODE_IDLE)
            statusDetail = if (nativeDidRollback()) {
                "Self-check expired and rolled back automatically."
            } else {
                nativeGetLastError().ifBlank { "Native TouchMux not active." }
            }
        }
        return ok
    }

    override fun cancelSelfCheckAndUngrab() {
        cancelWatchdogTask()
        selfCheckConfirmed.set(false)
        if (nativeLoaded.get()) {
            try {
                nativeShutdown()
            } catch (_: Throwable) {
            }
        }
        if (currentMode.get() == MODE_NATIVE_MUX) {
            currentMode.set(MODE_IDLE)
        }
        statusDetail = "Self-check cancelled; physical touch restored."
    }

    private fun cancelWatchdogTask() {
        selfCheckWatchdogTask?.cancel(false)
        selfCheckWatchdogTask = null
    }

    override fun startClicking(
        targets: MutableList<ClickTargetParceled>?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        allowFallback: Boolean
    ): Int {
        val list = targets ?: emptyList()

        // Prefer Native TouchMux ONLY if already grabbed & verified (never silently grab without self-check)
        val nativeActive = nativeLoaded.get() && nativeGetState() == NATIVE_STATE_RUNNING_GRABBED
        if (nativeActive) {
            stopFallbackThread()
            pushTargetsToNative(list, running = true, displayWidth, displayHeight, rotation)
            currentMode.set(MODE_NATIVE_MUX)
            clickingActive.set(true)
            statusDetail = "Running (Native TouchMux / Zero-Interrupt)"
            return MODE_NATIVE_MUX
        }

        if (!allowFallback) {
            statusDetail = "Native TouchMux not active and fallback disabled."
            return -1002
        }

        if (!ensureFallbackInjectorReady()) {
            statusDetail = "Fallback InputManager injector unavailable."
            return -1003
        }

        synchronized(fallbackLock) {
            fallbackTargets = list.toList()
            fallbackDisplayW = displayWidth.coerceAtLeast(1)
            fallbackDisplayH = displayHeight.coerceAtLeast(1)
        }
        clickingActive.set(true)
        currentMode.set(MODE_SHIZUKU_FALLBACK)
        startOrWakeFallbackThread()
        statusDetail = "Running (Shizuku UID ${android.os.Process.myUid()} Inject Fallback)"
        return MODE_SHIZUKU_FALLBACK
    }

    override fun updateTargets(
        targets: MutableList<ClickTargetParceled>?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int
    ) {
        val list = targets ?: emptyList()
        val running = clickingActive.get()
        if (currentMode.get() == MODE_NATIVE_MUX && nativeLoaded.get()) {
            pushTargetsToNative(list, running, displayWidth, displayHeight, rotation)
        } else {
            synchronized(fallbackLock) {
                fallbackTargets = list.toList()
                fallbackDisplayW = displayWidth.coerceAtLeast(1)
                fallbackDisplayH = displayHeight.coerceAtLeast(1)
            }
        }
    }

    override fun updateDisplayMetrics(displayWidth: Int, displayHeight: Int, rotation: Int) {
        if (nativeLoaded.get()) {
            nativeUpdateDisplayMetrics(displayWidth, displayHeight, rotation)
        }
        synchronized(fallbackLock) {
            fallbackDisplayW = displayWidth.coerceAtLeast(1)
            fallbackDisplayH = displayHeight.coerceAtLeast(1)
        }
    }

    override fun stopClicking() {
        clickingActive.set(false)
        if (currentMode.get() == MODE_NATIVE_MUX && nativeLoaded.get()) {
            nativeStopClicking()
            statusDetail = "Idle (Native TouchMux Armed)"
        } else {
            stopFallbackThread()
            if (currentMode.get() == MODE_SHIZUKU_FALLBACK) {
                statusDetail = "Idle (Shizuku Fallback Ready)"
            }
        }
    }

    override fun shutdownEngine() {
        cancelWatchdogTask()
        clickingActive.set(false)
        selfCheckConfirmed.set(false)
        stopFallbackThread()
        if (nativeLoaded.get()) {
            try {
                nativeShutdown()
            } catch (_: Throwable) {
            }
        }
        currentMode.set(MODE_IDLE)
        statusDetail = "Engine stopped."
    }

    override fun getEngineMode(): Int = currentMode.get()

    override fun getStatusDetail(): String = statusDetail

    override fun destroy() {
        shutdownEngine()
        watchdogExecutor.shutdownNow()
        System.exit(0)
    }

    private fun pushTargetsToNative(
        list: List<ClickTargetParceled>,
        running: Boolean,
        displayW: Int,
        displayH: Int,
        rotation: Int
    ) {
        val n = list.size
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val intervals = LongArray(n)
        val presses = LongArray(n)
        val jitters = BooleanArray(n)
        val enabled = BooleanArray(n)
        for (i in 0 until n) {
            val t = list[i]
            ids[i] = t.id
            xs[i] = t.x
            ys[i] = t.y
            intervals[i] = t.intervalMs
            presses[i] = t.pressDurationMs
            jitters[i] = t.microJitter
            enabled[i] = t.enabled
        }
        nativeSetTargetsAndRunning(
            ids, xs, ys, intervals, presses, jitters, enabled,
            running, displayW, displayH, rotation, 4.0f
        )
    }

    private class FallbackTargetState(
        var config: ClickTargetParceled,
        var pressed: Boolean = false,
        var pointerId: Int = -1,
        var nextDeadlineNs: Long = 0L
    )

    private class ActivePointer(
        val targetId: Int,
        val pointerId: Int,
        val x: Float,
        val y: Float
    )

    private fun startOrWakeFallbackThread() {
        synchronized(fallbackLock) {
            if (fallbackRunning.get() && fallbackThread?.isAlive == true) {
                return
            }
            fallbackRunning.set(true)
            val thread = Thread({ runFallbackLoop() }, "ShizukuFallbackInjector")
            thread.priority = Thread.MAX_PRIORITY
            fallbackThread = thread
            thread.start()
        }
    }

    private fun stopFallbackThread() {
        fallbackRunning.set(false)
        val t: Thread?
        synchronized(fallbackLock) {
            t = fallbackThread
            fallbackThread = null
        }
        if (t != null && t !== Thread.currentThread()) {
            LockSupport.unpark(t)
            try {
                t.join(350)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun runFallbackLoop() {
        val runtimeMap = LinkedHashMap<Int, FallbackTargetState>()
        val activePointers = ArrayList<ActivePointer>(10)
        var downTimeMs = 0L

        fun allocPointerId(): Int {
            for (pid in 0 until 10) {
                if (activePointers.none { it.pointerId == pid }) {
                    return pid
                }
            }
            return -1
        }

        fun liftPointer(targetId: Int) {
            val idx = activePointers.indexOfFirst { it.targetId == targetId }
            if (idx < 0) return
            val nowMs = SystemClock.uptimeMillis()
            val action = if (activePointers.size == 1) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            injectMotionFrame(downTimeMs, nowMs, action, activePointers)
            activePointers.removeAt(idx)
        }

        fun pressPointer(targetId: Int, x: Float, y: Float): Int {
            val pid = allocPointerId()
            if (pid < 0) return -1
            val nowMs = SystemClock.uptimeMillis()
            if (activePointers.isEmpty()) {
                downTimeMs = nowMs
            }
            activePointers.add(ActivePointer(targetId, pid, x, y))
            val idx = activePointers.size - 1
            val action = if (activePointers.size == 1) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            injectMotionFrame(downTimeMs, nowMs, action, activePointers)
            return pid
        }

        try {
            while (fallbackRunning.get() && clickingActive.get()) {
                val snapshot: List<ClickTargetParceled>
                val dispW: Int
                val dispH: Int
                synchronized(fallbackLock) {
                    snapshot = fallbackTargets
                    dispW = fallbackDisplayW
                    dispH = fallbackDisplayH
                }

                val nowNs = System.nanoTime()
                val enabledIds = HashSet<Int>(snapshot.size)

                for (t in snapshot) {
                    if (!t.enabled) continue
                    enabledIds.add(t.id)
                    val existing = runtimeMap[t.id]
                    if (existing == null) {
                        runtimeMap[t.id] = FallbackTargetState(
                            config = t,
                            pressed = false,
                            pointerId = -1,
                            nextDeadlineNs = nowNs + runtimeMap.size * 500_000L
                        )
                    } else {
                        existing.config = t
                    }
                }

                // Release removed or disabled targets gracefully (ACTION_UP / POINTER_UP, never ACTION_CANCEL)
                val iter = runtimeMap.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (!enabledIds.contains(entry.key)) {
                        if (entry.value.pressed) {
                            liftPointer(entry.key)
                        }
                        iter.remove()
                    }
                }

                var earliestNs = Long.MAX_VALUE
                for (st in runtimeMap.values) {
                    if (st.nextDeadlineNs <= nowNs) {
                        if (!st.pressed) {
                            val jx = if (st.config.microJitter) {
                                (Random.nextFloat() * 8f - 4f)
                            } else 0f
                            val jy = if (st.config.microJitter) {
                                (Random.nextFloat() * 8f - 4f)
                            } else 0f
                            val px = (st.config.x + jx).coerceIn(0f, (dispW - 1).coerceAtLeast(1).toFloat())
                            val py = (st.config.y + jy).coerceIn(0f, (dispH - 1).coerceAtLeast(1).toFloat())
                            val pid = pressPointer(st.config.id, px, py)
                            if (pid >= 0) {
                                st.pressed = true
                                st.pointerId = pid
                                val pressDur = st.config.pressDurationMs.coerceIn(8L, 5000L)
                                st.nextDeadlineNs = nowNs + pressDur * 1_000_000L
                            } else {
                                st.nextDeadlineNs = nowNs + 5_000_000L
                            }
                        } else {
                            liftPointer(st.config.id)
                            st.pressed = false
                            st.pointerId = -1
                            val interval = st.config.intervalMs.coerceIn(5L, 60_000L)
                            st.nextDeadlineNs = nowNs + interval * 1_000_000L
                        }
                    }
                    if (st.nextDeadlineNs < earliestNs) {
                        earliestNs = st.nextDeadlineNs
                    }
                }

                val sleepNs = if (earliestNs == Long.MAX_VALUE) {
                    20_000_000L
                } else {
                    (earliestNs - System.nanoTime()).coerceIn(500_000L, 50_000_000L)
                }
                LockSupport.parkNanos(sleepNs)
            }
        } finally {
            // Gracefully lift any still-pressed virtual pointers without emitting ACTION_CANCEL
            while (activePointers.isNotEmpty()) {
                val lastTargetId = activePointers.last().targetId
                liftPointer(lastTargetId)
            }
        }
    }

    private fun injectMotionFrame(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointers: List<ActivePointer>
    ) {
        val im = cachedInputManagerObj ?: return
        val method = cachedInjectMethod ?: return
        val count = pointers.size
        if (count == 0) return

        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 1.0f
                size = 0.08f
            }
        }

        // Use virtual deviceId (-1) instead of physical touchscreen deviceId to avoid colliding
        // directly with physical finger TouchState DOWN/UP transitions inside InputDispatcher.
        val event = MotionEvent.obtain(
            downTimeMs,
            eventTimeMs,
            action,
            count,
            props,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            VIRTUAL_INJECT_DEVICE_ID,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            method.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "injectInputEvent failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TAG = "TouchMuxUserService"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val VIRTUAL_INJECT_DEVICE_ID = -1
        private const val NATIVE_STATE_RUNNING_GRABBED = 2

        const val MODE_IDLE = 0
        const val MODE_NATIVE_MUX = 1
        const val MODE_SHIZUKU_FALLBACK = 2
    }
}
