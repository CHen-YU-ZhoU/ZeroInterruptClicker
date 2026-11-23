package com.zerointerrupt.clicker.ui.tabs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.model.RawMacroCodec
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.BatchCreateEmptyPresetsDialog
import com.zerointerrupt.clicker.ui.components.BatchDeletePresetsDialog
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
import com.zerointerrupt.clicker.ui.components.PresetTreeFreezeDialog
import com.zerointerrupt.clicker.ui.components.ScriptImportDialog
import com.zerointerrupt.clicker.ui.components.SmoothRoundedCornerShape
import com.zerointerrupt.clicker.ui.components.TargetEditDialog
import androidx.compose.ui.unit.lerp as dpLerp
import com.zerointerrupt.clicker.ui.components.buildPresetTree
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
    var showTreeFreezeDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCreateDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<ClickTarget?>(null) }
    var showNewTargetDialog by remember { mutableStateOf(false) }
    var showLoopCountDialog by remember { mutableStateOf(false) }

    val treeNodes = remember(state.presets) { buildPresetTree(state.presets) }
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

    val frozenCount = remember(state.presets) { state.presets.count { it.isFrozen } }
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
            label = "冻结 / 解冻方案",
            badge = if (frozenCount > 0) "${frozenCount}已冻" else null,
            enabled = state.presets.isNotEmpty(),
            onClick = { showTreeFreezeDialog = true }
        ),
        OverflowMenuAction(
            label = "批量删除方案",
            enabled = treeNodes.size > 1,
            isDestructive = true,
            onClick = { showBatchDeleteDialog = true }
        ),
        OverflowMenuAction(
            label = "批量添加空方案",
            onClick = { showBatchCreateDialog = true }
        )
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscapeTwoColumn = maxWidth >= 600.dp && maxWidth > maxHeight

        if (isLandscapeTwoColumn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageTopHeader(
                    title = "方案",
                    menuActions = menuActions,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left column: landscape wide-square preset overview with vertical carousel masking & snapping
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(0.36f)
                            .fillMaxHeight()
                    ) {
                        val itemHeight = (maxWidth / 1.65f).coerceIn(124.dp, 210.dp)
                        val itemGap = 10.dp

                        if (visiblePresets.isNotEmpty()) {
                            key(presetIdsKey) {
                                val activeIdx = visiblePresets
                                    .indexOfFirst { it.id == displayedPreset?.id }
                                    .coerceAtLeast(0)
                                val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIdx)
                                val snapFling = rememberSnapFlingBehavior(
                                    lazyListState = listState,
                                    snapPosition = SnapPosition.Start
                                )

                                LazyColumn(
                                    state = listState,
                                    flingBehavior = snapFling,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 2.dp, bottom = 78.dp),
                                    verticalArrangement = Arrangement.spacedBy(itemGap)
                                ) {
                                    itemsIndexed(
                                        items = visiblePresets,
                                        key = { _, preset -> preset.id }
                                    ) { index, preset ->
                                        PresetVerticalCarouselCard(
                                            index = index,
                                            listState = listState,
                                            itemHeight = itemHeight,
                                            preset = preset,
                                            allPresets = state.presets,
                                            isSelected = preset.id == displayedPreset?.id,
                                            canDelete = treeNodes.size > 1,
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
                            AllFrozenPresetsCard(modifier = Modifier.padding(top = 2.dp))
                        }
                    }

                    // Right column: active preset tuning & step list
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.64f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val activePreset = displayedPreset
                        if (activePreset != null) {
                            item(key = activePreset.id) {
                                PresetDetailCard(
                                    activePreset = activePreset,
                                    state = state,
                                    callbacks = callbacks,
                                    seamlessOutgoingStepIds = seamlessOutgoingStepIds,
                                    onShowNewTargetDialog = { showNewTargetDialog = true },
                                    onShowLoopCountDialog = { showLoopCountDialog = true },
                                    onEditTarget = { editingTarget = it }
                                )
                            }
                        }
                    }
                }
            }
        } else {
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
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val horizontalPadding = 20.dp
                            val itemGap = 10.dp
                            val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(240.dp)
                            if (visiblePresets.size <= 3) {
                                val cardWidth = ((availableWidth - itemGap * 2) / 3f).coerceIn(108.dp, 156.dp)
                                val fillThreeAcross = visiblePresets.size == 3 && maxWidth < 600.dp
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = horizontalPadding)
                                        .height(224.dp),
                                    horizontalArrangement = Arrangement.spacedBy(itemGap)
                                ) {
                                    visiblePresets.forEach { preset ->
                                        val cardModifier = if (fillThreeAcross) {
                                            Modifier.weight(1f)
                                        } else {
                                            Modifier.width(cardWidth)
                                        }
                                        PresetStaticCard(
                                            preset = preset,
                                            allPresets = state.presets,
                                            isSelected = preset.id == displayedPreset?.id,
                                            canDelete = treeNodes.size > 1,
                                            screenWidthPx = state.screenWidthPx,
                                            screenHeightPx = state.screenHeightPx,
                                            onSelect = { callbacks.onSelectPreset(preset.id) },
                                            onRename = { renameTargetPreset = preset },
                                            onExportJson = { callbacks.onExportPresetJson(preset) },
                                            onDelete = { callbacks.onDeletePreset(preset.id) },
                                            modifier = cardModifier
                                        )
                                    }
                                }
                            } else {
                                // Re-key on structural preset IDs so Material3 1.3.1 PagerState refreshes immediately on add/delete/freeze/unfreeze
                                key(presetIdsKey) {
                                    val activeIdx = visiblePresets.indexOfFirst { it.id == displayedPreset?.id }.coerceAtLeast(0)
                                    val carouselState = rememberCarouselState(initialItem = activeIdx) { latestPresetCount }
                                    HorizontalMultiBrowseCarousel(
                                        state = carouselState,
                                        preferredItemWidth = 128.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(224.dp),
                                        itemSpacing = itemGap,
                                        contentPadding = PaddingValues(horizontal = horizontalPadding)
                                    ) { index ->
                                        val preset = visiblePresets.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
                                        PresetCarouselCard(
                                            preset = preset,
                                            allPresets = state.presets,
                                            isSelected = preset.id == displayedPreset?.id,
                                            isChildSubPreset = (subPresetParentCount[preset.id] ?: 0) > 0,
                                            canDelete = treeNodes.size > 1,
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
                        }
                    }
                } else if (state.presets.isNotEmpty()) {
                    item {
                        AllFrozenPresetsCard(
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }

                val activePreset = displayedPreset
                if (activePreset != null) {
                    item {
                        PresetDetailCard(
                            activePreset = activePreset,
                            state = state,
                            callbacks = callbacks,
                            seamlessOutgoingStepIds = seamlessOutgoingStepIds,
                            onShowNewTargetDialog = { showNewTargetDialog = true },
                            onShowLoopCountDialog = { showLoopCountDialog = true },
                            onEditTarget = { editingTarget = it },
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }

                item { Spacer(Modifier.height(78.dp)) }
            }
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
            onRawMacroRecord = {
                fabMenuExpanded = false
                callbacks.onStartRawMacroRecording()
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
            initialName = "方案 ${ClickConfigStore.nextMainPresetNumber(state.presets)}",
            confirmLabel = "创建",
            showCopySwitch = true,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, copyCurrent ->
                callbacks.onCreatePreset(name, copyCurrent)
                showCreateDialog = false
            }
        )
    }

    if (showLoopCountDialog && displayedPreset != null) {
        var loopText by remember(displayedPreset.id, displayedPreset.presetLoopCount) {
            mutableStateOf(displayedPreset.presetLoopCount.toString())
        }
        val parsedLoop = loopText.toIntOrNull()?.coerceIn(0, ClickTarget.MAX_REPEAT_COUNT)
        AlertDialog(
            onDismissRequest = { showLoopCountDialog = false },
            title = { Text("设置整套执行遍数", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "0 表示无限循环；>= 1 表示顺序跑完指定遍数后自动停止并释放触发状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = loopText,
                        onValueChange = { raw -> loopText = raw.filter(Char::isDigit).take(4) },
                        label = { Text("执行遍数 (0=无限, 1~9999)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedLoop != null,
                    onClick = {
                        callbacks.onSetPresetLoopCount(displayedPreset.id, parsedLoop ?: 0)
                        showLoopCountDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showLoopCountDialog = false }) { Text("取消") }
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

    if (showTreeFreezeDialog) {
        PresetTreeFreezeDialog(
            presets = state.presets,
            activePresetId = state.activePresetId,
            onDismiss = { showTreeFreezeDialog = false },
            onConfirm = { frozenIds ->
                callbacks.onApplyPresetFreezeStates(frozenIds)
                showTreeFreezeDialog = false
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
            nextNumber = ClickConfigStore.nextMainPresetNumber(state.presets),
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

@Composable
private fun AllFrozenPresetsCard(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "当前方案均已冻结",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "已冻结的方案不在主方案页展示；请点击右上角 ⋮ 菜单中的「冻结 / 解冻方案」解冻，或点击右下角新建方案。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PresetDetailCard(
    activePreset: ClickPreset,
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    seamlessOutgoingStepIds: Set<Int>,
    onShowNewTargetDialog: () -> Unit,
    onShowLoopCountDialog: () -> Unit,
    onEditTarget: (ClickTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val rawSummary = remember(activePreset.isRawMacro, activePreset.rawMacroJson) {
        if (activePreset.isRawMacro) RawMacroCodec.summarize(activePreset.rawMacroJson) else null
    }
    ExpressiveCard(modifier = modifier) {
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
                    val detailSubtitle = if (activePreset.isRawMacro) {
                        if (rawSummary != null) {
                            "录宏 · ${rawSummary.strokeCount} 笔 · ${formatMsShort(rawSummary.totalDurationMs)}"
                        } else {
                            "录宏 · 原始触控回放"
                        }
                    } else {
                        val detailModeLabel = if (activePreset.executionMode == ExecutionMode.SEQUENTIAL) {
                            "顺序"
                        } else {
                            "并发"
                        }
                        "${activePreset.targets.size} 步骤 · $detailModeLabel"
                    }
                    Text(
                        text = detailSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                if (!activePreset.isRawMacro) {
                    FilledTonalButton(
                        onClick = onShowNewTargetDialog,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (!activePreset.isRawMacro) {
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
            }

            if (activePreset.isRawMacro || activePreset.executionMode == ExecutionMode.SEQUENTIAL) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "整套执行遍数",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (activePreset.presetLoopCount <= 0) {
                                "无限循环"
                            } else {
                                "跑完 ${activePreset.presetLoopCount} 遍自动停止"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "无限", 1 to "1遍", 3 to "3遍", 5 to "5遍").forEach { (cnt, tag) ->
                            FilterChip(
                                selected = activePreset.presetLoopCount == cnt,
                                onClick = { callbacks.onSetPresetLoopCount(activePreset.id, cnt) },
                                label = { Text(tag, maxLines = 1) }
                            )
                        }
                        val isCustomLoop = activePreset.presetLoopCount !in listOf(0, 1, 3, 5)
                        FilterChip(
                            selected = isCustomLoop,
                            onClick = onShowLoopCountDialog,
                            label = {
                                Text(
                                    text = if (isCustomLoop) "${activePreset.presetLoopCount}遍" else "自定义",
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            if (activePreset.isRawMacro) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = cs.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "原始触控宏（不转译步骤）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface
                        )
                        Text(
                            text = if (rawSummary != null) {
                                "完整保留 ${rawSummary.strokeCount} 笔触控与 ${rawSummary.pointCount} 个采样点（基准分辨率 ${rawSummary.screenW}×${rawSummary.screenH}），播放时按原始时序与轨迹 1:1 复现。"
                            } else {
                                "播放时按原始触控时序与轨迹 1:1 复现。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            } else if (activePreset.targets.isEmpty()) {
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
                            onEdit = { onEditTarget(target) },
                            onDelete = { callbacks.onDeletePresetTarget(target.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetVerticalCarouselCard(
    index: Int,
    listState: LazyListState,
    itemHeight: Dp,
    preset: ClickPreset,
    allPresets: List<ClickPreset>,
    isSelected: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val cardBg = if (isSelected) {
        lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.36f)
    } else {
        cs.surfaceContainerHigh
    }
    val shape = SmoothRoundedCornerShape(24.dp)
    val borderStroke = if (isSelected) {
        BorderStroke(1.8.dp, cs.primary)
    } else {
        BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.50f))
    }

    // Mirrors portrait HorizontalMultiBrowseCarousel fade curve: badges/labels fade smoothly when compressed below 80%
    val fadeModifier = Modifier.graphicsLayer {
        val info = listState.layoutInfo
        val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
        val visFrac = if (itemInfo != null && itemInfo.size > 4 && info.viewportSize.height > 0) {
            val vpStart = info.viewportStartOffset
            val vpEnd = info.viewportEndOffset
            val top = itemInfo.offset
            val bottom = top + itemInfo.size
            val visible = (minOf(bottom, vpEnd) - maxOf(top, vpStart)).coerceAtLeast(0)
            (visible.toFloat() / itemInfo.size.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        alpha = ((visFrac - 0.60f) / 0.20f).coerceIn(0f, 1f)
    }

    // Fixed outer height keeps LazyColumn scroll & SnapFling physics rock-solid;
    // inner card dynamically masks/compresses into a full-rounded pill at viewport top/bottom edges.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val fullH = constraints.maxHeight
                    val info = listState.layoutInfo
                    val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                    val minH = 44.dp.roundToPx().coerceAtMost(fullH)
                    val targetH: Int
                    val yOffset: Int
                    if (itemInfo == null || info.viewportSize.height <= 0) {
                        targetH = fullH
                        yOffset = 0
                    } else {
                        val vpStart = info.viewportStartOffset
                        val vpEnd = info.viewportEndOffset
                        val top = itemInfo.offset
                        val bottom = top + itemInfo.size
                        if (top < vpStart) {
                            val visible = (bottom - vpStart).coerceIn(minH, fullH)
                            targetH = visible
                            yOffset = fullH - visible
                        } else if (bottom > vpEnd) {
                            val visible = (vpEnd - top).coerceIn(minH, fullH)
                            targetH = visible
                            yOffset = 0
                        } else {
                            targetH = fullH
                            yOffset = 0
                        }
                    }
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = targetH, maxHeight = targetH)
                    )
                    layout(placeable.width, fullH) {
                        placeable.placeRelative(0, yOffset)
                    }
                }
                .clip(shape)
                .border(borderStroke, shape)
                .background(cardBg)
                .clickable(onClick = onSelect)
        ) {
            PresetCardOverlayContent(
                preset = preset,
                allPresets = allPresets,
                isSelected = isSelected,
                canDelete = canDelete,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                fadeModifier = fadeModifier,
                onRename = onRename,
                onExportJson = onExportJson,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun PresetStaticCard(
    preset: ClickPreset,
    allPresets: List<ClickPreset>,
    isSelected: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val cardBg = if (isSelected) {
        lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.36f)
    } else {
        cs.surfaceContainerHigh
    }
    val shape = SmoothRoundedCornerShape(24.dp)
    val borderStroke = if (isSelected) {
        BorderStroke(1.8.dp, cs.primary)
    } else {
        BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.50f))
    }

    Box(
        modifier = modifier
            .height(224.dp)
            .clip(shape)
            .border(borderStroke, shape)
            .background(cardBg)
            .clickable(onClick = onSelect)
    ) {
        PresetCardOverlayContent(
            preset = preset,
            allPresets = allPresets,
            isSelected = isSelected,
            canDelete = canDelete,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            fadeModifier = Modifier,
            onRename = onRename,
            onExportJson = onExportJson,
            onDelete = onDelete
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
    val cardBg = if (isSelected) {
        lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.36f)
    } else {
        cs.surfaceContainerHigh
    }
    // Outer radius 24dp with 9dp badge inset and 30dp (r = 15dp) circle badges => concentric 15 + 9 = 24dp.
    val shape = SmoothRoundedCornerShape(24.dp)
    val fadeModifier = Modifier.graphicsLayer {
        val maxW = carouselItemInfo.maxSize
        val visFrac = if (maxW > 4f) (carouselItemInfo.maskRect.width / maxW).coerceIn(0f, 1f) else 1f
        alpha = ((visFrac - 0.60f) / 0.20f).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .height(224.dp)
            .maskClip(shape)
            .then(
                if (isSelected) {
                    Modifier.maskBorder(BorderStroke(1.8.dp, cs.primary), shape)
                } else {
                    Modifier.maskBorder(BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.50f)), shape)
                }
            )
            .background(cardBg)
            .clickable(onClick = onSelect)
    ) {
        PresetCardOverlayContent(
            preset = preset,
            allPresets = allPresets,
            isSelected = isSelected,
            canDelete = canDelete,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            fadeModifier = fadeModifier,
            onRename = onRename,
            onExportJson = onExportJson,
            onDelete = onDelete
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PresetCardOverlayContent(
    preset: ClickPreset,
    allPresets: List<ClickPreset>,
    isSelected: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    fadeModifier: Modifier,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var cardMenuExpanded by remember { mutableStateOf(false) }

    PresetMiniCanvas(
        preset = preset,
        allPresets = allPresets,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        modifier = Modifier.fillMaxSize(),
        drawOuterFrame = false
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(9.dp)
            .then(fadeModifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Surface(
                shape = CircleShape,
                color = cs.primary,
                contentColor = cs.onPrimary,
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "使用中",
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        } else {
            Spacer(Modifier.size(30.dp))
        }

        Box {
            Surface(
                shape = CircleShape,
                color = cs.surfaceContainerLowest.copy(alpha = 0.88f),
                contentColor = cs.onSurface,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { cardMenuExpanded = true }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "方案操作",
                        modifier = Modifier.size(17.dp)
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

    Surface(
        shape = CircleShape,
        color = if (isSelected) {
            cs.primaryContainer.copy(alpha = 0.92f)
        } else {
            cs.surfaceContainerLowest.copy(alpha = 0.88f)
        },
        contentColor = if (isSelected) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 9.dp, bottom = 9.dp)
            .heightIn(min = 28.dp)
            .then(fadeModifier)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
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
    onRawMacroRecord: () -> Unit,
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
                initialSize = 58.dp,
                finalSize = 48.dp,
                initialCornerRadius = 18.dp,
                finalCornerRadius = 24.dp
            ) { progress, iconColor ->
                val p = progress()
                val iconSize = dpLerp(26.dp, 22.dp, p.coerceIn(0f, 1f))
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = if (expanded) "收起新建方式" else "新增方案",
                    tint = iconColor,
                    modifier = Modifier
                        .size(iconSize)
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
            onClick = onRawMacroRecord,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            },
            text = { Text("录宏", maxLines = 1) }
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
