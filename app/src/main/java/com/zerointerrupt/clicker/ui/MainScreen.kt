package com.zerointerrupt.clicker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.shizuku.EngineSnapshot
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class MainUiState(
    val connectionState: ShizukuConnectionState = ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
    val engineSnapshot: EngineSnapshot = EngineSnapshot(),
    val overlayVisible: Boolean = false,
    val clickingRunning: Boolean = false,
    val overlayPermGranted: Boolean = false,
    val notifPermGranted: Boolean = false,
    val showNotifRow: Boolean = false,
    val selfCheckPassed: Boolean = false,
    val intervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val pressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val microJitter: Boolean = true,
    val autoLockOnStart: Boolean = true,
    val autoCollapseBar: Boolean = true,
    val selfCheckRemainingMs: Long = 0L,
    val selfCheckTimeoutMs: Long = 5000L,
    val presets: List<ClickPreset> = emptyList(),
    val activePresetId: String = "",
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 2400
)

data class MainScreenCallbacks(
    val onToggleOverlay: () -> Unit,
    val onGrantOverlay: () -> Unit,
    val onGrantShizuku: () -> Unit,
    val onGrantNotif: () -> Unit,
    val onStartSelfCheck: () -> Unit,
    val onConfirmSelfCheck: () -> Unit,
    val onRollbackSelfCheck: () -> Unit,
    val onReleaseNative: () -> Unit,
    val onIntervalChange: (value: Long, commit: Boolean) -> Unit,
    val onPressChange: (value: Long, commit: Boolean) -> Unit,
    val onJitterChange: (Boolean) -> Unit,
    val onAutoLockChange: (Boolean) -> Unit,
    val onAutoCollapseChange: (Boolean) -> Unit,
    val onApplyDefaultsToCurrentPreset: () -> Unit,
    val onSelectPreset: (String) -> Unit,
    val onCreatePreset: (name: String, copyCurrent: Boolean) -> Unit,
    val onRenamePreset: (presetId: String, newName: String) -> Unit,
    val onDeletePreset: (presetId: String) -> Unit,
    val onSavePresetTarget: (
        targetId: Int?,
        x: Float,
        y: Float,
        intervalMs: Long,
        pressDurationMs: Long,
        microJitter: Boolean,
        enabled: Boolean
    ) -> Unit,
    val onTogglePresetTargetEnabled: (targetId: Int, enabled: Boolean) -> Unit,
    val onDeletePresetTarget: (targetId: Int) -> Unit
)

private enum class NumericEditKind {
    INTERVAL,
    PRESS
}

