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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.shared.knowledge.KnowledgePack
import com.offgrid.shared.models.ModelBootstrapUiState

private val InkBlack = Color(0xFF111111)
private val SoftMuted = Color(0xFF888888)
private val FaintRule = Color(0xFFEAEAEA)
private val UserBubble = Color(0xFFF2F2F2)

private enum class AppPage { Chat, Knowledge }
private enum class KnowledgeSubTab { Catalog, Installed }

@Composable
fun OffgridApp(viewModel: ChatViewModel) {
    var currentPage by rememberSaveable { mutableStateOf(AppPage.Chat) }
    val modelUi by viewModel.modelBootstrapUi.collectAsStateWithLifecycle()
    val chatReady = modelUi is ModelBootstrapUiState.Ready

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp)
        ) {
            ModelBootstrapBanner(
                state = modelUi,
                onRetry = { viewModel.retryModelBootstrap() }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "Offgrid",
                    modifier = Modifier.align(Alignment.Center),
                    color = InkBlack,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            when (currentPage) {
                AppPage.Chat -> ChatPanel(
                    viewModel = viewModel,
                    chatEnabled = chatReady,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                AppPage.Knowledge -> KnowledgePanel(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = Color.White,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentPage == AppPage.Chat,
                    onClick = { currentPage = AppPage.Chat },
                    icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                    label = { Text("Chat") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = InkBlack,
                        selectedTextColor = InkBlack,
                        unselectedIconColor = SoftMuted,
                        unselectedTextColor = SoftMuted
                    )
                )
                NavigationBarItem(
                    selected = currentPage == AppPage.Knowledge,
                    onClick = { currentPage = AppPage.Knowledge },
                    icon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                    label = { Text("Knowledge") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = InkBlack,
                        selectedTextColor = InkBlack,
                        unselectedIconColor = SoftMuted,
                        unselectedTextColor = SoftMuted
                    )
                )
            }
        }

        // Block only Chat with overlay; Knowledge (pack download/install) must work
        // while model files download in background.
        val blockChatForModel =
            currentPage == AppPage.Chat &&
                (modelUi is ModelBootstrapUiState.Checking ||
                    modelUi is ModelBootstrapUiState.Downloading)
        if (blockChatForModel) {
            ModelBootstrapFullscreenOverlay(
                state = modelUi,
                onRetry = { viewModel.retryModelBootstrap() }
            )
        }
    }
}

@Composable
private fun ModelBootstrapBanner(
    state: ModelBootstrapUiState,
    onRetry: () -> Unit
) {
    if (state !is ModelBootstrapUiState.Failed) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Text(
            text = state.message,
            color = Color(0xFFB00020),
            fontSize = 13.sp
        )
        TextButton(onClick = onRetry) {
            Text("Retry model download", color = InkBlack)
        }
        Text(
            text = "Knowledge tab: packs still install. Chat needs the LLM.",
            color = SoftMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ModelBootstrapFullscreenOverlay(
    state: ModelBootstrapUiState,
    onRetry: () -> Unit
) {
    when (state) {
        ModelBootstrapUiState.Ready,
        is ModelBootstrapUiState.Failed -> return

        ModelBootstrapUiState.Checking -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = InkBlack)
                    Text("Preparing model…", color = SoftMuted, fontSize = 14.sp)
                }
            }
        }

        is ModelBootstrapUiState.Downloading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.label, color = InkBlack, fontSize = 15.sp)
                    if (state.bytesTotal > 0L) {
                        val p = (state.bytesReceived.toFloat() / state.bytesTotal.toFloat())
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${state.bytesReceived / 1024} KiB / ${state.bytesTotal / 1024} KiB",
                            color = SoftMuted,
                            fontSize = 12.sp
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Downloading…", color = SoftMuted, fontSize = 12.sp)
                    }
                }
            }
        }
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
private fun ChatPanel(
    viewModel: ChatViewModel,
    chatEnabled: Boolean,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
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
            chatEnabled = chatEnabled,
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
            text = assistantTextToAnnotated(text.ifBlank { "…" }),
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
    chatEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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
                    enabled = chatEnabled && !isLoading,
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
                onClick = if (isLoading) onStop else onSend,
                enabled = chatEnabled || isLoading
            )
        }
    }
}

