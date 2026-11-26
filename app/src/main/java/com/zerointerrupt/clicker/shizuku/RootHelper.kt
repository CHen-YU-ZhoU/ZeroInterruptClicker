package com.zerointerrupt.clicker.shizuku

import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class RootLaunchResult(
    val rootGranted: Boolean,
    val daemonLaunched: Boolean,
    val message: String
)

/**
 * Pure Root (`su`) helper for:
 * 1. One-shot superuser authorization + auto-granting Overlay, Notification, and Battery Whitelist.
 * 2. Launching the UID 0 TouchMuxUserService daemon via `app_process` in the SAME `su` session,
 *    so the user is prompted for superuser permission only once.
 */
object RootHelper {
    private const val TAG = "RootHelper"
    private const val MARKER_UID_OK = "__ZI_ROOT_UID0_OK__"
    private const val MARKER_DONE = "__ZI_ROOT_DONE__"

    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su"
    )

    fun hasSuBinaryHint(): Boolean {
        return SU_CANDIDATES.drop(1).any { path ->
            try {
                File(path).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Requests Root once, auto-grants all required app permissions (when [grantAllPermissions] is true),
     * and launches the UID 0 [RootMuxServerMain] process that calls back into [RootBinderProvider].
     */
    fun requestRootGrantAndLaunchDaemon(
        context: Context,
        authority: String,
        sessionToken: String,
        grantAllPermissions: Boolean = true,
        timeoutSeconds: Long = 15L
    ): RootLaunchResult {
        val pkg = context.packageName
        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isNullOrBlank()) {
            return RootLaunchResult(
                rootGranted = false,
                daemonLaunched = false,
                message = "无法定位应用安装包路径"
            )
        }

        val appProcessBin = resolveAppProcessBinary()
        val userId = Process.myUid() / 100_000
        val userFlag = if (userId > 0) "--user $userId " else ""
        val niceName = "$pkg:touchmux_root"
        val qPkg = shellQuote(pkg)
        val qApk = shellQuote(apkPath)
        val qAppProc = shellQuote(appProcessBin)
        val qNice = shellQuote(niceName)
        val qAuth = shellQuote(authority)
        val qToken = shellQuote(sessionToken)

        val script = buildString {
            appendLine("UID_OUT=$(id -u 2>/dev/null || id 2>/dev/null)")
            appendLine("case \"\$UID_OUT\" in")
            appendLine("  0|*uid=0*) echo '$MARKER_UID_OK' ;;")
            appendLine("  *) echo '__ZI_NOT_ROOT__'; exit 1 ;;")
            appendLine("esac")

            if (grantAllPermissions) {
                // 1. Overlay permission (SYSTEM_ALERT_WINDOW)
                appendLine("appops set ${userFlag}$qPkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true")
                appendLine("pm grant ${userFlag}$qPkg android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null || true")

                // 2. Notification permission (Android 13+)
                appendLine("pm grant ${userFlag}$qPkg android.permission.POST_NOTIFICATIONS 2>/dev/null || true")
                appendLine("appops set ${userFlag}$qPkg POST_NOTIFICATION allow 2>/dev/null || true")

                // 3. Battery optimization whitelist & unrestricted background execution
                appendLine("dumpsys deviceidle whitelist +$qPkg 2>/dev/null || true")
                appendLine("cmd deviceidle whitelist +$qPkg 2>/dev/null || true")
                appendLine("appops set ${userFlag}$qPkg RUN_IN_BACKGROUND allow 2>/dev/null || true")
                appendLine("appops set ${userFlag}$qPkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true")
            }

            // Reclaim any lingering old root mux process before starting fresh instance
            appendLine("pkill -TERM -f $qNice 2>/dev/null || true")
            appendLine("export CLASSPATH=$qApk")
            appendLine("if [ -z \"\$ANDROID_DATA\" ]; then export ANDROID_DATA=/data; fi")
            appendLine("if [ -z \"\$ANDROID_ROOT\" ]; then export ANDROID_ROOT=/system; fi")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid $qAppProc /system/bin --nice-name=$qNice com.zerointerrupt.clicker.shizuku.RootMuxServerMain $qPkg $qAuth $qToken $userId </dev/null >/dev/null 2>&1 &")
            appendLine("else")
            appendLine("  nohup $qAppProc /system/bin --nice-name=$qNice com.zerointerrupt.clicker.shizuku.RootMuxServerMain $qPkg $qAuth $qToken $userId </dev/null >/dev/null 2>&1 &")
            appendLine("fi")
            appendLine("echo '$MARKER_DONE'")
            appendLine("exit 0")
        }

        val execRes = executeSuScript(script, timeoutSeconds)
        if (!execRes.started) {
            return RootLaunchResult(
                rootGranted = false,
                daemonLaunched = false,
                message = "未检测到 Root (su) 环境，请确认设备已获取 Root 权限"
            )
        }
        if (execRes.timedOut) {
            return RootLaunchResult(
                rootGranted = false,
                daemonLaunched = false,
                message = "Root 授权等待超时，请在授权弹窗中点击允许"
            )
        }

        val output = execRes.output
        val gotUid0 = output.contains(MARKER_UID_OK)
        val reachedEnd = output.contains(MARKER_DONE)

        if (!gotUid0) {
            Log.w(TAG, "su did not yield uid=0 (exit=${execRes.exitCode}): $output")
            return RootLaunchResult(
                rootGranted = false,
                daemonLaunched = false,
                message = "Root 授权被拒绝或未生效，请在 Root 管理器中允许本应用"
            )
        }

        if (grantAllPermissions) {
            // Brief wait for AppOps / PackageManager cache to reflect newly granted overlay permission
            val deadline = System.currentTimeMillis() + 350L
            while (!Settings.canDrawOverlays(context) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(35L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        return RootLaunchResult(
            rootGranted = true,
            daemonLaunched = reachedEnd,
            message = if (reachedEnd) "Root 授权成功，正在建立原生通道…" else "Root 守护进程启动异常"
        )
    }

    private data class SuExecOutcome(
        val started: Boolean,
        val timedOut: Boolean,
        val exitCode: Int,
        val output: String
    )

    private fun executeSuScript(script: String, timeoutSeconds: Long): SuExecOutcome {
        var process: java.lang.Process? = null
        var lastStartErr: Throwable? = null
        for (suBin in SU_CANDIDATES) {
            if (suBin != "su" && !File(suBin).exists()) continue
            try {
                process = ProcessBuilder(suBin)
                    .redirectErrorStream(true)
                    .start()
                break
            } catch (t: Throwable) {
                lastStartErr = t
            }
        }
        if (process == null) {
            Log.w(TAG, "Unable to spawn su binary: ${lastStartErr?.message}")
            return SuExecOutcome(started = false, timedOut = false, exitCode = -1, output = "")
        }

        val sb = StringBuilder()
        val readerThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        sb.append(line).append('\n')
                        line = reader.readLine()
                    }
                }
            } catch (_: Throwable) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        return try {
            process.outputStream.use { out ->
                out.write(script.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(300L)
                SuExecOutcome(started = true, timedOut = true, exitCode = -1, output = sb.toString())
            } else {
                readerThread.join(500L)
                SuExecOutcome(
                    started = true,
                    timedOut = false,
                    exitCode = process.exitValue(),
                    output = sb.toString()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "executeSuScript error: ${t.message}")
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {
            }
            SuExecOutcome(started = true, timedOut = false, exitCode = -1, output = sb.toString())
        }
    }

    private fun resolveAppProcessBinary(): String {
        val is64 = Process.is64Bit()
        val preferred = if (is64) "/system/bin/app_process64" else "/system/bin/app_process32"
        return if (File(preferred).exists()) preferred else "/system/bin/app_process"
    }

    private fun shellQuote(raw: String): String {
        return "'" + raw.replace("'", "'\\''") + "'"
    }
}
