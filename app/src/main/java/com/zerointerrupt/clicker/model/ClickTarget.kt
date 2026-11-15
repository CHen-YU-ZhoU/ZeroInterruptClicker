package com.zerointerrupt.clicker.model

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.zerointerrupt.clicker.ClickTargetParceled
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.max

enum class ActionType(val code: Int, val label: String) {
    TAP(0, "连点"),
    SWIPE(1, "滑动"),
    HOLD(2, "长按");

    companion object {
        fun fromCode(code: Int): ActionType = entries.firstOrNull { it.code == code } ?: TAP
    }
}

enum class ExecutionMode(val code: Int, val label: String) {
    CONCURRENT(0, "同步并发"),
    SEQUENTIAL(1, "顺序连招");

    companion object {
        fun fromCode(code: Int): ExecutionMode = entries.firstOrNull { it.code == code } ?: CONCURRENT
    }
}

/**
 * Click/Swipe/Hold target anchor configuration with normalized coordinates and Parcelable support.
 */
data class ClickTarget(
    val id: Int,
    var index: Int,
    var x: Float,
    var y: Float,
    var normX: Float = -1f,
    var normY: Float = -1f,
    var intervalMs: Long = DEFAULT_INTERVAL_MS,
    var pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
    var microJitter: Boolean = true,
    var enabled: Boolean = true,
    var actionType: ActionType = ActionType.TAP,
    var endX: Float = x,
    var endY: Float = y,
    var endNormX: Float = -1f,
    var endNormY: Float = -1f,
    var swipeDurationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    var repeatCount: Int = DEFAULT_REPEAT_COUNT
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        index = parcel.readInt(),
        x = parcel.readFloat(),
        y = parcel.readFloat(),
        normX = parcel.readFloat(),
        normY = parcel.readFloat(),
        intervalMs = parcel.readLong(),
        pressDurationMs = parcel.readLong(),
        microJitter = parcel.readByte().toInt() != 0,
        enabled = parcel.readByte().toInt() != 0,
        actionType = ActionType.fromCode(parcel.readInt()),
        endX = parcel.readFloat(),
        endY = parcel.readFloat(),
        endNormX = parcel.readFloat(),
        endNormY = parcel.readFloat(),
        swipeDurationMs = parcel.readLong(),
        repeatCount = parcel.readInt().coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeInt(index)
        parcel.writeFloat(x)
        parcel.writeFloat(y)
        parcel.writeFloat(normX)
        parcel.writeFloat(normY)
        parcel.writeLong(intervalMs)
        parcel.writeLong(pressDurationMs)
        parcel.writeByte(if (microJitter) 1 else 0)
        parcel.writeByte(if (enabled) 1 else 0)
        parcel.writeInt(actionType.code)
        parcel.writeFloat(endX)
        parcel.writeFloat(endY)
        parcel.writeFloat(endNormX)
        parcel.writeFloat(endNormY)
        parcel.writeLong(swipeDurationMs)
        parcel.writeInt(repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT))
    }

    override fun describeContents(): Int = 0

    fun setStartPixel(px: Float, py: Float, screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        x = px.coerceIn(0f, maxX)
        y = py.coerceIn(0f, maxY)
        normX = (x / maxX).coerceIn(0f, 1f)
        normY = (y / maxY).coerceIn(0f, 1f)
    }

    fun setEndPixel(px: Float, py: Float, screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        endX = px.coerceIn(0f, maxX)
        endY = py.coerceIn(0f, maxY)
        endNormX = (endX / maxX).coerceIn(0f, 1f)
        endNormY = (endY / maxY).coerceIn(0f, 1f)
    }

    fun ensureDefaultSwipeEndpoint(screenW: Int, screenH: Int) {
        val dx = endX - x
        val dy = endY - y
        if ((dx * dx + dy * dy) < 16f) {
            val offsetX = (screenW * 0.14f).coerceIn(100f, 240f)
            val candidateX = if (x + offsetX <= screenW - 60f) x + offsetX else x - offsetX
            setEndPixel(candidateX, y, screenW, screenH)
        } else if (endNormX !in 0f..1f || endNormY !in 0f..1f) {
            setEndPixel(endX, endY, screenW, screenH)
        }
    }

    /**
     * Remaps pixel coordinates cleanly from saved normalized coordinates for the given viewport
     * WITHOUT overwriting (normX, normY) when clamping across rotations.
     */
    fun remapToScreen(screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        if (normX in 0f..1f && normY in 0f..1f) {
            x = (normX * maxX).coerceIn(0f, maxX)
            y = (normY * maxY).coerceIn(0f, maxY)
        } else {
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
            normX = (x / maxX).coerceIn(0f, 1f)
            normY = (y / maxY).coerceIn(0f, 1f)
        }
        if (endNormX in 0f..1f && endNormY in 0f..1f) {
            endX = (endNormX * maxX).coerceIn(0f, maxX)
            endY = (endNormY * maxY).coerceIn(0f, maxY)
        } else if (actionType == ActionType.SWIPE) {
            endX = endX.coerceIn(0f, maxX)
            endY = endY.coerceIn(0f, maxY)
            endNormX = (endX / maxX).coerceIn(0f, 1f)
            endNormY = (endY / maxY).coerceIn(0f, 1f)
        } else {
            endX = x
            endY = y
        }
    }

    fun toParceled(): ClickTargetParceled = ClickTargetParceled().apply {
        id = this@ClickTarget.id
        x = this@ClickTarget.x
        y = this@ClickTarget.y
        intervalMs = this@ClickTarget.intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        pressDurationMs = this@ClickTarget.pressDurationMs.coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
        microJitter = this@ClickTarget.microJitter
        enabled = this@ClickTarget.enabled
        actionType = this@ClickTarget.actionType.code
        endX = this@ClickTarget.endX
        endY = this@ClickTarget.endY
        swipeDurationMs = this@ClickTarget.swipeDurationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        repeatCount = this@ClickTarget.repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT)
    }

    companion object {
        const val MIN_INTERVAL_MS = 5L
        const val MAX_INTERVAL_MS = 60_000L
        const val DEFAULT_INTERVAL_MS = 80L

        const val MIN_PRESS_MS = 8L
        const val MAX_PRESS_MS = 30_000L
        const val DEFAULT_PRESS_DURATION_MS = 25L

        const val MIN_SWIPE_MS = 30L
        const val MAX_SWIPE_MS = 15_000L
        const val DEFAULT_SWIPE_DURATION_MS = 320L

        const val MIN_REPEAT_COUNT = 1
        const val MAX_REPEAT_COUNT = 9999
        const val DEFAULT_REPEAT_COUNT = 1

        @JvmField
        val CREATOR = object : Parcelable.Creator<ClickTarget> {
            override fun createFromParcel(parcel: Parcel): ClickTarget = ClickTarget(parcel)
            override fun newArray(size: Int): Array<ClickTarget?> = arrayOfNulls(size)
        }
    }
}

