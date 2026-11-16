package com.zerointerrupt.clicker.automation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.overlay.OverlayService
import com.zerointerrupt.clicker.overlay.OverlayStateHub
import com.zerointerrupt.clicker.shizuku.ShizukuController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * High-speed loopback JSON-RPC server (strictly bound to 127.0.0.1:27183).
 *
 * Line-delimited JSON request -> line-delimited JSON response per persistent socket connection.
 */
object LocalRpcServer {
    private const val TAG = "LocalRpcServer"
    const val RPC_PORT = 27183

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun isRunning(): Boolean = serverSocket?.let { !it.isClosed } == true

    fun syncWithSettings(context: Context) {
        val appCtx = context.applicationContext
        val settings = ClickConfigStore.loadSettings(appCtx)
        if (settings.proModeEnabled && settings.rpcBridgeEnabled) {
            start(appCtx)
        } else {
            stop()
        }
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lifecycleLock) {
            if (isRunning()) return
            try {
                val ipv4Loopback = InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1))
                val sock = ServerSocket(RPC_PORT, 16, ipv4Loopback).apply {
                    reuseAddress = true
                }
                serverSocket = sock
                acceptJob = scope.launch {
                    Log.i(TAG, "Loopback RPC Server listening on 127.0.0.1:$RPC_PORT")
                    while (isActive && !sock.isClosed) {
                        val client = try {
                            sock.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        launch { handleClient(appCtx, client) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind 127.0.0.1:$RPC_PORT: ${t.message}", t)
                serverSocket = null
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            try {
                serverSocket?.close()
            } catch (_: Throwable) {
            }
            serverSocket = null
            acceptJob?.cancel()
            acceptJob = null
        }
    }

    fun isTokenValid(expectedToken: String, providedToken: String?): Boolean {
        if (expectedToken.isBlank() || providedToken.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            providedToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim().trimStart('\uFEFF')
                if (trimmed.isEmpty()) continue
                val response = processRequestLine(context, trimmed)
                writer.write(response)
                writer.write("\n")
                writer.flush()
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun processRequestLine(context: Context, line: String): String {
        val req = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return JSONObject().put("ok", false).put("error", "invalid_json").toString()
        }

        val settings = ClickConfigStore.loadSettings(context)
        if (!settings.proModeEnabled || !settings.rpcBridgeEnabled) {
            return JSONObject().put("ok", false).put("error", "rpc_bridge_disabled").toString()
        }
        val token = req.optString("token", "")
        if (!isTokenValid(settings.rpcToken, token)) {
            return JSONObject().put("ok", false).put("error", "unauthorized").toString()
        }

        val cmd = req.optString("cmd", "").trim().lowercase()
        val resp = JSONObject().put("cmd", cmd)

        when (cmd) {
            "ping" -> {
                resp.put("ok", true)
                resp.put("pong", true)
                resp.put("port", RPC_PORT)
            }
            "status" -> {
                val snap = ShizukuController.engineSnapshot.value
                val slots = ShizukuController.slotStates.value
                val slotArray = JSONArray().apply { slots.forEach { put(it) } }
                resp.put("ok", true)
                resp.put("shizukuConnected", ShizukuController.isServiceConnected())
                resp.put("engineMode", snap.engineMode)
                resp.put("overlayVisible", OverlayStateHub.overlayVisible.value)
                resp.put("clickingRunning", OverlayStateHub.clickingRunning.value)
                resp.put("activePresetId", ClickConfigStore.getActivePresetId(context))
                resp.put("slotStates", slotArray)
            }
            "tap" -> {
                val x = req.optDouble("x", Double.NaN).toFloat()
                val y = req.optDouble("y", Double.NaN).toFloat()
                val dur = req.optLong("durationMs", 25L).coerceIn(8L, 10_000L)
                if (x.isNaN() || y.isNaN()) {
                    resp.put("ok", false).put("error", "missing_xy")
                } else {
                    val sent = ShizukuController.rpcTap(x, y, dur)
                    resp.put("ok", sent)
                    if (!sent) resp.put("error", "shizuku_not_connected")
                }
            }
            "swipe" -> {
                val x1 = req.optDouble("x1", Double.NaN).toFloat()
                val y1 = req.optDouble("y1", Double.NaN).toFloat()
                val x2 = req.optDouble("x2", Double.NaN).toFloat()
                val y2 = req.optDouble("y2", Double.NaN).toFloat()
                val dur = req.optLong("durationMs", 300L).coerceIn(24L, 15_000L)
                if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                    resp.put("ok", false).put("error", "missing_coordinates")
                } else {
                    val sent = ShizukuController.rpcSwipe(x1, y1, x2, y2, dur)
                    resp.put("ok", sent)
                    if (!sent) resp.put("error", "shizuku_not_connected")
                }
            }
            "hold_down" -> {
                val holdId = req.optInt("holdId", 0)
                val x = req.optDouble("x", Double.NaN).toFloat()
                val y = req.optDouble("y", Double.NaN).toFloat()
                if (x.isNaN() || y.isNaN()) {
                    resp.put("ok", false).put("error", "missing_xy")
                } else {
                    val sent = ShizukuController.rpcHold(holdId, true, x, y)
                    resp.put("ok", sent)
                    if (!sent) resp.put("error", "shizuku_not_connected")
                }
            }
            "hold_up" -> {
                val holdId = req.optInt("holdId", 0)
                val x = req.optDouble("x", 0.0).toFloat()
                val y = req.optDouble("y", 0.0).toFloat()
                val sent = ShizukuController.rpcHold(holdId, false, x, y)
                resp.put("ok", sent)
                if (!sent) resp.put("error", "shizuku_not_connected")
            }
            "start" -> {
                if (OverlayStateHub.overlayVisible.value) {
                    context.startService(
                        Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_START
                        }
                    )
                    resp.put("ok", true)
                } else if (Settings.canDrawOverlays(context)) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_START
                        }
                    )
                    resp.put("ok", true)
                } else {
                    val preset = ClickConfigStore.getActivePreset(context)
                    val mode = ShizukuController.startClicking(
                        targets = preset.targets,
                        executionMode = preset.executionMode
                    )
                    resp.put("ok", mode >= 0).put("engineMode", mode)
                }
            }
            "stop" -> {
                if (OverlayStateHub.overlayVisible.value) {
                    context.startService(
                        Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_STOP
                        }
                    )
                }
                ShizukuController.stopClicking()
                resp.put("ok", true)
            }
            "switch_preset" -> {
                val id = req.optString("presetId", "")
                val name = req.optString("name", "")
                val presets = ClickConfigStore.loadPresets(context)
                val match = when {
                    id.isNotBlank() -> presets.firstOrNull { it.id == id }
                    name.isNotBlank() -> presets.firstOrNull { it.name == name }
                    else -> null
                }
                if (match == null) {
                    resp.put("ok", false).put("error", "preset_not_found")
                } else {
                    ClickConfigStore.setActivePresetId(context, match.id)
                    OverlayStateHub.bumpConfigVersion()
                    if (OverlayStateHub.overlayVisible.value) {
                        context.startService(
                            Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_RPC_SWITCH_PRESET
                                putExtra(OverlayService.EXTRA_PRESET_ID, match.id)
                            }
                        )
                    }
                    resp.put("ok", true).put("activePresetId", match.id).put("name", match.name)
                }
            }
            else -> {
                resp.put("ok", false).put("error", "unknown_cmd")
            }
        }
        return resp.toString()
    }
}