private data class TargetEditDraft(
    val existingId: Int?,
    val x: Float,
    val y: Float,
    val intervalMs: Long,
    val pressDurationMs: Long,
    val microJitter: Boolean,
    val enabled: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    snackbarHostState: SnackbarHostState
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var editKind by remember { mutableStateOf<NumericEditKind?>(null) }
    var showCreatePresetDialog by remember { mutableStateOf(false) }
    var renamingPreset by remember { mutableStateOf<ClickPreset?>(null) }
    var editingTargetDraft by remember { mutableStateOf<TargetEditDraft?>(null) }

    val activePreset = state.presets.firstOrNull { it.id == state.activePresetId } ?: state.presets.firstOrNull()

    fun openAddTargetDialog() {
        val count = activePreset?.targets?.size ?: 0
        val offset = ((count % 5) - 2) * 80f
        editingTargetDraft = TargetEditDraft(
            existingId = null,
            x = (state.screenWidthPx * 0.5f + offset).coerceIn(40f, (state.screenWidthPx - 40).coerceAtLeast(40).toFloat()),
            y = (state.screenHeightPx * 0.5f + offset * 0.5f).coerceIn(40f, (state.screenHeightPx - 40).coerceAtLeast(40).toFloat()),
            intervalMs = state.intervalMs,
            pressDurationMs = state.pressDurationMs,
            microJitter = state.microJitter,
            enabled = true
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "零断触连点器",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "TouchMux 硬件合流 · 预设方案 · 极简折叠悬浮胶囊",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            val isWide = maxWidth >= 700.dp
            Column(
                modifier = Modifier
                    .widthIn(max = if (isWide) 980.dp else 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            EngineHeroCard(state = state, onToggleOverlay = callbacks.onToggleOverlay)
                            PermissionCard(state = state, callbacks = callbacks)
                            SelfCheckCard(state = state, callbacks = callbacks)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            PresetSchemesCard(
                                state = state,
                                activePreset = activePreset,
                                onSelectPreset = callbacks.onSelectPreset,
                                onRequestCreatePreset = { showCreatePresetDialog = true },
                                onRequestRenamePreset = { preset -> renamingPreset = preset },
                                onDeletePreset = callbacks.onDeletePreset,
                                onRequestAddTarget = ::openAddTargetDialog,
                                onRequestEditTarget = { target ->
                                    editingTargetDraft = TargetEditDraft(
                                        existingId = target.id,
                                        x = target.x,
                                        y = target.y,
                                        intervalMs = target.intervalMs,
                                        pressDurationMs = target.pressDurationMs,
                                        microJitter = target.microJitter,
                                        enabled = target.enabled
                                    )
                                },
                                onToggleTargetEnabled = callbacks.onTogglePresetTargetEnabled,
                                onDeleteTarget = callbacks.onDeletePresetTarget
                            )
                            GlobalSettingsCard(
                                state = state,
                                callbacks = callbacks,
                                onRequestExactEdit = { editKind = it }
                            )
                        }
                    }
                } else {
                    EngineHeroCard(state = state, onToggleOverlay = callbacks.onToggleOverlay)
                    PresetSchemesCard(
                        state = state,
                        activePreset = activePreset,
                        onSelectPreset = callbacks.onSelectPreset,
                        onRequestCreatePreset = { showCreatePresetDialog = true },
                        onRequestRenamePreset = { preset -> renamingPreset = preset },
                        onDeletePreset = callbacks.onDeletePreset,
                        onRequestAddTarget = ::openAddTargetDialog,
                        onRequestEditTarget = { target ->
                            editingTargetDraft = TargetEditDraft(
                                existingId = target.id,
                                x = target.x,
                                y = target.y,
                                intervalMs = target.intervalMs,
                                pressDurationMs = target.pressDurationMs,
                                microJitter = target.microJitter,
                                enabled = target.enabled
                            )
                        },
                        onToggleTargetEnabled = callbacks.onTogglePresetTargetEnabled,
                        onDeleteTarget = callbacks.onDeletePresetTarget
                    )
                    GlobalSettingsCard(
                        state = state,
                        callbacks = callbacks,
                        onRequestExactEdit = { editKind = it }
                    )
                    PermissionCard(state = state, callbacks = callbacks)
                    SelfCheckCard(state = state, callbacks = callbacks)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (state.selfCheckRemainingMs > 0L) {
        SelfCheckCountdownDialog(
            remainingMs = state.selfCheckRemainingMs,
            totalMs = state.selfCheckTimeoutMs,
            onConfirm = callbacks.onConfirmSelfCheck,
            onRollback = callbacks.onRollbackSelfCheck
        )
    }

    editKind?.let { kind ->
        ExactMillisInputDialog(
            kind = kind,
            currentValue = if (kind == NumericEditKind.INTERVAL) state.intervalMs else state.pressDurationMs,
            onDismiss = { editKind = null },
            onApply = { value ->
                if (kind == NumericEditKind.INTERVAL) {
                    callbacks.onIntervalChange(value, true)
                } else {
                    callbacks.onPressChange(value, true)
                }
                editKind = null
            }
        )
    }

    if (showCreatePresetDialog) {
        CreatePresetDialog(
            suggestedName = "方案 ${state.presets.size + 1}",
            hasCurrentTargets = (activePreset?.targets?.isNotEmpty() == true),
            onDismiss = { showCreatePresetDialog = false },
            onConfirm = { name, copyCurrent ->
                callbacks.onCreatePreset(name, copyCurrent)
                showCreatePresetDialog = false
            }
        )
    }

    renamingPreset?.let { preset ->
        RenamePresetDialog(
            initialName = preset.name,
            onDismiss = { renamingPreset = null },
            onConfirm = { newName ->
                callbacks.onRenamePreset(preset.id, newName)
                renamingPreset = null
            }
        )
    }

    editingTargetDraft?.let { draft ->
        PresetTargetEditDialog(
            draft = draft,
            screenWidthPx = state.screenWidthPx,
            screenHeightPx = state.screenHeightPx,
            onDismiss = { editingTargetDraft = null },
            onSave = { x, y, intervalMs, pressMs, jitter, enabled ->
                callbacks.onSavePresetTarget(
                    draft.existingId,
                    x,
                    y,
                    intervalMs,
                    pressMs,
                    jitter,
                    enabled
                )
                editingTargetDraft = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineHeroCard(
    state: MainUiState,
    onToggleOverlay: () -> Unit
) {
    val conn = state.connectionState
    val snap = state.engineSnapshot
    val nativeCapable = snap.uinputWritable && snap.touchDeviceReadable
    val nativeActive = snap.engineMode == TouchMuxUserService.MODE_NATIVE_MUX
    val fallbackActive = snap.engineMode == TouchMuxUserService.MODE_SHIZUKU_FALLBACK

    val targetContainerColor = when {
        conn != ShizukuConnectionState.CONNECTED -> MaterialTheme.colorScheme.surfaceVariant
        nativeActive || (nativeCapable && state.selfCheckPassed) -> MaterialTheme.colorScheme.primaryContainer
        nativeCapable -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val containerColor by animateColorAsState(targetValue = targetContainerColor, label = "heroCardColor")
    val contentColor = when {
        conn != ShizukuConnectionState.CONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        nativeActive || (nativeCapable && state.selfCheckPassed) -> MaterialTheme.colorScheme.onPrimaryContainer
        nativeCapable -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val headline = when {
        conn == ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> "等待连接 Shizuku 服务"
        conn == ShizukuConnectionState.NO_PERMISSION -> "Shizuku 已运行，待授权"
        conn == ShizukuConnectionState.BINDING -> "正在绑定触控服务..."
        conn == ShizukuConnectionState.ERROR -> "Shizuku 连接异常"
        nativeActive -> "原生 TouchMux 硬件合流激活"
        nativeCapable && state.selfCheckPassed -> "原生 TouchMux 已通过自检，就绪"
        nativeCapable -> "本机支持免 Root TouchMux！建议先完成 5 秒自检"
        fallbackActive -> "Shizuku UID ${snap.uid} 异步注入运行中"
        snap.fallbackReady -> "Shizuku 异步注入通道就绪"
        else -> "引擎待机中"
    }

    val badgeLabel = when {
        state.clickingRunning -> "连点运行中"
        state.overlayVisible -> "悬浮窗已开启"
        nativeCapable && state.selfCheckPassed -> "原生零断触就绪"
        conn == ShizukuConnectionState.CONNECTED -> "服务已连接"
        else -> "未就绪"
    }

    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = Modifier.fillMaxWidth()
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = contentColor.copy(alpha = 0.14f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_touch_app),
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "引擎与硬件通道",
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (conn == ShizukuConnectionState.CONNECTED) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusInfoChip(text = "UID ${snap.uid}")
                    StatusInfoChip(
                        text = if (snap.uinputWritable) "/dev/uinput 可写" else "/dev/uinput 受限"
                    )
                    StatusInfoChip(
                        text = if (snap.touchDeviceReadable) "物理节点可读" else "物理节点受限"
                    )
                    StatusInfoChip(
                        text = if (snap.fallbackReady) "Fallback 可用" else "Fallback 不可用"
                    )
                }

                if (snap.touchDeviceInfo.isNotBlank() || snap.nativeProbeError.isNotBlank()) {
                    Text(
                        text = buildString {
                            if (snap.touchDeviceInfo.isNotBlank()) {
                                append("触控设备: ").append(snap.touchDeviceInfo)
                            }
                            if (snap.nativeProbeError.isNotBlank()) {
                                if (isNotEmpty()) append("\n")
                                append("诊断: ").append(snap.nativeProbeError)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.82f)
                    )
                }
            } else {
                Text(
                    text = snap.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }

            Button(
                onClick = onToggleOverlay,
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(vertical = 15.dp, horizontal = 20.dp),
                colors = if (state.overlayVisible) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (state.overlayVisible) Icons.Rounded.Close else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.overlayVisible) "关闭游戏悬浮控制条" else "开启游戏悬浮控制条",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatusInfoChip(text: String) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = null,
        modifier = Modifier.height(28.dp)
    )
}

@Composable
private fun PermissionCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val conn = state.connectionState
    val shizukuSubtitle = when (conn) {
        ShizukuConnectionState.CONNECTED -> "已授权并绑定特权触控服务"
        ShizukuConnectionState.NO_PERMISSION -> "待授权，点击右侧授权"
        ShizukuConnectionState.BINDING -> "正在绑定 UserService..."
        ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> "Shizuku 未启动，点击去启动或刷新"
        ShizukuConnectionState.ERROR -> "连接异常，请重启 Shizuku 后重试"
    }
    val shizukuAction = when (conn) {
        ShizukuConnectionState.CONNECTED -> "重测"
        ShizukuConnectionState.NO_PERMISSION -> "授权"
        else -> "启动/刷新"
    }

    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "基础权限向导",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            PermissionActionRow(
                iconRes = R.drawable.ic_layers,
                title = "1. 悬浮窗显示权限",
                subtitle = if (state.overlayPermGranted) "已开启（游戏内可显示控制胶囊与锚点）" else "未授权（悬浮窗必需）",
                granted = state.overlayPermGranted,
                buttonText = if (state.overlayPermGranted) "已开启" else "去开启",
                onClick = callbacks.onGrantOverlay
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            PermissionActionRow(
                iconRes = R.drawable.ic_touch_app,
                title = "2. Shizuku 跨进程触控权限",
                subtitle = shizukuSubtitle,
                granted = conn == ShizukuConnectionState.CONNECTED,
                buttonText = shizukuAction,
                onClick = callbacks.onGrantShizuku
            )

            if (state.showNotifRow) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PermissionActionRow(
                    iconRes = R.drawable.ic_notifications,
                    title = "3. 通知权限（前台防杀 & 急停）",
                    subtitle = if (state.notifPermGranted) "已授权" else "未授权（推荐开启防止切后台被杀）",
                    granted = state.notifPermGranted,
                    buttonText = if (state.notifPermGranted) "已授权" else "去授权",
                    buttonEnabled = !state.notifPermGranted,
                    onClick = callbacks.onGrantNotif
                )
            }
        }
    }
}

@Composable
private fun PermissionActionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    granted: Boolean,
    buttonText: String,
    buttonEnabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(8.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        FilledTonalButton(
            onClick = onClick,
            enabled = buttonEnabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            if (granted && !buttonEnabled) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = buttonText, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SelfCheckCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val conn = state.connectionState
    val snap = state.engineSnapshot
    val nativeCapable = snap.uinputWritable && snap.touchDeviceReadable
    val selfCheckEnabled = conn == ShizukuConnectionState.CONNECTED && nativeCapable

    val hintText = when {
        conn == ShizukuConnectionState.CONNECTED && !nativeCapable ->
            "当前运行于标准 Shizuku (UID ${snap.uid}) 模式，内核节点 /dev/uinput 受 SELinux 策略限制 (${snap.nativeProbeError})，系统已自动选用进程内异步注入通道。"
        state.selfCheckPassed && nativeCapable ->
            "已完成 5 秒硬件接管安全自检！开始连点时将自动通过 TouchMux 在物理屏底层合流；若 5 秒内出现异常或点击【释放独占】可立即退出接管。"
        else ->
            "开启 EVIOCGRAB 硬件独占合流前，需进行一次 5 秒触控响应自检。若 5 秒内未手动点击确认，内核定时器将自动释放独占并销毁虚拟设备，确保触控永不卡死。"
    }

    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (state.selfCheckPassed && nativeCapable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_check),
                        contentDescription = null,
                        tint = if (state.selfCheckPassed && nativeCapable) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "原生 TouchMux 5 秒触控自检",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (state.selfCheckPassed && nativeCapable) "状态：已通过并解锁硬件合流" else "带内核自动安全回滚保护",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.selfCheckPassed && nativeCapable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = callbacks.onStartSelfCheck,
                    enabled = selfCheckEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.selfCheckPassed && nativeCapable) "重新验证 5 秒自检" else "发起 5 秒触控自检",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = callbacks.onReleaseNative,
                    enabled = conn == ShizukuConnectionState.CONNECTED
                ) {
                    Text(text = "释放独占")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSchemesCard(
    state: MainUiState,
    activePreset: ClickPreset?,
    onSelectPreset: (String) -> Unit,
    onRequestCreatePreset: () -> Unit,
    onRequestRenamePreset: (ClickPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRequestAddTarget: () -> Unit,
    onRequestEditTarget: (ClickTarget) -> Unit,
    onToggleTargetEnabled: (targetId: Int, enabled: Boolean) -> Unit,
    onDeleteTarget: (targetId: Int) -> Unit
) {
    val activeTargets = activePreset?.targets.orEmpty()

    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_layers),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "连点预设方案",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "默认自动保存 · 悬浮窗点击编号按钮一键切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onRequestCreatePreset,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "新建", style = MaterialTheme.typography.labelLarge)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.presets.forEachIndexed { index, preset ->
                    val selected = preset.id == activePreset?.id
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPreset(preset.id) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        label = {
                            Text(
                                text = "${index + 1}. ${preset.name} (${preset.targets.size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (activePreset != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onRequestRenamePreset(activePreset) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "重命名", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = { onDeletePreset(activePreset.id) },
                            enabled = state.presets.size > 1,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "删除方案", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    FilledTonalButton(
                        onClick = onRequestAddTarget,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "添加连点位", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (activeTargets.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "当前方案暂无预设连点位，可点击右上角「添加连点位」输入坐标，或开启悬浮窗展开「∨ → +」直接在屏幕拖放锚点（均自动保存）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeTargets.forEachIndexed { idx, target ->
                        Surface(
                            onClick = { onRequestEditTarget(target) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
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
                                    color = if (target.enabled) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    androidx.compose.foundation.layout.Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (target.enabled) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "坐标 (${target.x.roundToInt()}, ${target.y.roundToInt()})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "间隔 ${target.intervalMs}ms · 按压 ${target.pressDurationMs}ms · ${if (target.microJitter) "微抖动开" else "精准"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Switch(
                                    checked = target.enabled,
                                    onCheckedChange = { checked ->
                                        onToggleTargetEnabled(target.id, checked)
                                    }
                                )

                                IconButton(
                                    onClick = { onDeleteTarget(target.id) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "删除点位",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSettingsCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onRequestExactEdit: (NumericEditKind) -> Unit
) {
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tune),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Column {
                    Text(
                        text = "全局连点与悬浮窗设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "改动即时自动生效并保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TimingSliderSection(
                label = "默认点击间隔",
                valueMs = state.intervalMs,
                sliderRange = 10f..300f,
                presets = listOf(15L, 30L, 80L, 150L),
                onValueChange = callbacks.onIntervalChange,
                onClickExact = { onRequestExactEdit(NumericEditKind.INTERVAL) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            TimingSliderSection(
                label = "按压时长",
                valueMs = state.pressDurationMs,
                sliderRange = 8f..200f,
                presets = listOf(15L, 25L, 50L, 100L),
                onValueChange = callbacks.onPressChange,
                onClickExact = { onRequestExactEdit(NumericEditKind.PRESS) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingSwitchRow(
                title = "随机防检测微抖动 (±4px)",
                subtitle = "对新添加的锚点默认启用落点随机偏移",
                checked = state.microJitter,
                onCheckedChange = callbacks.onJitterChange
            )

            SettingSwitchRow(
                title = "开始连点时自动锁定锚点",
                subtitle = "开启连点自动切为触摸穿透模式，避免误拖锚点",
                checked = state.autoLockOnStart,
                onCheckedChange = callbacks.onAutoLockChange
            )

            SettingSwitchRow(
                title = "悬浮控制条自动贴边收起",
                subtitle = "开始连点或闲置片刻后自动缩成迷你贴边胶囊，不挡视野",
                checked = state.autoCollapseBar,
                onCheckedChange = callbacks.onAutoCollapseChange
            )

            FilledTonalButton(
                onClick = callbacks.onApplyDefaultsToCurrentPreset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "将以上默认参数同步至当前方案锚点",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TimingSliderSection(
    label: String,
    valueMs: Long,
    sliderRange: ClosedFloatingPointRange<Float>,
    presets: List<Long>,
    onValueChange: (value: Long, commit: Boolean) -> Unit,
    onClickExact: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Surface(
                onClick = onClickExact,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    text = "$valueMs ms (点击输入)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Slider(
            value = valueMs.toFloat().coerceIn(sliderRange.start, sliderRange.endInclusive),
            onValueChange = { onValueChange(it.roundToLong(), false) },
            onValueChangeFinished = { onValueChange(valueMs, true) },
            valueRange = sliderRange
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = valueMs == preset,
                    onClick = { onValueChange(preset, true) },
                    label = {
                        Text(
                            text = "${preset}ms",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SelfCheckCountdownDialog(
    remainingMs: Long,
    totalMs: Long,
    onConfirm: () -> Unit,
    onRollback: () -> Unit
) {
    val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    val progress = (remainingMs.toFloat() / totalMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_shield_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(text = "5 秒触控合流自检") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "物理触摸屏已由 TouchMux 临时接管并合流至虚拟屏。\n请在 $sec 秒内用手指点击下方【确认触控正常】。",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "剩余 $sec 秒后将自动安全回滚至原厂触控",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = "确认触控正常")
            }
        },
        dismissButton = {
            TextButton(onClick = onRollback) {
                Text(text = "立即回滚")
            }
        }
    )
}

@Composable
private fun ExactMillisInputDialog(
    kind: NumericEditKind,
    currentValue: Long,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit
) {
    val minVal = if (kind == NumericEditKind.INTERVAL) ClickTarget.MIN_INTERVAL_MS else ClickTarget.MIN_PRESS_MS
    val maxVal = if (kind == NumericEditKind.INTERVAL) ClickTarget.MAX_INTERVAL_MS else ClickTarget.MAX_PRESS_MS
    val title = if (kind == NumericEditKind.INTERVAL) "设置默认点击间隔 (ms)" else "设置默认按压时长 (ms)"
    var inputText by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = inputText,
                onValueChange = { raw ->
                    inputText = raw.filter { it.isDigit() }.take(6)
                },
                singleLine = true,
                label = { Text(text = "范围 $minVal ~ $maxVal ms") },
                suffix = { Text(text = "ms") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                val parsed = inputText.toLongOrNull() ?: currentValue
                onApply(parsed.coerceIn(minVal, maxVal))
            }) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
private fun CreatePresetDialog(
    suggestedName: String,
    hasCurrentTargets: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, copyCurrent: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }
    var copyCurrent by remember { mutableStateOf(hasCurrentTargets) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新建预设方案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(18) },
                    singleLine = true,
                    label = { Text(text = "方案名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasCurrentTargets) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "复制当前方案的点位作为起点",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = copyCurrent,
                            onCheckedChange = { copyCurrent = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { suggestedName }, copyCurrent) }) {
                Text(text = "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
private fun RenamePresetDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "重命名方案") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(18) },
                singleLine = true,
                label = { Text(text = "方案名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
private fun PresetTargetEditDialog(
    draft: TargetEditDraft,
    screenWidthPx: Int,
    screenHeightPx: Int,
    onDismiss: () -> Unit,
    onSave: (
        x: Float,
        y: Float,
        intervalMs: Long,
        pressDurationMs: Long,
        microJitter: Boolean,
        enabled: Boolean
    ) -> Unit
) {
    var xText by remember(draft) { mutableStateOf(draft.x.roundToInt().toString()) }
    var yText by remember(draft) { mutableStateOf(draft.y.roundToInt().toString()) }
    var intervalText by remember(draft) { mutableStateOf(draft.intervalMs.toString()) }
    var pressText by remember(draft) { mutableStateOf(draft.pressDurationMs.toString()) }
    var jitter by remember(draft) { mutableStateOf(draft.microJitter) }
    var enabled by remember(draft) { mutableStateOf(draft.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (draft.existingId == null) "添加预设连点位" else "编辑连点位") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "屏幕参考分辨率：$screenWidthPx × $screenHeightPx px（也可开启悬浮窗直接拖动锚点并自动保存）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = xText,
                        onValueChange = { raw -> xText = raw.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text(text = "X 坐标") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = yText,
                        onValueChange = { raw -> yText = raw.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text(text = "Y 坐标") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { raw -> intervalText = raw.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text(text = "间隔 (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pressText,
                        onValueChange = { raw -> pressText = raw.filter { it.isDigit() }.take(4) },
                        singleLine = true,
                        label = { Text(text = "按压 (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "随机微抖动 (±4px)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = jitter, onCheckedChange = { jitter = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "启用该连点位", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedX = xText.toFloatOrNull() ?: draft.x
                val parsedY = yText.toFloatOrNull() ?: draft.y
                val parsedInterval = intervalText.toLongOrNull() ?: draft.intervalMs
                val parsedPress = pressText.toLongOrNull() ?: draft.pressDurationMs
                onSave(parsedX, parsedY, parsedInterval, parsedPress, jitter, enabled)
            }) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}