data class ClickPreset(
    val id: String,
    var name: String,
    var executionMode: ExecutionMode = ExecutionMode.CONCURRENT,
    var presetLoopCount: Int = 0,
    var presetDurationSec: Int = 0,
    val targets: MutableList<ClickTarget> = mutableListOf()
) {
    companion object {
        const val MIN_LOOP_COUNT = 0
        const val MAX_LOOP_COUNT = 9999
        const val MIN_DURATION_SEC = 0
        const val MAX_DURATION_SEC = 86_400
    }
}

data class ClickGlobalSettings(
    val defaultIntervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val defaultPressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val tuningTier: Int = TIER_CUSTOM,
    val customIntervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val customPressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val defaultMicroJitter: Boolean = true,
    val jitterRadiusPx: Float = 4.0f,
    val selfCheckPassed: Boolean = false,
    val autoLockOnStart: Boolean = true,
    val autoCollapseBar: Boolean = false,
    val lockedAnchorAlpha: Float = DEFAULT_LOCKED_ALPHA,
    val themeMode: Int = THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val volumeKeyToggleEnabled: Boolean = true,
    val proModeEnabled: Boolean = false,
    val preferredBackend: Int = BACKEND_AUTO,
    val rpcBridgeEnabled: Boolean = false,
    val rpcToken: String = ""
) {
    companion object {
        const val DEFAULT_LOCKED_ALPHA = 0.65f
        const val MIN_LOCKED_ALPHA = 0.35f
        const val MAX_LOCKED_ALPHA = 0.75f

        const val TIER_CASUAL = 0
        const val TIER_STANDARD = 1
        const val TIER_FAST = 2
        const val TIER_CUSTOM = 3

        const val CASUAL_INTERVAL_MS = 150L
        const val CASUAL_PRESS_MS = 45L
        const val STANDARD_INTERVAL_MS = 60L
        const val STANDARD_PRESS_MS = 24L
        const val FAST_INTERVAL_MS = 20L
        const val FAST_PRESS_MS = 10L

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val BACKEND_AUTO = 0
        const val BACKEND_UINPUT = 1
        const val BACKEND_UHID = 2
        const val BACKEND_HYBRID = 3
        const val BACKEND_FALLBACK = 4
    }
}

