package com.zerointerrupt.clicker.ui.tabs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.BatchCreateEmptyPresetsDialog
import com.zerointerrupt.clicker.ui.components.BatchDeletePresetsDialog
import com.zerointerrupt.clicker.ui.components.BatchFreezePresetsDialog
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveFabMenuItem
import com.zerointerrupt.clicker.ui.components.ExpressiveFloatingActionButtonMenu
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.ExpressiveToggleFab
import com.zerointerrupt.clicker.ui.components.ImportJsonDialog
import com.zerointerrupt.clicker.ui.components.OverflowMenuAction
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import com.zerointerrupt.clicker.ui.components.PresetMiniCanvas
import com.zerointerrupt.clicker.ui.components.PresetNameDialog
import com.zerointerrupt.clicker.ui.components.ScriptImportDialog
import com.zerointerrupt.clicker.ui.components.TargetEditDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var fabMenuExpanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showScriptDialog by remember { mutableStateOf(false) }
    var scriptInitialText by remember { mutableStateOf("") }
    var renameTargetPreset by remember { mutableStateOf<ClickPreset?>(null) }
    var showBatchImportDialog by remember { mutableStateOf(false) }
    var batchImportInitialText by remember { mutableStateOf("") }
    var showBatchFreezeDialog by remember { mutableStateOf(false) }
    var showBatchUnfreezeDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCreateDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<ClickTarget?>(null) }
    var showNewTargetDialog by remember { mutableStateOf(false) }

    val visiblePresets = remember(state.presets) { state.presets.filter { !it.isFrozen } }
    val displayedPreset = remember(visiblePresets, state.activePresetId) {
        visiblePresets.firstOrNull { it.id == state.activePresetId } ?: visiblePresets.firstOrNull()
    }
    LaunchedEffect(displayedPreset?.id, state.activePresetId) {
        if (displayedPreset != null && displayedPreset.id != state.activePresetId) {
            callbacks.onSelectPreset(displayedPreset.id)
        }
    }
    val presetIdsKey = remember(visiblePresets) { visiblePresets.joinToString("|") { it.id } }
    val latestPresetCount by rememberUpdatedState(visiblePresets.size)
    val seamlessOutgoingStepIds = remember(state.presets, displayedPreset, state.screenWidthPx, state.screenHeightPx) {
        if (displayedPreset == null) emptySet()
        else ClickConfigStore.findSeamlessMergeStepIds(
            presets = state.presets,
            activePreset = displayedPreset,
            screenW = state.screenWidthPx,
            screenH = state.screenHeightPx
        )
    }
    val subPresetParentCount = remember(state.presets) {
        val counts = HashMap<String, Int>()
        for (p in state.presets) {
            if (p.executionMode != ExecutionMode.SEQUENTIAL) continue
            for (t in p.targets) {
                if (t.actionType == ActionType.SUB_PRESET && t.refPresetId.isNotBlank()) {
                    counts[t.refPresetId] = (counts[t.refPresetId] ?: 0) + 1
                }
            }
        }
        counts
    }

    val hasUnfrozenPreset = state.presets.any { !it.isFrozen }
    val hasFrozenPreset = state.presets.any { it.isFrozen }
    val menuActions = listOf(
        OverflowMenuAction(
            label = "批量导出 JSON",
            enabled = state.presets.isNotEmpty(),
            onClick = callbacks.onExportAllPresetsJson
        ),
        OverflowMenuAction(
            label = "批量导入 JSON",
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                batchImportInitialText = if (clip.contains("\"presets\"") || clip.contains("\"targets\"")) clip else ""
                showBatchImportDialog = true
            }
        ),
        OverflowMenuAction(
            label = "批量冻结方案",
            enabled = hasUnfrozenPreset,
            onClick = { showBatchFreezeDialog = true }
        ),
        OverflowMenuAction(
            label = "批量解冻方案",
            enabled = hasFrozenPreset,
            onClick = { showBatchUnfreezeDialog = true }
        ),
        OverflowMenuAction(
            label = "批量删除方案",
            enabled = state.presets.size > 1,
            isDestructive = true,
            onClick = { showBatchDeleteDialog = true }
        ),
        OverflowMenuAction(
            label = "批量添加空方案",
            onClick = { showBatchCreateDialog = true }
        )
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PageTopHeader(
                    title = "方案",
                    menuActions = menuActions,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            if (visiblePresets.isNotEmpty()) {
                item {
                    // Re-key on structural preset IDs so Material3 1.3.1 PagerState refreshes immediately on add/delete/freeze/unfreeze
                    key(presetIdsKey) {
                        val activeIdx = visiblePresets.indexOfFirst { it.id == displayedPreset?.id }.coerceAtLeast(0)
                        val carouselState = rememberCarouselState(initialItem = activeIdx) { latestPresetCount }
                        HorizontalMultiBrowseCarousel(
                            state = carouselState,
                            preferredItemWidth = 148.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp),
                            itemSpacing = 10.dp,
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) { index ->
                            val preset = visiblePresets.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
                            PresetCarouselCard(
                                preset = preset,
                                allPresets = state.presets,
                                isSelected = preset.id == displayedPreset?.id,
                                isChildSubPreset = (subPresetParentCount[preset.id] ?: 0) > 0,
                                canDelete = state.presets.size > 1,
                                screenWidthPx = state.screenWidthPx,
                                screenHeightPx = state.screenHeightPx,
                                onSelect = { callbacks.onSelectPreset(preset.id) },
                                onRename = { renameTargetPreset = preset },
                                onExportJson = { callbacks.onExportPresetJson(preset) },
                                onDelete = { callbacks.onDeletePreset(preset.id) }
                            )
                        }
                    }
                }
            } else if (state.presets.isNotEmpty()) {
                item {
                    ExpressiveCard(
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "当前方案均已冻结",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "# 开头的冻结方案已从方案页隐藏，点击「批量解冻方案」或新建方案即可继续使用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = { showBatchUnfreezeDialog = true },
                                shape = CircleShape
                            ) {
                                Text("批量解冻方案", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            val activePreset = displayedPreset
            if (activePreset != null) {
                item {
                    ExpressiveCard(
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
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
                                        text = "${activePreset.targets.size} 步骤",
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

                            if (activePreset.targets.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = cs.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "暂无步骤，点击右上角「+ 添加」配置手势或子方案",
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
                                            allPresets = state.presets,
                                            globalIntervalMs = state.intervalMs,
                                            globalPressMs = state.pressDurationMs,
                                            isConcurrent = isConcurrent,
                                            hasSeamlessHandoff = target.id in seamlessOutgoingStepIds,
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

            item { Spacer(Modifier.height(78.dp)) }
        }

        AnimatedVisibility(
            visible = fabMenuExpanded,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { fabMenuExpanded = false }
            )
        }

        PresetAddSpeedDial(
            expanded = fabMenuExpanded,
            onToggle = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                fabMenuExpanded = !fabMenuExpanded
            },
            onRecord = {
                fabMenuExpanded = false
                callbacks.onStartMacroRecording()
            },
            onManual = {
                fabMenuExpanded = false
                showCreateDialog = true
            },
            onScript = {
                fabMenuExpanded = false
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                scriptInitialText = if (
                    clip.contains("\"targets\"") ||
                    clip.startsWith("tap ") ||
                    clip.startsWith("hold ") ||
                    clip.startsWith("# name:")
                ) clip else ""
                showScriptDialog = true
            },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    if (showCreateDialog) {
        PresetNameDialog(
            title = "手动新建方案",
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

    if (showScriptDialog) {
        ScriptImportDialog(
            initialText = scriptInitialText,
            onDismiss = { showScriptDialog = false },
            onImport = { script ->
                callbacks.onImportPresetScript(script)
                showScriptDialog = false
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

    if (showBatchImportDialog) {
        ImportJsonDialog(
            initialText = batchImportInitialText,
            title = "批量导入 JSON",
            hintText = "粘贴导出的多方案 JSON 包或单方案 JSON 文本：",
            confirmLabel = "批量导入",
            onDismiss = { showBatchImportDialog = false },
            onImport = { json ->
                callbacks.onBatchImportPresetsJson(json)
                showBatchImportDialog = false
            }
        )
    }

    if (showBatchFreezeDialog) {
        BatchFreezePresetsDialog(
            presets = state.presets,
            activePresetId = state.activePresetId,
            freezeMode = true,
            onDismiss = { showBatchFreezeDialog = false },
            onConfirm = { selectedIds ->
                callbacks.onBatchFreezePresets(selectedIds)
                showBatchFreezeDialog = false
            }
        )
    }

    if (showBatchUnfreezeDialog) {
        BatchFreezePresetsDialog(
            presets = state.presets,
            activePresetId = state.activePresetId,
            freezeMode = false,
            onDismiss = { showBatchUnfreezeDialog = false },
            onConfirm = { selectedIds ->
                callbacks.onBatchUnfreezePresets(selectedIds)
                showBatchUnfreezeDialog = false
            }
        )
    }

    if (showBatchDeleteDialog) {
        BatchDeletePresetsDialog(
            presets = state.presets,
            activePresetId = state.activePresetId,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = { selectedIds ->
                callbacks.onBatchDeletePresets(selectedIds)
                showBatchDeleteDialog = false
            }
        )
    }

    if (showBatchCreateDialog) {
        BatchCreateEmptyPresetsDialog(
            existingCount = state.presets.size,
            onDismiss = { showBatchCreateDialog = false },
            onConfirm = { count, mode ->
                callbacks.onBatchCreateEmptyPresets(count, mode)
                showBatchCreateDialog = false
            }
        )
    }

    if (showNewTargetDialog && displayedPreset != null) {
        TargetEditDialog(
            initial = null,
            activePresetId = displayedPreset.id,
            executionMode = displayedPreset.executionMode,
            allPresets = state.presets,
            screenW = state.screenWidthPx,
            screenH = state.screenHeightPx,
            defaultIntervalMs = state.intervalMs,
            defaultPressMs = state.pressDurationMs,
            defaultJitter = state.microJitter,
            onDismiss = { showNewTargetDialog = false },
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs ->
                callbacks.onSavePresetTarget(
                    null, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs
                )
                showNewTargetDialog = false
            }
        )
    }

    if (editingTarget != null && displayedPreset != null) {
        val target = editingTarget!!
        TargetEditDialog(
            initial = target,
            activePresetId = displayedPreset.id,
            executionMode = displayedPreset.executionMode,
            allPresets = state.presets,
            screenW = state.screenWidthPx,
            screenH = state.screenHeightPx,
            defaultIntervalMs = state.intervalMs,
            defaultPressMs = state.pressDurationMs,
            defaultJitter = state.microJitter,
            onDismiss = { editingTarget = null },
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs ->
                callbacks.onSavePresetTarget(
                    target.id, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs
                )
                editingTarget = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselItemScope.PresetCarouselCard(
    preset: ClickPreset,
    allPresets: List<ClickPreset>,
    isSelected: Boolean,
    isChildSubPreset: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var cardMenuExpanded by remember { mutableStateOf(false) }

    val cardBg = if (isSelected) {
        lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.42f)
    } else {
        cs.surfaceContainerLow
    }
    val shape = RoundedCornerShape(24.dp)
    val fadeModifier = Modifier.graphicsLayer {
        val maxW = carouselItemInfo.maxSize
        val visFrac = if (maxW > 4f) (carouselItemInfo.maskRect.width / maxW).coerceIn(0f, 1f) else 1f
        alpha = ((visFrac - 0.60f) / 0.20f).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .height(210.dp)
            .maskClip(shape)
            .then(
                if (isSelected) {
                    Modifier.maskBorder(BorderStroke(1.5.dp, cs.primary), shape)
                } else {
                    Modifier.maskBorder(BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.4f)), shape)
                }
            )
            .background(cardBg)
            .clickable(onClick = onSelect)
    ) {
        // Slim centered phone layout preview filling the slim card cleanly
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 52.dp, start = 12.dp, end = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            PresetMiniCanvas(
                preset = preset,
                allPresets = allPresets,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                modifier = Modifier.height(132.dp)
            )
        }

        // Top overlay: active badge (left) + compact ⋮ menu matching Files by Google (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .then(fadeModifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            Box {
                Surface(
                    shape = CircleShape,
                    color = cs.surfaceContainerLowest.copy(alpha = 0.85f),
                    contentColor = cs.onSurface,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { cardMenuExpanded = true }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "方案操作",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = cardMenuExpanded,
                    onDismissRequest = { cardMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            cardMenuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("导出为 JSON") },
                        onClick = {
                            cardMenuExpanded = false
                            onExportJson()
                        }
                    )
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text("删除方案", color = cs.error) },
                            onClick = {
                                cardMenuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }

        // Bottom info cleanly inside narrow card width
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(cardBg.copy(alpha = 0.86f))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .then(fadeModifier),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val modeTag = when {
                preset.executionMode == ExecutionMode.SEQUENTIAL -> "顺序"
                isChildSubPreset -> "并发子方案"
                else -> "并发"
            }
            Text(
                text = "${preset.targets.size} 步骤 · $modeTag",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TargetRowCard(
    target: ClickTarget,
    allPresets: List<ClickPreset>,
    globalIntervalMs: Long,
    globalPressMs: Long,
    isConcurrent: Boolean,
    hasSeamlessHandoff: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val refPreset = if (target.actionType == ActionType.SUB_PRESET) {
        allPresets.firstOrNull { it.id == target.refPresetId }
    } else null

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
                        ActionType.SUB_PRESET -> Icon(
                            painter = painterResource(R.drawable.ic_layers),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.primary
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
                            ActionType.HOLD -> if (!isConcurrent && target.pressDurationMs <= 120L && target.repeatCount >= 1) {
                                "按住点击 (${target.x.roundToInt()},${target.y.roundToInt()})"
                            } else {
                                "长按 (${target.x.roundToInt()},${target.y.roundToInt()})"
                            }
                            ActionType.SUB_PRESET -> "并发方案: ${refPreset?.name ?: "未找到方案"}"
                            ActionType.TAP -> "连点 (${target.x.roundToInt()},${target.y.roundToInt()})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val repeatTag = if (target.repeatCount <= 0) "无限" else "${target.repeatCount}次"
                val gapLabel = if (target.intervalMs <= 0L) "无缝(0ms)" else "停顿 ${target.intervalMs}ms"
                val detail = when (target.actionType) {
                    ActionType.SUB_PRESET -> when {
                        isConcurrent -> "仅在顺序模式中生效"
                        refPreset == null -> "引用的方案已不存在"
                        refPreset.executionMode != ExecutionMode.CONCURRENT -> "引用方案当前非并发模式"
                        target.repeatCount <= 0 -> "${refPreset.targets.count { it.enabled }} 节点 · 无限并发"
                        else -> "持续 ${formatMsShort(target.subDurationMs)} · $gapLabel · $repeatTag"
                    }
                    ActionType.SWIPE -> "${if (!isConcurrent) gapLabel else "间隔 ${target.intervalMs}ms"} · 耗时 ${target.swipeDurationMs}ms · $repeatTag"
                    ActionType.HOLD -> if (target.repeatCount <= 0) {
                        "持续长按驻留 · 无限"
                    } else {
                        "按住 ${target.pressDurationMs}ms · ${if (!isConcurrent) gapLabel else "间隔 ${target.intervalMs}ms"} · $repeatTag"
                    }
                    ActionType.TAP -> "间隔 ${globalIntervalMs}ms · 按压 ${globalPressMs}ms · $repeatTag"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasSeamlessHandoff && !isConcurrent) {
                    Surface(
                        shape = CircleShape,
                        color = cs.secondaryContainer,
                        contentColor = cs.onSecondaryContainer,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "不断触衔接下步",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            CheckSwitch(
                checked = target.enabled,
                onCheckedChange = onToggle
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "删除步骤",
                    modifier = Modifier.size(16.dp),
                    tint = cs.error
                )
            }
        }
    }
}

private fun formatMsShort(ms: Long): String {
    return if (ms >= 1000L && ms % 1000L == 0L) {
        "${ms / 1000L}秒"
    } else {
        "${ms}ms"
    }
}

@Composable
private fun PresetAddSpeedDial(
    expanded: Boolean,
    onToggle: () -> Unit,
    onRecord: () -> Unit,
    onManual: () -> Unit,
    onScript: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExpressiveFloatingActionButtonMenu(
        expanded = expanded,
        horizontalAlignment = Alignment.End,
        modifier = modifier,
        button = {
            ExpressiveToggleFab(
                checked = expanded,
                onCheckedChange = { onToggle() },
                initialSize = 48.dp,
                finalSize = 52.dp,
                initialCornerRadius = 14.dp,
                finalCornerRadius = 26.dp
            ) { progress, iconColor ->
                val p = progress()
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = if (expanded) "收起新建方式" else "新增方案",
                    tint = iconColor,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = p * 45f }
                )
            }
        }
    ) {
        ExpressiveFabMenuItem(
            onClick = onScript,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_terminal),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            },
            text = { Text("脚本", maxLines = 1) }
        )
        ExpressiveFabMenuItem(
            onClick = onManual,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            },
            text = { Text("手动", maxLines = 1) }
        )
        ExpressiveFabMenuItem(
            onClick = onRecord,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_record),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            },
            text = { Text("录制", maxLines = 1) }
        )
    }
}
