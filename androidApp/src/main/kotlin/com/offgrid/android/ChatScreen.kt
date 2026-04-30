package com.offgrid.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.shared.knowledge.KnowledgePack

private val InkBlack = Color(0xFF111111)
private val SoftMuted = Color(0xFF888888)
private val FaintRule = Color(0xFFEAEAEA)
private val UserBubble = Color(0xFFF2F2F2)

private enum class AppPage { Chat, Knowledge }

@Composable
fun OffgridApp(viewModel: ChatViewModel) {
    var currentPage by rememberSaveable { mutableStateOf(AppPage.Chat) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Offgrid",
            color = InkBlack,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )
        TabBar(
            current = currentPage,
            onSelect = { currentPage = it }
        )
        when (currentPage) {
            AppPage.Chat -> ChatPanel(viewModel = viewModel)
            AppPage.Knowledge -> KnowledgePanel(viewModel = viewModel)
        }
    }
}

@Composable
private fun TabBar(current: AppPage, onSelect: (AppPage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TabLabel("Chat", current == AppPage.Chat) { onSelect(AppPage.Chat) }
        TabLabel("Knowledge", current == AppPage.Knowledge) { onSelect(AppPage.Knowledge) }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = text,
            color = if (selected) InkBlack else SoftMuted,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 28.dp else 0.dp)
                .background(InkBlack)
        )
    }
}

@Composable
private fun ChatPanel(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val packs by viewModel.installedPacks.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.messages.isEmpty()) {
            EmptyChatHint(packCount = packs.size)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                if (message.fromUser) {
                    UserMessageRow(text = message.text)
                } else {
                    AssistantMessageRow(text = message.text)
                }
            }
        }

        StatusRow(uiState = uiState)

        uiState.error?.let { error ->
            Text(
                text = error,
                color = Color(0xFFB00020),
                fontSize = 13.sp
            )
        }

        InputRow(
            input = input,
            onInputChange = { input = it },
            isLoading = uiState.isLoading,
            onSend = {
                if (input.isNotBlank()) {
                    viewModel.sendMessage(input)
                    input = ""
                }
            },
            onStop = { viewModel.stopGeneration() }
        )
    }
}

@Composable
private fun EmptyChatHint(packCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (packCount > 0) "Ready" else "Ready (no packs installed)",
            color = SoftMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Text(
            text = if (packCount > 0) {
                "$packCount pack(s) installed. Ask anything; answers will cite [Source: …] when grounded."
            } else {
                "Tip: install a knowledge pack from the Knowledge tab to ground answers in real content."
            },
            color = InkBlack,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun UserMessageRow(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                color = InkBlack,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun AssistantMessageRow(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "OFFGRID",
            color = SoftMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = text.ifBlank { "…" },
            color = InkBlack,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun StatusRow(uiState: com.offgrid.shared.models.ChatUiState) {
    if (uiState.isRetrieving) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Searching local knowledge…",
                color = SoftMuted,
                fontSize = 12.sp
            )
        }
    } else if (uiState.isLoading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.width(12.dp).height(12.dp),
                strokeWidth = 1.5.dp,
                color = SoftMuted
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Generating…",
                color = SoftMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun InputRow(
    input: String,
    onInputChange: (String) -> Unit,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FaintRule))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !isLoading,
                    cursorBrush = SolidColor(InkBlack),
                    textStyle = LocalTextStyle.current.copy(
                        color = InkBlack,
                        fontSize = 15.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
                if (input.isEmpty()) {
                    Text(
                        text = "Ask something…",
                        color = SoftMuted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
            PillButton(
                label = if (isLoading) "Stop" else "Send",
                onClick = if (isLoading) onStop else onSend
            )
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(InkBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun KnowledgePanel(viewModel: ChatViewModel) {
    val packs by viewModel.installedPacks.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshingPacks.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Knowledge Packs",
                    color = InkBlack,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (packs.isEmpty()) {
                        "Sideload a pack ZIP to get started."
                    } else {
                        "${packs.size} installed · used to ground chat answers."
                    },
                    color = SoftMuted,
                    fontSize = 13.sp
                )
            }
            PillButton(
                label = if (refreshing) "Scanning" else "Refresh",
                onClick = { if (!refreshing) viewModel.refreshPacks() }
            )
        }
        Spacer(Modifier.height(4.dp))
        if (packs.isEmpty()) {
            EmptyPacksHint()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(packs, key = { it.id }) { pack ->
                    PackRow(pack = pack)
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FaintRule))
                }
            }
        }
    }
}

@Composable
private fun EmptyPacksHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "How to install a pack",
            color = InkBlack,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "1. Build a pack ZIP with the Knowledge Factory:\n" +
                "   cd backend/factory && npm run build <topic-id>",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Text(
            text = "2. Push it onto the device:\n" +
                "   adb push out/<topic>.zip /sdcard/Android/data/com.offgrid.android/files/packs/",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Text(
            text = "3. Tap Refresh. The app auto-imports the pack and chat answers will cite [Source: …] from it.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun PackRow(pack: KnowledgePack) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
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
                Text(
                    text = pack.title,
                    color = InkBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (pack.description.isNotBlank()) {
                    Text(
                        text = pack.description,
                        color = SoftMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = pack.version,
                color = SoftMuted,
                fontSize = 11.sp
            )
        }
        Text(
            text = "${pack.numChunks} chunks · ${pack.numSources} sources · ${formatSize(pack.sizeBytes)}",
            color = SoftMuted,
            fontSize = 12.sp
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KiB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MiB".format(mb)
}