object ClickConfigStore {
    private const val PREF_NAME = "zero_interrupt_clicker_prefs"
    private const val KEY_TARGETS = "saved_targets_json"
    private const val KEY_PRESETS_JSON = "saved_presets_json"
    private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
    private const val DEFAULT_PRESET_ID = "preset_1"

    private const val KEY_DEFAULT_INTERVAL = "default_interval_ms"
    private const val KEY_DEFAULT_PRESS = "default_press_ms"
    private const val KEY_TUNING_TIER = "tuning_tier"
    private const val KEY_CUSTOM_INTERVAL = "custom_interval_ms"
    private const val KEY_CUSTOM_PRESS = "custom_press_ms"
    private const val KEY_DEFAULT_JITTER = "default_jitter"
    private const val KEY_JITTER_RADIUS = "jitter_radius_px"
    private const val KEY_SELF_CHECK_PASSED = "self_check_passed"
    private const val KEY_AUTO_LOCK_ON_START = "auto_lock_on_start"
    private const val KEY_AUTO_COLLAPSE_BAR = "auto_collapse_bar"
    private const val KEY_LOCKED_ANCHOR_ALPHA = "locked_anchor_alpha"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_VOL_KEY_TOGGLE = "volume_key_toggle_enabled"
    private const val KEY_PRO_MODE = "pro_mode_enabled"
    private const val KEY_PREFERRED_BACKEND = "preferred_backend"
    private const val KEY_RPC_BRIDGE_ENABLED = "rpc_bridge_enabled"
    private const val KEY_RPC_TOKEN = "rpc_token"

    private const val KEY_CONTROL_BAR_X = "control_bar_x"
    private const val KEY_CONTROL_BAR_Y = "control_bar_y"
    private const val KEY_CONTROL_BAR_DOCK_RIGHT = "control_bar_dock_right"
    private const val KEY_CONTROL_BAR_COLLAPSED = "control_bar_collapsed"

    private val secureRandom = SecureRandom()

