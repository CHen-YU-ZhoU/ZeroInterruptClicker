package com.zerointerrupt.clicker.ui.tabs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.model.PresetQuickTriggerConfig
import com.zerointerrupt.clicker.model.RawMacroCodec
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.AnimatedSwipeTrashIcon
import com.zerointerrupt.clicker.ui.components.BatchCreateEmptyPresetsDialog
import com.zerointerrupt.clicker.ui.components.BatchDeletePresetsDialog
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveFabMenuItem
import com.zerointerrupt.clicker.ui.components.ExpressiveFloatingActionButtonMenu
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.ExpressiveSlider
import com.zerointerrupt.clicker.ui.components.ExpressiveToggleFab
import com.zerointerrupt.clicker.ui.components.ImportJsonDialog
import com.zerointerrupt.clicker.ui.components.OverflowMenuAction
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import com.zerointerrupt.clicker.ui.components.PresetMiniCanvas
import com.zerointerrupt.clicker.ui.components.PresetNameDialog
import com.zerointerrupt.clicker.ui.components.PresetTreeFreezeDialog
import com.zerointerrupt.clicker.ui.components.ScriptImportDialog
import com.zerointerrupt.clicker.ui.components.SmoothRoundedCornerShape
import com.zerointerrupt.clicker.ui.components.StickyBlurredPageHeader
import com.zerointerrupt.clicker.ui.components.TargetEditDialog
import androidx.compose.ui.unit.lerp as dpLerp
import com.zerointerrupt.clicker.ui.components.buildPresetTree
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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
    var quickTriggerConfigPreset by remember { mutableStateOf<ClickPreset?>(null) }
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
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
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
                                    contentPadding = PaddingValues(top = 74.dp, bottom = 78.dp),
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
                                            hasQuickTrigger = preset.id in state.activeQuickTriggerPresetIds,
                                            canDelete = treeNodes.size > 1,
                                            screenWidthPx = state.screenWidthPx,
                                            screenHeightPx = state.screenHeightPx,
                                            onSelect = { callbacks.onSelectPreset(preset.id) },
                                            onToggleQuickTrigger = {
                                                if (preset.id in state.activeQuickTriggerPresetIds) {
                                                    callbacks.onHidePresetQuickTrigger(preset.id)
                                                } else {
                                                    quickTriggerConfigPreset = preset
                                                }
                                            },
                                            onRename = { renameTargetPreset = preset },
                                            onExportJson = { callbacks.onExportPresetJson(preset) },
                                            onDelete = { callbacks.onDeletePreset(preset.id) }
                                        )
                                    }
                                }
                            }
                        } else if (state.presets.isNotEmpty()) {
                            AllFrozenPresetsCard(modifier = Modifier.padding(top = 74.dp))
                        }
                    }

                    // Right column: active preset tuning & step list
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.64f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(top = 74.dp, bottom = 84.dp),
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

                StickyBlurredPageHeader(
                    title = "方案",
                    menuActions = menuActions,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 74.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
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
                                                hasQuickTrigger = preset.id in state.activeQuickTriggerPresetIds,
                                                canDelete = treeNodes.size > 1,
                                                screenWidthPx = state.screenWidthPx,
                                                screenHeightPx = state.screenHeightPx,
                                                onSelect = { callbacks.onSelectPreset(preset.id) },
                                                onToggleQuickTrigger = {
                                                    if (preset.id in state.activeQuickTriggerPresetIds) {
                                                        callbacks.onHidePresetQuickTrigger(preset.id)
                                                    } else {
                                                        quickTriggerConfigPreset = preset
                                                    }
                                                },
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
                                                hasQuickTrigger = preset.id in state.activeQuickTriggerPresetIds,
                                                isChildSubPreset = (subPresetParentCount[preset.id] ?: 0) > 0,
                                                canDelete = treeNodes.size > 1,
                                                screenWidthPx = state.screenWidthPx,
                                                screenHeightPx = state.screenHeightPx,
                                                onSelect = { callbacks.onSelectPreset(preset.id) },
                                                onToggleQuickTrigger = {
                                                    if (preset.id in state.activeQuickTriggerPresetIds) {
                                                        callbacks.onHidePresetQuickTrigger(preset.id)
                                                    } else {
                                                        quickTriggerConfigPreset = preset
                                                    }
                                                },
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

                StickyBlurredPageHeader(
                    title = "方案",
                    menuActions = menuActions,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
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

    quickTriggerConfigPreset?.let { preset ->
        val initialCfg = remember(preset.id) {
            ClickConfigStore.loadQuickTriggerConfig(context, preset.id)
        }
        QuickTriggerConfigDialog(
            preset = preset,
            initialConfig = initialCfg,
            onDismiss = { quickTriggerConfigPreset = null },
            onConfirm = { config ->
                ClickConfigStore.saveQuickTriggerConfig(context, config)
                callbacks.onShowPresetQuickTrigger(config)
                quickTriggerConfigPreset = null
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
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs, triggerTargetZone ->
                callbacks.onSavePresetTarget(
                    null, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs, triggerTargetZone
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
            onSave = { x, y, intervalMs, pressMs, jitter, enabled, actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs, triggerTargetZone ->
                callbacks.onSavePresetTarget(
                    target.id, x, y, intervalMs, pressMs, jitter, enabled,
                    actionType, endX, endY, swipeDur, repeatCount, refPresetId, subDurationMs, triggerTargetZone
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
                Text(
                    text = activePreset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val isConcurrent = activePreset.executionMode == ExecutionMode.CONCURRENT
                    val lastIdx = activePreset.targets.lastIndex
                    activePreset.targets.forEachIndexed { idx, target ->
                        key(target.id) {
                            TargetRowCard(
                                target = target,
                                allPresets = state.presets,
                                isConcurrent = isConcurrent,
                                hasSeamlessHandoff = target.id in seamlessOutgoingStepIds,
                                isFirst = idx == 0,
                                isLast = idx == lastIdx,
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
}

@Composable
private fun PresetVerticalCarouselCard(
    index: Int,
    listState: LazyListState,
    itemHeight: Dp,
    preset: ClickPreset,
    allPresets: List<ClickPreset>,
    isSelected: Boolean,
    hasQuickTrigger: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onToggleQuickTrigger: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) cs.secondaryContainer else cs.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "presetVertCardBg"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 18.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "presetVertCorner"
    )
    val shape = SmoothRoundedCornerShape(cornerRadius)

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
                .background(cardBg)
                .clickable(onClick = onSelect)
        ) {
            PresetCardOverlayContent(
                preset = preset,
                allPresets = allPresets,
                isSelected = isSelected,
                hasQuickTrigger = hasQuickTrigger,
                canDelete = canDelete,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                fadeModifier = fadeModifier,
                onToggleQuickTrigger = onToggleQuickTrigger,
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
    hasQuickTrigger: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onToggleQuickTrigger: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) cs.secondaryContainer else cs.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "presetStaticCardBg"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 18.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "presetStaticCorner"
    )
    val shape = SmoothRoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .height(224.dp)
            .clip(shape)
            .background(cardBg)
            .clickable(onClick = onSelect)
    ) {
        PresetCardOverlayContent(
            preset = preset,
            allPresets = allPresets,
            isSelected = isSelected,
            hasQuickTrigger = hasQuickTrigger,
            canDelete = canDelete,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            fadeModifier = Modifier,
            onToggleQuickTrigger = onToggleQuickTrigger,
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
    hasQuickTrigger: Boolean,
    isChildSubPreset: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onSelect: () -> Unit,
    onToggleQuickTrigger: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) cs.secondaryContainer else cs.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "presetCarouselCardBg"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 30.dp else 18.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "presetCarouselCorner"
    )
    // Material3 1.3.1's CarouselItemScope.maskClip forgets to key remember() on `shape`,
    // freezing corner radius on first composition. Building the masked GenericShape with
    // `cornerRadius` in the remember keys guarantees live 120fps Bezier corner morphing.
    val liveMaskShape = remember(carouselItemInfo, density, cornerRadius) {
        GenericShape { size, direction ->
            val rect = carouselItemInfo.maskRect.intersect(size.toRect())
            addOutline(
                SmoothRoundedCornerShape(cornerRadius).createOutline(rect.size, direction, density)
            )
            translate(Offset(rect.left, rect.top))
        }
    }
    val fadeModifier = Modifier.graphicsLayer {
        val maxW = carouselItemInfo.maxSize
        val visFrac = if (maxW > 4f) (carouselItemInfo.maskRect.width / maxW).coerceIn(0f, 1f) else 1f
        alpha = ((visFrac - 0.60f) / 0.20f).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .height(224.dp)
            .clip(liveMaskShape)
            .background(cardBg)
            .clickable(onClick = onSelect)
    ) {
        PresetCardOverlayContent(
            preset = preset,
            allPresets = allPresets,
            isSelected = isSelected,
            hasQuickTrigger = hasQuickTrigger,
            canDelete = canDelete,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            fadeModifier = fadeModifier,
            onToggleQuickTrigger = onToggleQuickTrigger,
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
    hasQuickTrigger: Boolean,
    canDelete: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    fadeModifier: Modifier,
    onToggleQuickTrigger: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var cardMenuExpanded by remember { mutableStateOf(false) }

    PresetMiniCanvas(
        preset = preset,
        allPresets = allPresets,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        modifier = Modifier.fillMaxSize(),
        drawOuterFrame = false
    )

    // Top-right Files-by-Google vertical oval pill 3-dot button (rich soft dynamic tint, never white)
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 9.dp, end = 9.dp)
            .then(fadeModifier)
    ) {
        val pillBg = lerp(cs.primaryContainer, cs.primary, 0.14f)
        Surface(
            shape = CircleShape,
            color = pillBg,
            contentColor = cs.onPrimaryContainer,
            modifier = Modifier
                .width(24.dp)
                .height(34.dp)
                .clip(CircleShape)
                .clickable { cardMenuExpanded = true }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "方案操作",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(
            expanded = cardMenuExpanded,
            onDismissRequest = { cardMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (hasQuickTrigger) "关闭悬浮触发键" else "启用悬浮触发键",
                        color = if (hasQuickTrigger) cs.primary else Color.Unspecified,
                        fontWeight = if (hasQuickTrigger) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                onClick = {
                    cardMenuExpanded = false
                    onToggleQuickTrigger()
                }
            )
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

    // Bottom-right Gmail-style 3D flip badge: unselected preset name -> flips around bottom-right axis into checkmark circle
    val flipRotation by animateFloatAsState(
        targetValue = if (isSelected) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f),
        label = "presetBadgeFlip"
    )
    val cjkUnits = remember(preset.name) {
        preset.name.sumOf { ch -> if (ch.code > 0x7F) 2 else 1 }
    }
    val isLongName = cjkUnits >= 16

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(start = 9.dp, end = 9.dp, bottom = 9.dp)
            .then(fadeModifier),
        contentAlignment = Alignment.BottomEnd
    ) {
        if (flipRotation <= 90f) {
            val badgeBg = lerp(cs.primaryContainer, cs.primary, 0.14f)
            Surface(
                shape = if (isLongName) RoundedCornerShape(12.dp) else CircleShape,
                color = badgeBg,
                contentColor = cs.onPrimaryContainer,
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .graphicsLayer {
                        val checkHalfPx = with(density) { 16.dp.toPx() }
                        val pivotX = if (size.width > 1f) {
                            (1f - checkHalfPx / size.width).coerceIn(0.5f, 0.94f)
                        } else {
                            0.5f
                        }
                        transformOrigin = TransformOrigin(pivotX, 0.5f)
                        rotationY = flipRotation
                        cameraDistance = 12f * density.density
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(
                        horizontal = if (isLongName) 8.dp else 10.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text(
                        text = preset.name,
                        style = if (isLongName) {
                            MaterialTheme.typography.labelMedium.copy(
                                fontSize = 6.5.sp,
                                lineHeight = 8.5.sp
                            )
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = if (isLongName) 2 else 1,
                        softWrap = isLongName,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Surface(
                shape = CircleShape,
                color = cs.primary,
                contentColor = cs.onPrimary,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        rotationY = flipRotation - 180f
                        cameraDistance = 12f * density.density
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "已选中",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetRowCard(
    target: ClickTarget,
    allPresets: List<ClickPreset>,
    isConcurrent: Boolean,
    hasSeamlessHandoff: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val refPreset = if (target.actionType == ActionType.SUB_PRESET) {
        allPresets.firstOrNull { it.id == target.refPresetId }
    } else null

    var cardWidthPx by remember { mutableIntStateOf(0) }
    val offsetX = remember { Animatable(0f) }
    var isDismissing by remember { mutableStateOf(false) }

    val thresholdPx = (cardWidthPx * 0.40f).coerceAtLeast(1f)
    val absOffset = abs(offsetX.value)
    val pastThreshold = cardWidthPx > 0 && absOffset >= thresholdPx

    var wasPastThreshold by remember { mutableStateOf(false) }
    LaunchedEffect(pastThreshold) {
        if (pastThreshold && !wasPastThreshold) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!pastThreshold && wasPastThreshold && absOffset > 8f) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasPastThreshold = pastThreshold
    }

    val capsuleBg by animateColorAsState(
        targetValue = if (pastThreshold) cs.error else cs.errorContainer,
        animationSpec = tween(160),
        label = "swipeCapsuleBg"
    )
    val capsuleIconTint by animateColorAsState(
        targetValue = if (pastThreshold) cs.onError else cs.onErrorContainer,
        animationSpec = tween(160),
        label = "swipeCapsuleTint"
    )
    val swipeProgress = if (cardWidthPx > 0) (absOffset / thresholdPx).coerceIn(0f, 1.3f) else 0f
    val iconScale by animateFloatAsState(
        targetValue = if (pastThreshold) 1.24f else (0.72f + 0.28f * swipeProgress.coerceAtMost(1f)),
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 520f),
        label = "swipeIconScale"
    )
    val capsuleScaleY by animateFloatAsState(
        targetValue = if (pastThreshold) 1.0f else (0.76f + 0.18f * swipeProgress.coerceAtMost(1f)),
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 480f),
        label = "swipeCapsuleScaleY"
    )
    val isSwiping = absOffset > 6f
    val topCorner by animateDpAsState(
        targetValue = if (isSwiping || isFirst) 20.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f),
        label = "swipeTopCorner"
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isSwiping || isLast) 20.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f),
        label = "swipeBottomCorner"
    )
    val cardShape = RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = bottomCorner,
        bottomEnd = bottomCorner
    )

    val draggableState = rememberDraggableState { delta ->
        if (!isDismissing) {
            scope.launch {
                val maxSlide = cardWidthPx.toFloat().coerceAtLeast(1f)
                offsetX.snapTo((offsetX.value + delta).coerceIn(-maxSlide, maxSlide))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { cardWidthPx = it.width }
    ) {
        // Gmail / Material 3 Expressive capsule scaling delete background
        if (absOffset > 1f) {
            val swipingRight = offsetX.value > 0f
            val revealedDp = with(density) { (absOffset - 6.dp.toPx()).coerceAtLeast(0f).toDp() }
            val capsuleWidth = revealedDp.coerceAtLeast(36.dp)
            val capsuleAlpha = (swipeProgress * 3.2f).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 2.dp),
                contentAlignment = if (swipingRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(capsuleWidth)
                        .graphicsLayer {
                            scaleY = capsuleScaleY
                            alpha = capsuleAlpha
                        }
                        .clip(CircleShape)
                        .background(capsuleBg),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedSwipeTrashIcon(
                        swipeProgress = swipeProgress,
                        pastThreshold = pastThreshold,
                        swipingRight = swipingRight,
                        tint = capsuleIconTint,
                        cutoutColor = capsuleBg,
                        size = 22.dp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                    )
                }
            }
        }

        Surface(
            shape = cardShape,
            color = cs.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(cardShape)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = !isDismissing,
                    onDragStopped = { velocity ->
                        val cur = offsetX.value
                        val absCur = abs(cur)
                        val shouldDismiss = cardWidthPx > 0 && (
                            absCur >= thresholdPx ||
                                (absCur >= cardWidthPx * 0.28f && sign(velocity) == sign(cur) && abs(velocity) > 1300f)
                            )
                        scope.launch {
                            if (shouldDismiss) {
                                isDismissing = true
                                val dir = if (cur >= 0f) 1f else -1f
                                offsetX.animateTo(
                                    targetValue = dir * cardWidthPx * 1.05f,
                                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                                )
                                onDelete()
                            } else {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f)
                                )
                            }
                        }
                    }
                )
                .clickable(enabled = !isDismissing && absOffset < 4f, onClick = onEdit)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (target.enabled) cs.primary else cs.surfaceContainerHighest,
                    modifier = Modifier.size(32.dp)
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
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when (target.actionType) {
                            ActionType.SWIPE -> Icon(
                                painter = painterResource(R.drawable.ic_swipe),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = cs.secondary
                            )
                            ActionType.HOLD -> Icon(
                                painter = painterResource(R.drawable.ic_hold),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = cs.tertiary
                            )
                            ActionType.SUB_PRESET -> Icon(
                                painter = painterResource(R.drawable.ic_layers),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = cs.primary
                            )
                            ActionType.TAP -> Icon(
                                painter = painterResource(R.drawable.ic_touch_app),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
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
                                ActionType.SUB_PRESET -> "并发方案 · ${refPreset?.name ?: "未找到方案"}"
                                ActionType.TAP -> "连点 (${target.x.roundToInt()},${target.y.roundToInt()})"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (hasSeamlessHandoff && !isConcurrent) {
                        Surface(
                            shape = CircleShape,
                            color = cs.secondaryContainer,
                            contentColor = cs.onSecondaryContainer
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
            }
        }
    }
}

@Composable
private fun QuickTriggerConfigDialog(
    preset: ClickPreset,
    initialConfig: PresetQuickTriggerConfig,
    onDismiss: () -> Unit,
    onConfirm: (PresetQuickTriggerConfig) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var sizeDp by remember(preset.id) { mutableIntStateOf(initialConfig.sizeDp) }
    var shapeMode by remember(preset.id) { mutableIntStateOf(initialConfig.shapeMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                text = "悬浮触发键 · ${preset.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shape selector
                ExpressiveConnectedGroup(
                    options = listOf(
                        ExpressiveOption(PresetQuickTriggerConfig.SHAPE_PILL, "胶囊"),
                        ExpressiveOption(PresetQuickTriggerConfig.SHAPE_SQUIRCLE, "圆角"),
                        ExpressiveOption(PresetQuickTriggerConfig.SHAPE_CIRCLE, "圆形")
                    ),
                    selectedValue = shapeMode,
                    onSelect = { shapeMode = it },
                    height = 38.dp
                )

                // Size slider
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "大小",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface
                        )
                        Text(
                            text = "${sizeDp} dp",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary
                        )
                    }
                    ExpressiveSlider(
                        value = sizeDp.toFloat(),
                        valueRange = PresetQuickTriggerConfig.MIN_SIZE_DP.toFloat()..
                            PresetQuickTriggerConfig.MAX_SIZE_DP.toFloat(),
                        onValueChange = { raw ->
                            val next = raw.roundToInt().coerceIn(
                                PresetQuickTriggerConfig.MIN_SIZE_DP,
                                PresetQuickTriggerConfig.MAX_SIZE_DP
                            )
                            if (next != sizeDp) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                sizeDp = next
                            }
                        },
                        onValueChangeFinished = {},
                        floatingLabel = "${sizeDp}dp"
                    )
                }

                Text(
                    text = "点按启停 · 拖动调位 · 拖入销毁",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        PresetQuickTriggerConfig(
                            presetId = preset.id,
                            sizeDp = sizeDp,
                            shapeMode = shapeMode,
                            normX = initialConfig.normX,
                            normY = initialConfig.normY
                        )
                    )
                },
                shape = CircleShape
            ) {
                Text("确定", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
