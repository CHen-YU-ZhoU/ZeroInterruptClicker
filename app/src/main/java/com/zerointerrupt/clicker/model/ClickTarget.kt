package com.zerointerrupt.clicker.model

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.zerointerrupt.clicker.ClickTargetParceled
import org.json.JSONArray
import org.json.JSONObject

/**
 * Click target anchor configuration with Parcelable support.
 */
data class ClickTarget(
    val id: Int,
    var index: Int,
    var x: Float,
    var y: Float,
    var intervalMs: Long = DEFAULT_INTERVAL_MS,
    var pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
    var microJitter: Boolean = true,
    var enabled: Boolean = true
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        index = parcel.readInt(),
        x = parcel.readFloat(),
        y = parcel.readFloat(),
        intervalMs = parcel.readLong(),
        pressDurationMs = parcel.readLong(),
        microJitter = parcel.readByte().toInt() != 0,
        enabled = parcel.readByte().toInt() != 0
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeInt(index)
        parcel.writeFloat(x)
        parcel.writeFloat(y)
        parcel.writeLong(intervalMs)
        parcel.writeLong(pressDurationMs)
        parcel.writeByte(if (microJitter) 1 else 0)
        parcel.writeByte(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    fun toParceled(): ClickTargetParceled = ClickTargetParceled().apply {
        id = this@ClickTarget.id
        x = this@ClickTarget.x
        y = this@ClickTarget.y
        intervalMs = this@ClickTarget.intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        pressDurationMs = this@ClickTarget.pressDurationMs.coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
        microJitter = this@ClickTarget.microJitter
        enabled = this@ClickTarget.enabled
    }

    companion object {
        const val MIN_INTERVAL_MS = 5L
        const val MAX_INTERVAL_MS = 60_000L
        const val DEFAULT_INTERVAL_MS = 80L

        const val MIN_PRESS_MS = 8L
        const val MAX_PRESS_MS = 5_000L
        const val DEFAULT_PRESS_DURATION_MS = 25L

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
    val targets: MutableList<ClickTarget> = mutableListOf()
)

data class ClickGlobalSettings(
    val defaultIntervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val defaultPressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val defaultMicroJitter: Boolean = true,
    val jitterRadiusPx: Float = 4.0f,
    val selfCheckPassed: Boolean = false,
    val autoLockOnStart: Boolean = true,
    val autoCollapseBar: Boolean = true
)

object ClickConfigStore {
    private const val PREF_NAME = "zero_interrupt_clicker_prefs"
    private const val KEY_TARGETS = "saved_targets_json"
    private const val KEY_PRESETS_JSON = "saved_presets_json"
    private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
    private const val DEFAULT_PRESET_ID = "preset_1"
    private const val KEY_DEFAULT_INTERVAL = "default_interval_ms"
    private const val KEY_DEFAULT_PRESS = "default_press_ms"
    private const val KEY_DEFAULT_JITTER = "default_jitter"
    private const val KEY_JITTER_RADIUS = "jitter_radius_px"
    private const val KEY_SELF_CHECK_PASSED = "self_check_passed"
    private const val KEY_AUTO_LOCK_ON_START = "auto_lock_on_start"
    private const val KEY_AUTO_COLLAPSE_BAR = "auto_collapse_bar"
    private const val KEY_CONTROL_BAR_X = "control_bar_x"
    private const val KEY_CONTROL_BAR_Y = "control_bar_y"
    private const val KEY_CONTROL_BAR_DOCK_RIGHT = "control_bar_dock_right"
    private const val KEY_CONTROL_BAR_COLLAPSED = "control_bar_collapsed"

    fun loadSettings(context: Context): ClickGlobalSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return ClickGlobalSettings(
            defaultIntervalMs = prefs.getLong(KEY_DEFAULT_INTERVAL, ClickTarget.DEFAULT_INTERVAL_MS)
                .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
            defaultPressDurationMs = prefs.getLong(KEY_DEFAULT_PRESS, ClickTarget.DEFAULT_PRESS_DURATION_MS)
                .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
            defaultMicroJitter = prefs.getBoolean(KEY_DEFAULT_JITTER, true),
            jitterRadiusPx = prefs.getFloat(KEY_JITTER_RADIUS, 4.0f).coerceIn(0f, 20f),
            selfCheckPassed = prefs.getBoolean(KEY_SELF_CHECK_PASSED, false),
            autoLockOnStart = prefs.getBoolean(KEY_AUTO_LOCK_ON_START, true),
            autoCollapseBar = prefs.getBoolean(KEY_AUTO_COLLAPSE_BAR, true)
        )
    }

    fun saveSettings(context: Context, settings: ClickGlobalSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DEFAULT_INTERVAL, settings.defaultIntervalMs)
            .putLong(KEY_DEFAULT_PRESS, settings.defaultPressDurationMs)
            .putBoolean(KEY_DEFAULT_JITTER, settings.defaultMicroJitter)
            .putFloat(KEY_JITTER_RADIUS, settings.jitterRadiusPx)
            .putBoolean(KEY_SELF_CHECK_PASSED, settings.selfCheckPassed)
            .putBoolean(KEY_AUTO_LOCK_ON_START, settings.autoLockOnStart)
            .putBoolean(KEY_AUTO_COLLAPSE_BAR, settings.autoCollapseBar)
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
            result += ClickTarget(
                id = obj.optInt("id", i + 1),
                index = i + 1,
                x = obj.optDouble("x", 0.0).toFloat(),
                y = obj.optDouble("y", 0.0).toFloat(),
                intervalMs = obj.optLong("intervalMs", ClickTarget.DEFAULT_INTERVAL_MS)
                    .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
                pressDurationMs = obj.optLong("pressDurationMs", ClickTarget.DEFAULT_PRESS_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
                microJitter = obj.optBoolean("microJitter", true),
                enabled = obj.optBoolean("enabled", true)
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
                put("intervalMs", t.intervalMs)
                put("pressDurationMs", t.pressDurationMs)
                put("microJitter", t.microJitter)
                put("enabled", t.enabled)
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
                    val targets = jsonArrayToTargets(obj.optJSONArray("targets"))
                    list += ClickPreset(id = id, name = name, targets = targets)
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
        activate: Boolean = true
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
        val created = ClickPreset(id = newId, name = cleanName, targets = copiedTargets)
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

    fun loadTargets(context: Context): MutableList<ClickTarget> =
        getActivePreset(context).targets.map { it.copy() }.toMutableList()

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