    fun generateRpcToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val sb = StringBuilder("zi_")
        repeat(20) {
            sb.append(alphabet[secureRandom.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    fun loadSettings(context: Context): ClickGlobalSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_RPC_TOKEN, "") ?: ""
        if (token.isBlank()) {
            token = generateRpcToken()
            prefs.edit().putString(KEY_RPC_TOKEN, token).apply()
        }
        val defInterval = prefs.getLong(KEY_DEFAULT_INTERVAL, ClickTarget.DEFAULT_INTERVAL_MS)
            .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val defPress = prefs.getLong(KEY_DEFAULT_PRESS, ClickTarget.DEFAULT_PRESS_DURATION_MS)
            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        val inferredTier = when {
            defInterval == ClickGlobalSettings.CASUAL_INTERVAL_MS && defPress == ClickGlobalSettings.CASUAL_PRESS_MS ->
                ClickGlobalSettings.TIER_CASUAL
            defInterval == ClickGlobalSettings.STANDARD_INTERVAL_MS && defPress == ClickGlobalSettings.STANDARD_PRESS_MS ->
                ClickGlobalSettings.TIER_STANDARD
            defInterval == ClickGlobalSettings.FAST_INTERVAL_MS && defPress == ClickGlobalSettings.FAST_PRESS_MS ->
                ClickGlobalSettings.TIER_FAST
            else -> ClickGlobalSettings.TIER_CUSTOM
        }
        val tuningTier = if (prefs.contains(KEY_TUNING_TIER)) {
            prefs.getInt(KEY_TUNING_TIER, inferredTier)
                .coerceIn(ClickGlobalSettings.TIER_CASUAL, ClickGlobalSettings.TIER_CUSTOM)
        } else {
            inferredTier
        }
        val customInterval = prefs.getLong(KEY_CUSTOM_INTERVAL, defInterval)
            .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val customPress = prefs.getLong(KEY_CUSTOM_PRESS, defPress)
            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        return ClickGlobalSettings(
            defaultIntervalMs = defInterval,
            defaultPressDurationMs = defPress,
            tuningTier = tuningTier,
            customIntervalMs = customInterval,
            customPressDurationMs = customPress,
            defaultMicroJitter = prefs.getBoolean(KEY_DEFAULT_JITTER, true),
            jitterRadiusPx = prefs.getFloat(KEY_JITTER_RADIUS, 4.0f).coerceIn(0f, 16f),
            selfCheckPassed = prefs.getBoolean(KEY_SELF_CHECK_PASSED, false),
            autoLockOnStart = prefs.getBoolean(KEY_AUTO_LOCK_ON_START, true),
            autoCollapseBar = prefs.getBoolean(KEY_AUTO_COLLAPSE_BAR, false),
            lockedAnchorAlpha = prefs.getFloat(KEY_LOCKED_ANCHOR_ALPHA, ClickGlobalSettings.DEFAULT_LOCKED_ALPHA)
                .coerceIn(ClickGlobalSettings.MIN_LOCKED_ALPHA, ClickGlobalSettings.MAX_LOCKED_ALPHA),
            themeMode = prefs.getInt(KEY_THEME_MODE, ClickGlobalSettings.THEME_SYSTEM)
                .coerceIn(ClickGlobalSettings.THEME_SYSTEM, ClickGlobalSettings.THEME_DARK),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            volumeKeyToggleEnabled = prefs.getBoolean(KEY_VOL_KEY_TOGGLE, true),
            proModeEnabled = prefs.getBoolean(KEY_PRO_MODE, false),
            preferredBackend = prefs.getInt(KEY_PREFERRED_BACKEND, ClickGlobalSettings.BACKEND_AUTO)
                .coerceIn(ClickGlobalSettings.BACKEND_AUTO, ClickGlobalSettings.BACKEND_FALLBACK),
            rpcBridgeEnabled = prefs.getBoolean(KEY_RPC_BRIDGE_ENABLED, false),
            rpcToken = token
        )
    }

    fun saveSettings(context: Context, settings: ClickGlobalSettings) {
        val safeToken = settings.rpcToken.ifBlank { generateRpcToken() }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DEFAULT_INTERVAL, settings.defaultIntervalMs)
            .putLong(KEY_DEFAULT_PRESS, settings.defaultPressDurationMs)
            .putInt(KEY_TUNING_TIER, settings.tuningTier)
            .putLong(KEY_CUSTOM_INTERVAL, settings.customIntervalMs)
            .putLong(KEY_CUSTOM_PRESS, settings.customPressDurationMs)
            .putBoolean(KEY_DEFAULT_JITTER, settings.defaultMicroJitter)
            .putFloat(KEY_JITTER_RADIUS, settings.jitterRadiusPx.coerceIn(0f, 16f))
            .putBoolean(KEY_SELF_CHECK_PASSED, settings.selfCheckPassed)
            .putBoolean(KEY_AUTO_LOCK_ON_START, settings.autoLockOnStart)
            .putBoolean(KEY_AUTO_COLLAPSE_BAR, settings.autoCollapseBar)
            .putFloat(
                KEY_LOCKED_ANCHOR_ALPHA,
                settings.lockedAnchorAlpha.coerceIn(
                    ClickGlobalSettings.MIN_LOCKED_ALPHA,
                    ClickGlobalSettings.MAX_LOCKED_ALPHA
                )
            )
            .putInt(KEY_THEME_MODE, settings.themeMode)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putBoolean(KEY_VOL_KEY_TOGGLE, settings.volumeKeyToggleEnabled)
            .putBoolean(KEY_PRO_MODE, settings.proModeEnabled)
            .putInt(KEY_PREFERRED_BACKEND, settings.preferredBackend)
            .putBoolean(KEY_RPC_BRIDGE_ENABLED, settings.rpcBridgeEnabled)
            .putString(KEY_RPC_TOKEN, safeToken)
            .apply()
    }

