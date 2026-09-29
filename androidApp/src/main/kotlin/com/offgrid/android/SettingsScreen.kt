package com.offgrid.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.shared.models.ModelInfo

private val InkBlack @Composable get() = MaterialTheme.colorScheme.onSurface
private val SoftMuted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val FaintRule @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val DangerRed @Composable get() = MaterialTheme.colorScheme.error

@Composable
fun SettingsPanel(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val models by viewModel.availableModels.collectAsStateWithLifecycle()
    val activeId by viewModel.activeModelId.collectAsStateWithLifecycle()
    val freeBytes by viewModel.freeStorageBytes.collectAsStateWithLifecycle()
    val deletingIds by viewModel.deletingModelIds.collectAsStateWithLifecycle()
    val modelUi by viewModel.modelBootstrapUi.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Settings",
            color = InkBlack,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        StorageSummary(freeBytes = freeBytes)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Models",
                color = InkBlack,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextChip(
                label = "Refresh",
                onClick = { viewModel.refreshAvailableModels() }
            )
        }

        if (modelUi is com.offgrid.shared.models.ModelBootstrapUiState.Downloading) {
            val d = modelUi as com.offgrid.shared.models.ModelBootstrapUiState.Downloading
            val pct = if (d.bytesTotal > 0) (d.bytesReceived * 100 / d.bytesTotal).toInt() else 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Downloading model: $pct%",
                    color = InkBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else if (modelUi is com.offgrid.shared.models.ModelBootstrapUiState.Checking) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Preparing model...",
                    color = InkBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (models.isEmpty()) {
            Text(
                text = "No models available yet. Connect to the internet and tap Refresh to try again.",
                color = SoftMuted,
                fontSize = 13.sp
            )
        } else {
            val isDownloading = modelUi is com.offgrid.shared.models.ModelBootstrapUiState.Downloading || modelUi is com.offgrid.shared.models.ModelBootstrapUiState.Checking
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(models, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        deleting = deletingIds.contains(model.id),
                        canDelete = model.id != activeId && model.isDownloaded && !isDownloading,
                        isGlobalDownloading = isDownloading,
                        onSelect = { viewModel.selectModel(model.id) },
                        onDelete = { viewModel.deleteModel(model.id) }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(FaintRule)
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageSummary(freeBytes: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Free storage on this device",
                color = SoftMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Text(
                text = formatBytes(freeBytes),
                color = InkBlack,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    deleting: Boolean,
    canDelete: Boolean,
    isGlobalDownloading: Boolean = false,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        color = InkBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (model.isActive) {
                        Spacer(Modifier.width(8.dp))
                        ActiveBadge()
                    }
                }
                if (model.description.isNotBlank()) {
                    Text(
                        text = model.description,
                        color = SoftMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Text(
            text = buildModelMeta(model),
            color = SoftMuted,
            fontSize = 12.sp
        )

        if (model.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                model.tags.forEach { tag -> TagChip(tag) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            when {
                model.isActive -> TextChip(
                    label = "In use",
                    enabled = false
                )
                model.isDownloaded -> TextChip(
                    label = "Use this model",
                    enabled = !isGlobalDownloading,
                    onClick = onSelect
                )
                else -> TextChip(
                    label = "Download & use",
                    enabled = !isGlobalDownloading,
                    onClick = onSelect
                )
            }
            Spacer(Modifier.width(8.dp))
            TextChip(
                label = if (deleting) "Deleting…" else "Delete",
                onClick = onDelete,
                enabled = canDelete && !deleting,
                danger = canDelete && !deleting
            )
        }
    }
}

@Composable
private fun ActiveBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("Active", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TagChip(tag: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(tag, color = InkBlack, fontSize = 11.sp)
    }
}

@Composable
private fun TextChip(
    label: String,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        danger -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> SoftMuted
        danger -> DangerRed
        else -> InkBlack
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun buildModelMeta(model: ModelInfo): String {
    val parts = mutableListOf<String>()
    if (model.sizeBytes > 0) parts += formatBytes(model.sizeBytes)
    if (model.isDownloaded && model.onDeviceBytes > 0) {
        parts += "${formatBytes(model.onDeviceBytes)} on device"
    }
    model.recommendedRamMb?.let { parts += "RAM ${it} MB+" }
    if (model.isDownloaded && !model.isActive) parts += "downloaded"
    if (!model.isDownloaded) parts += "not downloaded"
    return parts.joinToString(" · ")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KiB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MiB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GiB".format(gb)
}

/**
 * First-run / no-active-model overlay. Shown when bootstrap state is
 * [com.offgrid.shared.models.ModelBootstrapUiState.NeedsSelection].
 */
@Composable
fun ModelPickerOverlay(
    available: List<ModelInfo>,
    freeBytes: Long,
    onPick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "A little brain. All yours.",
                color = InkBlack,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Offgrid runs your model fully on-device. Pick one to download.",
                color = SoftMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            StorageSummary(freeBytes = freeBytes)
            Spacer(Modifier.height(2.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(available, key = { it.id }) { model ->
                    PickerRow(model = model, onPick = { onPick(model.id) })
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(FaintRule)
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(model: ModelInfo, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = model.displayName,
            color = InkBlack,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (model.description.isNotBlank()) {
            Text(
                text = model.description,
                color = SoftMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        Text(
            text = buildModelMeta(model),
            color = SoftMuted,
            fontSize = 12.sp
        )
        if (model.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                model.tags.forEach { tag -> TagChip(tag) }
            }
        }
    }
}
