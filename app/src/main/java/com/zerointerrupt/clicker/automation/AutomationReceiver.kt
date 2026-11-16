package com.zerointerrupt.clicker.automation

import android.content.BroadcastReceiver
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * External ADB / Tasker Broadcast Receiver for automation RPC control.
 *
 * Intent action: "com.zerointerrupt.clicker.RPC"
 * Required extra: "token" matching ClickGlobalSettings.rpcToken
 * Commands ("cmd"): START | STOP | SWITCH_PRESET | TAP | SWIPE
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        if (intent?.action != ACTION_RPC) return

        val settings = ClickConfigStore.loadSettings(ctx)
        if (!settings.proModeEnabled || !settings.rpcBridgeEnabled) {
            Log.w(TAG, "Ignored RPC broadcast: bridge disabled.")
            return
        }

        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!LocalRpcServer.isTokenValid(settings.rpcToken, token)) {
            Log.w(TAG, "Rejected RPC broadcast: invalid token.")
            return
        }

        val cmd = intent.getStringExtra(EXTRA_CMD)?.trim()?.uppercase().orEmpty()
        if (cmd.isEmpty()) return

        val pending = goAsync()
        receiverScope.launch {
            try {
                handleCommand(ctx, intent, cmd)
            } catch (t: Throwable) {
                Log.w(TAG, "RPC broadcast failed ($cmd): ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleCommand(ctx: Context, intent: Intent, cmd: String) {
        ShizukuController.initialize(ctx)
        when (cmd) {
            "START" -> {
                if (OverlayStateHub.overlayVisible.value) {
                    ctx.startService(
                        Intent(ctx, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_START
                        }
                    )
                } else if (Settings.canDrawOverlays(ctx)) {
                    ContextCompat.startForegroundService(
                        ctx,
                        Intent(ctx, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_START
                        }
                    )
                } else {
                    val preset = ClickConfigStore.getActivePreset(ctx)
                    ShizukuController.startClicking(
                        targets = preset.targets,
                        executionMode = preset.executionMode
                    )
                }
            }
            "STOP" -> {
                if (OverlayStateHub.overlayVisible.value) {
                    ctx.startService(
                        Intent(ctx, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_STOP
                        }
                    )
                }
                ShizukuController.stopClicking()
            }
            "SWITCH_PRESET" -> {
                val id = intent.getStringExtra("preset_id").orEmpty()
                val name = intent.getStringExtra("preset_name").orEmpty()
                val presets = ClickConfigStore.loadPresets(ctx)
                val match = when {
                    id.isNotBlank() -> presets.firstOrNull { it.id == id }
                    name.isNotBlank() -> presets.firstOrNull { it.name == name }
                    else -> null
                } ?: return
                ClickConfigStore.setActivePresetId(ctx, match.id)
                OverlayStateHub.bumpConfigVersion()
                if (OverlayStateHub.overlayVisible.value) {
                    ctx.startService(
                        Intent(ctx, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_RPC_SWITCH_PRESET
                            putExtra(OverlayService.EXTRA_PRESET_ID, match.id)
                        }
                    )
                }
            }
            "TAP" -> {
                val x = getFloatExtraCompat(intent, "x") ?: return
                val y = getFloatExtraCompat(intent, "y") ?: return
                val dur = getLongExtraCompat(intent, "duration_ms") ?: 25L
                ShizukuController.rpcTap(x, y, dur)
            }
            "SWIPE" -> {
                val x1 = getFloatExtraCompat(intent, "x1") ?: getFloatExtraCompat(intent, "x") ?: return
                val y1 = getFloatExtraCompat(intent, "y1") ?: getFloatExtraCompat(intent, "y") ?: return
                val x2 = getFloatExtraCompat(intent, "x2") ?: getFloatExtraCompat(intent, "end_x") ?: return
                val y2 = getFloatExtraCompat(intent, "y2") ?: getFloatExtraCompat(intent, "end_y") ?: return
                val dur = getLongExtraCompat(intent, "duration_ms") ?: 300L
                ShizukuController.rpcSwipe(x1, y1, x2, y2, dur)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getFloatExtraCompat(intent: Intent, key: String): Float? {
        val extras = intent.extras ?: return null
        val v = extras.get(key) ?: return null
        return when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun getLongExtraCompat(intent: Intent, key: String): Long? {
        val extras = intent.extras ?: return null
        val v = extras.get(key) ?: return null
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    companion object {
        private const val TAG = "AutomationReceiver"
        const val ACTION_RPC = "com.zerointerrupt.clicker.RPC"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CMD = "cmd"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