    fun markSelfCheckPassed(context: Context, passed: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SELF_CHECK_PASSED, passed)
            .apply()
    }

    private fun jsonArrayToTargets(array: JSONArray?): MutableList<ClickTarget> {
        if (array == null) return mutableListOf()
        val result = mutableListOf<ClickTarget>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val px = obj.optDouble("x", 0.0).toFloat()
            val py = obj.optDouble("y", 0.0).toFloat()
            val act = ActionType.fromCode(obj.optInt("actionType", 0))
            result += ClickTarget(
                id = obj.optInt("id", i + 1),
                index = i + 1,
                x = px,
                y = py,
                normX = obj.optDouble("normX", -1.0).toFloat(),
                normY = obj.optDouble("normY", -1.0).toFloat(),
                intervalMs = obj.optLong("intervalMs", ClickTarget.DEFAULT_INTERVAL_MS)
                    .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
                pressDurationMs = obj.optLong("pressDurationMs", ClickTarget.DEFAULT_PRESS_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
                microJitter = obj.optBoolean("microJitter", true),
                enabled = obj.optBoolean("enabled", true),
                actionType = act,
                endX = obj.optDouble("endX", px.toDouble()).toFloat(),
                endY = obj.optDouble("endY", py.toDouble()).toFloat(),
                endNormX = obj.optDouble("endNormX", -1.0).toFloat(),
                endNormY = obj.optDouble("endNormY", -1.0).toFloat(),
                swipeDurationMs = obj.optLong("swipeDurationMs", ClickTarget.DEFAULT_SWIPE_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS),
                repeatCount = obj.optInt("repeatCount", ClickTarget.DEFAULT_REPEAT_COUNT)
                    .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
            )
        }
        return result
    }

    private fun targetsToJsonArray(targets: List<ClickTarget>): JSONArray {
        val array = JSONArray()
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            val obj = JSONObject().apply {
                put("id", t.id)
                put("x", t.x.toDouble())
                put("y", t.y.toDouble())
                put("normX", t.normX.toDouble())
                put("normY", t.normY.toDouble())
                put("intervalMs", t.intervalMs)
                put("pressDurationMs", t.pressDurationMs)
                put("microJitter", t.microJitter)
                put("enabled", t.enabled)
                put("actionType", t.actionType.code)
                put("endX", t.endX.toDouble())
                put("endY", t.endY.toDouble())
                put("endNormX", t.endNormX.toDouble())
                put("endNormY", t.endNormY.toDouble())
                put("swipeDurationMs", t.swipeDurationMs)
                put("repeatCount", t.repeatCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT))
            }
            array.put(obj)
        }
        return array
    }

    private fun loadLegacyTargetsRaw(context: Context): MutableList<ClickTarget> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGETS, null) ?: return mutableListOf()
        return try {
            jsonArrayToTargets(JSONArray(raw))
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun loadPresets(context: Context): MutableList<ClickPreset> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRESETS_JSON, null)
        val parsed = if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val list = mutableListOf<ClickPreset>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").ifBlank { "preset_${i + 1}" }
                    val name = obj.optString("name", "").ifBlank { "方案 ${i + 1}" }
                    val execMode = ExecutionMode.fromCode(obj.optInt("executionMode", 0))
                    val loopCount = obj.optInt("presetLoopCount", 0)
                        .coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT)
                    val durationSec = obj.optInt("presetDurationSec", 0)
                        .coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC)
                    val targets = jsonArrayToTargets(obj.optJSONArray("targets"))
                    list += ClickPreset(
                        id = id,
                        name = name,
                        executionMode = execMode,
                        presetLoopCount = loopCount,
                        presetDurationSec = durationSec,
                        targets = targets
                    )
                }
                list
            } catch (_: Throwable) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        if (parsed.isNotEmpty()) {
            return parsed
        }

        val migrated = ClickPreset(
            id = DEFAULT_PRESET_ID,
            name = "方案 1",
            executionMode = ExecutionMode.CONCURRENT,
            targets = loadLegacyTargetsRaw(context)
        )
        val initialList = mutableListOf(migrated)
        persistPresetsInternal(context, initialList, migrated.id)
        return initialList
    }

    private fun persistPresetsInternal(
        context: Context,
        presets: List<ClickPreset>,
        activePresetId: String
    ) {
        val safeList = if (presets.isEmpty()) {
            listOf(ClickPreset(id = DEFAULT_PRESET_ID, name = "方案 1"))
        } else {
            presets
        }
        val active = safeList.firstOrNull { it.id == activePresetId } ?: safeList.first()
        val array = JSONArray()
        for (preset in safeList) {
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("executionMode", preset.executionMode.code)
                put("presetLoopCount", preset.presetLoopCount.coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT))
                put("presetDurationSec", preset.presetDurationSec.coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC))
                put("targets", targetsToJsonArray(preset.targets))
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESETS_JSON, array.toString())
            .putString(KEY_ACTIVE_PRESET_ID, active.id)
            .putString(KEY_TARGETS, targetsToJsonArray(active.targets).toString())
            .apply()
    }

    fun getActivePresetId(context: Context): String {
        val presets = loadPresets(context)
        val savedId = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PRESET_ID, DEFAULT_PRESET_ID)
        val resolved = presets.firstOrNull { it.id == savedId } ?: presets.first()
        if (resolved.id != savedId) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_PRESET_ID, resolved.id)
                .apply()
        }
        return resolved.id
    }

    fun getActivePreset(context: Context): ClickPreset {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        return presets.firstOrNull { it.id == activeId } ?: presets.first()
    }

    fun getActivePresetIndexAndTotal(context: Context): Pair<Int, Int> {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        val idx = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        return Pair(idx, presets.size.coerceAtLeast(1))
    }

    fun setActivePresetId(context: Context, presetId: String): ClickPreset {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: presets.first()
        persistPresetsInternal(context, presets, target.id)
        return target
    }

    fun createPreset(
        context: Context,
        name: String,
        initialTargets: List<ClickTarget> = emptyList(),
        executionMode: ExecutionMode = ExecutionMode.CONCURRENT,
        activate: Boolean = true,
        presetLoopCount: Int = 0,
        presetDurationSec: Int = 0
    ): ClickPreset {
        val presets = loadPresets(context)
        val existingIds = presets.map { it.id }.toSet()
        var seq = presets.size + 1
        var newId = "preset_$seq"
        while (newId in existingIds) {
            seq++
            newId = "preset_$seq"
        }
        val cleanName = name.trim().ifEmpty { "方案 ${presets.size + 1}" }
        val copiedTargets = initialTargets.mapIndexed { idx, t ->
            t.copy(id = idx + 1, index = idx + 1)
        }.toMutableList()
        val created = ClickPreset(
            id = newId,
            name = cleanName,
            executionMode = executionMode,
            presetLoopCount = presetLoopCount.coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT),
            presetDurationSec = presetDurationSec.coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC),
            targets = copiedTargets
        )
        presets += created
        val nextActiveId = if (activate) created.id else getActivePresetId(context)
        persistPresetsInternal(context, presets, nextActiveId)
        return created
    }

    fun renamePreset(context: Context, presetId: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        target.name = clean
        persistPresetsInternal(context, presets, getActivePresetId(context))
        return true
    }

    fun setPresetExecutionMode(context: Context, presetId: String, mode: ExecutionMode): Boolean {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        target.executionMode = mode
        persistPresetsInternal(context, presets, getActivePresetId(context))
        return true
    }

    fun setPresetLoopCondition(
        context: Context,
        presetId: String,
        loopCount: Int,
        durationSec: Int
    ): Boolean {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        target.presetLoopCount = loopCount.coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT)
        target.presetDurationSec = durationSec.coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC)
        persistPresetsInternal(context, presets, getActivePresetId(context))
        return true
    }

    fun applyGlobalTapSettingsToAllPresets(
        context: Context,
        settings: ClickGlobalSettings
    ): Int {
        val presets = loadPresets(context)
        val intervalMs = settings.defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val pressMs = settings.defaultPressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        var updatedCount = 0
        for (preset in presets) {
            for (target in preset.targets) {
                if (target.actionType == ActionType.TAP) {
                    target.intervalMs = intervalMs
                    target.pressDurationMs = pressMs
                    updatedCount++
                }
            }
        }
        persistPresetsInternal(context, presets, getActivePresetId(context))
        return updatedCount
    }

    fun resolveTargetsForExecution(
        targets: List<ClickTarget>,
        settings: ClickGlobalSettings
    ): List<ClickTarget> {
        val intervalMs = settings.defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val pressMs = settings.defaultPressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        return targets.map { target ->
            if (target.actionType == ActionType.TAP) {
                target.copy(intervalMs = intervalMs, pressDurationMs = pressMs)
            } else {
                target.copy()
            }
        }
    }

    fun deletePreset(context: Context, presetId: String): Boolean {
        val presets = loadPresets(context)
        if (presets.size <= 1) return false
        val idx = presets.indexOfFirst { it.id == presetId }
        if (idx < 0) return false
        val currentActive = getActivePresetId(context)
        presets.removeAt(idx)
        val nextActive = if (currentActive == presetId) {
            presets[idx.coerceAtMost(presets.lastIndex)].id
        } else {
            currentActive
        }
        persistPresetsInternal(context, presets, nextActive)
        return true
    }

    fun switchToNextPreset(context: Context): Triple<Int, Int, ClickPreset> {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        val currentIdx = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % presets.size
        val nextPreset = presets[nextIdx]
        persistPresetsInternal(context, presets, nextPreset.id)
        return Triple(nextIdx, presets.size, nextPreset)
    }

    fun exportPresetToJson(preset: ClickPreset): String {
        val obj = JSONObject().apply {
            put("schema", "zerointerrupt.preset.v2")
            put("name", preset.name)
            put("executionMode", preset.executionMode.code)
            put("presetLoopCount", preset.presetLoopCount.coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT))
            put("presetDurationSec", preset.presetDurationSec.coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC))
            put("targets", targetsToJsonArray(preset.targets))
        }
        return obj.toString(2)
    }

    fun importPresetFromJson(
        context: Context,
        rawJson: String,
        screenW: Int,
        screenH: Int
    ): ClickPreset? {
        return try {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return null
            val name: String
            val execMode: ExecutionMode
            val loopCount: Int
            val durationSec: Int
            val targets: MutableList<ClickTarget>
            if (trimmed.startsWith("[")) {
                name = "导入方案"
                execMode = ExecutionMode.CONCURRENT
                loopCount = 0
                durationSec = 0
                targets = jsonArrayToTargets(JSONArray(trimmed))
            } else {
                val obj = JSONObject(trimmed)
                name = obj.optString("name", "").ifBlank { "导入方案" }
                execMode = ExecutionMode.fromCode(obj.optInt("executionMode", 0))
                loopCount = obj.optInt("presetLoopCount", 0)
                    .coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT)
                durationSec = obj.optInt("presetDurationSec", 0)
                    .coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC)
                targets = jsonArrayToTargets(obj.optJSONArray("targets"))
            }
            targets.forEach { it.remapToScreen(screenW, screenH) }
            createPreset(
                context = context,
                name = name,
                initialTargets = targets,
                executionMode = execMode,
                activate = true,
                presetLoopCount = loopCount,
                presetDurationSec = durationSec
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun loadTargets(context: Context): MutableList<ClickTarget> =
        getActivePreset(context).targets.map { it.copy() }.toMutableList()

    fun loadTargetsForScreen(context: Context, screenW: Int, screenH: Int): MutableList<ClickTarget> =
        getActivePreset(context).targets.map { t ->
            t.copy().also { it.remapToScreen(screenW, screenH) }
        }.toMutableList()

    fun saveTargets(context: Context, targets: List<ClickTarget>) {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        val active = presets.firstOrNull { it.id == activeId } ?: presets.first()
        active.targets.clear()
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            active.targets += t.copy(index = idx + 1)
        }
        persistPresetsInternal(context, presets, active.id)
    }

    fun saveControlBarPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONTROL_BAR_X, x)
            .putInt(KEY_CONTROL_BAR_Y, y)
            .apply()
    }

    fun loadControlBarPosition(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CONTROL_BAR_X) || !prefs.contains(KEY_CONTROL_BAR_Y)) {
            return null
        }
        return Pair(prefs.getInt(KEY_CONTROL_BAR_X, 40), prefs.getInt(KEY_CONTROL_BAR_Y, 220))
    }

    fun saveControlBarCollapseState(context: Context, collapsed: Boolean, dockRight: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONTROL_BAR_COLLAPSED, collapsed)
            .putBoolean(KEY_CONTROL_BAR_DOCK_RIGHT, dockRight)
            .apply()
    }

    fun loadControlBarDockRight(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTROL_BAR_DOCK_RIGHT, false)

    fun loadControlBarCollapsed(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTROL_BAR_COLLAPSED, false)
}
