package com.zerointerrupt.clicker.ui.tabs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.ImportJsonDialog
import com.zerointerrupt.clicker.ui.components.OverflowMenuAction
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import com.zerointerrupt.clicker.ui.components.PresetMiniCanvas
import com.zerointerrupt.clicker.ui.components.PresetNameDialog
import com.zerointerrupt.clicker.ui.components.TargetEditDialog
import kotlin.math.roundToInt

@Composable
fun PresetsTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTargetPreset by remember { mutableStateOf<ClickPreset?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInitialText by remember { mutableStateOf("") }
    var editingTarget by remember { mutableStateOf<ClickTarget?>(null) }
    var showNewTargetDialog by remember { mutableStateOf(false) }
    var stopConditionPreset by remember { mutableStateOf<ClickPreset?>(null) }

    val activePreset = state.activePreset

    val menuActions = listOf(
        OverflowMenuAction(
            label = "导入 JSON",
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                importInitialText = if (clip.contains("\"targets\"")) clip else ""
                showImportDialog = true
            }
        ),
        OverflowMenuAction(
            label = "导出当前方案",
            enabled = activePreset != null,
            onClick = {
                if (activePreset != null) {
                    callbacks.onExportPresetJson(activePreset)
                }
            }
        )
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columnCount = if (maxWidth >= 700.dp) 3 else 2
        val presetRows = remember(state.presets, columnCount) {
            state.presets.chunked(columnCount)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PageTopHeader(
                    title = "方案",
                    menuActions = menuActions,
                    trailingAction = {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "新建方案",
                                tint = cs.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            items(presetRows, key = { row -> row.joinToString(",") { it.id } }) { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowPresets.forEach { preset ->
                        PresetGridCard(
                            preset = preset,
                            isSelected = preset.id == state.activePresetId,
                            canDelete = state.presets.size > 1,
                            screenWidthPx = state.screenWidthPx,
                            screenHeightPx = state.screenHeightPx,
                            onSelect = { callbacks.onSelectPreset(preset.id) },
                            onRename = { renameTargetPreset = preset },
                            onDelete = { callbacks.onDeletePreset(preset.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columnCount - rowPresets.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (activePreset != null) {
                item {
                    Spacer(Modifier.height(4.dp))
                    ExpressiveCard {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activePreset.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${activePreset.targets.size} 节点",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { showNewTargetDialog = true },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            ExpressiveConnectedGroup(
                                options = listOf(
                                    ExpressiveOption(
                                        ExecutionMode.CONCURRENT,
                                        "并发",
                                        R.drawable.ic_touch_app
                                    ),
                                    ExpressiveOption(
                                        ExecutionMode.SEQUENTIAL,
                                        "顺序",
                                        R.drawable.ic_swipe
                                    )
                                ),
                                selectedValue = activePreset.executionMode,
                                onSelect = { mode -> callbacks.onSetPresetExecutionMode(activePreset.id, mode) },
                                height = 42.dp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val loopText = if (activePreset.presetLoopCount > 0) {
                                    "循环 ${activePreset.presetLoopCount} 轮"
                                } else {
                                    "无限轮次"
                                }
                                val timerText = if (activePreset.presetDurationSec > 0) {
                                    "定时 ${formatPresetDuration(activePreset.presetDurationSec)}"
                                } else {
                                    "不限时"
                                }
                                StopConditionPill(
                                    label = loopText,
                                    active = activePreset.presetLoopCount > 0,
                                    onClick = { stopConditionPreset = activePreset },
                                    modifier = Modifier.weight(1f)
                                )
                                StopConditionPill(
                                    label = timerText,
                                    active = activePreset.presetDurationSec > 0,
                                    onClick = { stopConditionPreset = activePreset },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (activePreset.targets.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = cs.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "暂无节点",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isConcurrent = activePreset.executionMode == ExecutionMode.CONCURRENT
                                    activePreset.targets.forEach { target ->
                                        TargetRowCard(
                                            target = target,
                                            globalIntervalMs = state.intervalMs,
                                            globalPressMs = state.pressDurationMs,
                                            isConcurrent = isConcurrent,
                                            onToggle = { enabled ->
                                                callbacks.onTogglePresetTargetEnabled(target.id, enabled)
                                            },
                                            onEdit = { editingTarget = target },
                                            onDelete = { callbacks.onDeletePresetTarget(target.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }
    }

    if (showCreateDialog) {
        PresetNameDialog(
            title = "新建方案",
            initialName = "方案 ${state.presets.size + 1}",
            confirmLabel = "创建",
            showCopySwitch = true,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, copyCurrent ->
                callbacks.onCreatePreset(name, copyCurrent)
                showCreateDialog = false
            }
        )
    }

    renameTargetPreset?.let { preset ->
        PresetNameDialog(
            title = "重命名方案",
            initialName = preset.name,
            confirmLabel = "保存",
            showCopySwitch = false,
            onDismiss = { renameTargetPreset = null },
            onConfirm = { newName, _ ->
                callbacks.onRenamePreset(preset.id, newName)
                renameTargetPreset = null
            }
        )
    }

    if (showImportDialog) {
        ImportJsonDialog(
            initialText = importInitialText,
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                callbacks.onImportPresetJson(json)
                showImportDialog = false
            }
        )
    }

    if (showNewTargetDialog) {
        TargetEditDialog(
            initial = null,
            screenW = state.screenWidthPx,
            screenH = state.screenHeightPx,
            defaultIntervalMs = state.intervalMs,
            defaultPressMs = state.pressDurationMs,
            defaultJitter = state.microJitter,
            onDismiss = { showNewTargetDialog = false },
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount ->
                callbacks.onSavePresetTarget(
                    null, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount
                )
                showNewTargetDialog = false
            }
        )
    }

    editingTarget?.let { target ->
        TargetEditDialog(
            initial = target,
            screenW = state.screenWidthPx,
            screenH = state.screenHeightPx,
            defaultIntervalMs = state.intervalMs,
            defaultPressMs = state.pressDurationMs,
            defaultJitter = state.microJitter,
            onDismiss = { editingTarget = null },
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount ->
                callbacks.onSavePresetTarget(
                    target.id, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount
                )
                editingTarget = null
            }
        )
    }

    stopConditionPreset?.let { preset ->
        PresetStopConditionDialog(
            initialLoopCount = preset.presetLoopCount,
            initialDurationSec = preset.presetDurationSec,
            onDismiss = { stopConditionPreset = null },
            onSave = { loopCount, durationSec ->
                callbacks.onSetPresetLoopCondition(preset.id, loopCount, durationSec)
                stopConditionPreset = null
            }
        )
    }
}

@Composable
private fun PresetGridCard(
    preset: ClickPreset,
    isSelected: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val ratio = (screenWidthPx.toFloat() / screenHeightPx.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 1.8f)

    val cardBg = if (isSelected) {
        lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.42f)
    } else {
        cs.surfaceContainerLow
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (isSelected) BorderStroke(1.5.dp, cs.primary) else null,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onSelect)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PresetMiniCanvas(
                    preset = preset,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    modifier = Modifier
                        .height(106.dp)
                        .aspectRatio(ratio)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = cs.primary,
                            contentColor = cs.onPrimary
                        ) {
                            Text(
                                text = "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                val modeTag = if (preset.executionMode == ExecutionMode.SEQUENTIAL) "顺序连招" else "并发"
                Text(
                    text = "${preset.targets.size} 节点 · $modeTag",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onRename, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "重命名",
                        modifier = Modifier.size(16.dp),
                        tint = cs.onSurfaceVariant
                    )
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "删除方案",
                            modifier = Modifier.size(16.dp),
                            tint = cs.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRowCard(
    target: ClickTarget,
    globalIntervalMs: Long,
    globalPressMs: Long,
    isConcurrent: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (target.enabled) cs.primary else cs.surfaceContainerHighest,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "#${target.index}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (target.enabled) cs.onPrimary else cs.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (target.actionType) {
                        ActionType.SWIPE -> Icon(
                            painter = painterResource(R.drawable.ic_swipe),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.secondary
                        )
                        ActionType.HOLD -> Icon(
                            painter = painterResource(R.drawable.ic_hold),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.tertiary
                        )
                        ActionType.TAP -> Icon(
                            painter = painterResource(R.drawable.ic_touch_app),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.primary
                        )
                    }
                    Text(
                        text = when (target.actionType) {
                            ActionType.SWIPE -> "滑动 (${target.x.roundToInt()},${target.y.roundToInt()})→(${target.endX.roundToInt()},${target.endY.roundToInt()})"
                            ActionType.HOLD -> "长按 (${target.x.roundToInt()},${target.y.roundToInt()})"
                            ActionType.TAP -> "连点 (${target.x.roundToInt()},${target.y.roundToInt()})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val repeatTag = "${target.repeatCount.coerceAtLeast(1)}次"
                val detail = when (target.actionType) {
                    ActionType.SWIPE -> "间隔 ${target.intervalMs}ms · 耗时 ${target.swipeDurationMs}ms · $repeatTag"
                    ActionType.HOLD -> if (isConcurrent) {
                        "持续长按驻留"
                    } else {
                        "长按 ${target.pressDurationMs}ms · 间隔 ${target.intervalMs}ms · $repeatTag"
                    }
                    ActionType.TAP -> "间隔 ${globalIntervalMs}ms · 按压 ${globalPressMs}ms · $repeatTag"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            CheckSwitch(
                checked = target.enabled,
                onCheckedChange = onToggle
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "删除节点",
                    modifier = Modifier.size(16.dp),
                    tint = cs.error
                )
            }
        }
    }
}

@Composable
private fun StopConditionPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) cs.secondaryContainer else cs.surfaceContainerHigh,
        contentColor = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun PresetStopConditionDialog(
    initialLoopCount: Int,
    initialDurationSec: Int,
    onDismiss: () -> Unit,
    onSave: (loopCount: Int, durationSec: Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var loopText by remember { mutableStateOf(initialLoopCount.toString()) }
    var durationText by remember { mutableStateOf(initialDurationSec.toString()) }

    val loopVal = loopText.toIntOrNull()?.coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT)
        ?: initialLoopCount
    val durVal = durationText.toIntOrNull()?.coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC)
        ?: initialDurationSec

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("停止条件", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "总轮次（全部节点各执行一轮）",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "无限",
                        1 to "1轮",
                        5 to "5轮",
                        10 to "10轮",
                        50 to "50轮"
                    ).forEach { (v, tag) ->
                        val selected = loopVal == v
                        Surface(
                            shape = CircleShape,
                            color = if (selected) cs.primary else cs.surfaceContainerHigh,
                            contentColor = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .clickable { loopText = v.toString() }
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = loopText,
                    onValueChange = { loopText = it.filter(Char::isDigit).take(4) },
                    label = { Text("自定义总轮次 (0=无限, 1~9999)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Text(
                    text = "定时停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "不限",
                        30 to "30秒",
                        60 to "1分",
                        300 to "5分",
                        1800 to "30分"
                    ).forEach { (v, tag) ->
                        val selected = durVal == v
                        Surface(
                            shape = CircleShape,
                            color = if (selected) cs.primary else cs.surfaceContainerHigh,
                            contentColor = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .clickable { durationText = v.toString() }
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter(Char::isDigit).take(5) },
                    label = { Text("自定义定时/秒 (0=不限, 1~86400)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeLoop = (loopText.toIntOrNull() ?: 0)
                        .coerceIn(ClickPreset.MIN_LOOP_COUNT, ClickPreset.MAX_LOOP_COUNT)
                    val safeDur = (durationText.toIntOrNull() ?: 0)
                        .coerceIn(ClickPreset.MIN_DURATION_SEC, ClickPreset.MAX_DURATION_SEC)
                    onSave(safeLoop, safeDur)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatPresetDuration(sec: Int): String {
    if (sec < 60) return "${sec}秒"
    val mins = sec / 60
    val rem = sec % 60
    return if (rem == 0) "${mins}分" else "${mins}分${rem}秒"
}