private fun assistantTextToAnnotated(raw: String): AnnotatedString {
    val text = raw.replace(Regex("```[\\s\\S]*?```"), "").trim()
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val lineStart = i == 0 || text[i - 1] == '\n'
            if (lineStart && text.startsWith("### ", i)) {
                val end = text.indexOf('\n', i)
                val endIdx = if (end < 0) text.length else end
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                    append(text.substring(i + 4, endIdx).trim())
                }
                if (end >= 0) append('\n')
                i = if (end < 0) text.length else end + 1
                continue
            }
            if (lineStart && (text.startsWith("- ", i) || text.startsWith("* ", i))) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("• ") }
                i += 2
                continue
            }
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) InkBlack else Color(0xFFCCCCCC))
            .clickable(enabled = enabled, onClick = onClick)
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
private fun KnowledgePanel(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val packs by viewModel.installedPacks.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshingPacks.collectAsStateWithLifecycle()
    val catalog by viewModel.availablePacks.collectAsStateWithLifecycle()
    val refreshingCatalog by viewModel.isRefreshingCatalog.collectAsStateWithLifecycle()
    val installingIds by viewModel.installingPackIds.collectAsStateWithLifecycle()
    val deletingIds by viewModel.deletingPackIds.collectAsStateWithLifecycle()
    var subTab by rememberSaveable { mutableStateOf(KnowledgeSubTab.Catalog) }
    var search by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshCatalog()
        viewModel.refreshPacks()
    }
    val filteredCatalog = catalog.filter { pack ->
        val q = search.trim().lowercase()
        if (q.isEmpty()) true else {
            "${pack.title} ${pack.description} ${pack.tags.joinToString(" ")} ${pack.id}"
                .lowercase()
                .contains(q)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Knowledge Packs",
            color = InkBlack,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (refreshingCatalog || refreshing) {
            Text(
                text = "Syncing catalog and installed packs…",
                color = SoftMuted,
                fontSize = 12.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TabLabel(
                text = "Catalog",
                selected = subTab == KnowledgeSubTab.Catalog,
                onClick = { subTab = KnowledgeSubTab.Catalog }
            )
            TabLabel(
                text = "Installed",
                selected = subTab == KnowledgeSubTab.Installed,
                onClick = { subTab = KnowledgeSubTab.Installed }
            )
        }
        Spacer(Modifier.height(4.dp))
        when (subTab) {
            KnowledgeSubTab.Catalog -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8F8F8))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        textStyle = LocalTextStyle.current.copy(color = InkBlack, fontSize = 14.sp),
                        cursorBrush = SolidColor(InkBlack),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (search.isBlank()) {
                        Text("Search topics...", color = SoftMuted, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (filteredCatalog.isEmpty()) {
                    Text(
                        text = if (catalog.isEmpty()) {
                            if (refreshingCatalog) "Loading catalog…" else "No catalog items (check error message above or network)."
                        } else {
                            "No results for \"$search\"."
                        },
                        color = SoftMuted,
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(filteredCatalog, key = { it.id }) { pack ->
                            val installed = packs.any { it.id == pack.id }
                            AvailablePackRow(
                                pack = pack,
                                installed = installed,
                                installing = installingIds.contains(pack.id),
                                onInstall = { viewModel.installPack(pack.id) }
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FaintRule))
                        }
                    }
                }
            }
            KnowledgeSubTab.Installed -> {
                if (packs.isEmpty()) {
                    Text(
                        text = "No installed modules yet.",
                        color = SoftMuted,
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(packs, key = { it.id }) { pack ->
                            PackRow(
                                pack = pack,
                                deleting = deletingIds.contains(pack.id),
                                onDelete = { viewModel.deletePack(pack.id) }
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FaintRule))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailablePackRow(
    pack: RemotePack,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = pack.title,
                color = InkBlack,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${pack.tags.joinToString(" · ")} · ${formatSize(pack.sizeBytes)}",
                color = SoftMuted,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        installed -> Color(0xFFEFEFEF)
                        installing -> Color(0xFFDCDCDC)
                        else -> Color(0xFF111111)
                    }
                )
                .clickable(enabled = !installed && !installing, onClick = onInstall)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = when {
                    installed -> "Installed"
                    installing -> "Installing..."
                    else -> "Install"
                },
                color = if (installed || installing) SoftMuted else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PackRow(
    pack: KnowledgePack,
    deleting: Boolean,
    onDelete: () -> Unit
) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (deleting) Color(0xFFE8E8E8) else Color(0xFFF2F2F2))
                    .clickable(enabled = !deleting, onClick = onDelete)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (deleting) "Deleting..." else "Delete",
                    color = if (deleting) SoftMuted else InkBlack,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KiB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MiB".format(mb)
}
