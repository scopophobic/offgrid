package com.offgrid.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.shared.knowledge.KnowledgePack
import com.offgrid.shared.models.ModelBootstrapUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val InkBlack @Composable get() = MaterialTheme.colorScheme.onSurface
private val SoftMuted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val FaintRule @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val UserBubble @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Paper @Composable get() = MaterialTheme.colorScheme.background
private val Forest @Composable get() = MaterialTheme.colorScheme.primary

private enum class AppPage { Chat, Knowledge, Library, Tools, Settings }
private enum class KnowledgeSubTab { Catalog, Installed }

@Composable
fun OffgridApp(viewModel: ChatViewModel) {
    var currentPage by rememberSaveable { mutableStateOf(AppPage.Chat) }
    val modelUi by viewModel.modelBootstrapUi.collectAsStateWithLifecycle()
    val chatReady = modelUi is ModelBootstrapUiState.Ready
    val sharedDraft by viewModel.draft.collectAsStateWithLifecycle()
    val sharedContent by viewModel.sharedContent.collectAsStateWithLifecycle()
    LaunchedEffect(sharedDraft) { if (sharedDraft.isNotBlank()) currentPage = AppPage.Chat }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var discover by rememberSaveable { mutableStateOf(false) }
    val pageState = rememberSaveableStateHolder()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            OffgridDrawerContent(
                viewModel = viewModel,
                currentPage = currentPage,
                onSelectPage = { page ->
                    currentPage = page
                    scope.launch { drawerState.close() }
                },
                onSelectChat = { chatId ->
                    viewModel.openChat(chatId)
                    currentPage = AppPage.Chat
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.newChat()
                    currentPage = AppPage.Chat
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            // Modern, minimalist top navigation bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Open sidebar",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (currentPage != AppPage.Chat) currentPage = AppPage.Chat
                        }
                ) {
                    Icon(painterResource(R.drawable.offgrid_mark), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (currentPage) {
                            AppPage.Chat -> "offgrid"
                            AppPage.Library, AppPage.Knowledge -> "library"
                            AppPage.Tools -> "tools"
                            AppPage.Settings -> "settings"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.newChat()
                        currentPage = AppPage.Chat
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            NoticeBar(viewModel)
            ModelBootstrapBanner(modelUi, viewModel::retryModelBootstrap)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                pageState.SaveableStateProvider(currentPage.name) {
                    when (currentPage) {
                        AppPage.Chat -> when (val state = modelUi) {
                            is ModelBootstrapUiState.NeedsSelection -> {
                                val freeBytes by viewModel.freeStorageBytes.collectAsStateWithLifecycle()
                                ModelPickerOverlay(state.available, freeBytes, { viewModel.selectModel(it) })
                            }
                            is ModelBootstrapUiState.Checking, is ModelBootstrapUiState.Downloading ->
                                ModelBootstrapFullscreenOverlay(state, viewModel::retryModelBootstrap)
                            else -> ChatPanel(viewModel, chatReady, Modifier.fillMaxSize())
                        }
                        AppPage.Library, AppPage.Knowledge -> Column(Modifier.fillMaxSize()) {
                            ActionStrip {
                                FilterChip(!discover, { discover = false }, label = { Text("My things") })
                                FilterChip(discover, { discover = true }, label = { Text("Discover packs") })
                            }
                            if (discover) KnowledgePanel(viewModel, Modifier.weight(1f))
                            else LibraryPanel(viewModel, { currentPage = AppPage.Chat }, Modifier.weight(1f))
                        }
                        AppPage.Tools -> ToolsPanel(viewModel, { currentPage = AppPage.Chat }, Modifier.fillMaxSize())
                        AppPage.Settings -> SettingsWithPreferences(viewModel, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    Box {
        sharedContent?.let { content ->
            val url = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(content)?.value
            val allowed by viewModel.webAllowed.collectAsStateWithLifecycle()
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.sharedContent.value = null },
                title = { Text(if (url == null) "Make something useful" else "Keep this page for the road") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(content.take(220), color = SoftMuted)
                        if (url != null) {
                            Text("Read the page with internet, then save it to your offline library.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Allow web for this session", Modifier.weight(1f))
                                androidx.compose.material3.Switch(allowed, viewModel::setWebAllowed)
                            }
                            androidx.compose.material3.TextButton(enabled = allowed, onClick = {
                                viewModel.readWebPage(url)
                                viewModel.sharedContent.value = null
                                currentPage = AppPage.Tools
                            }) { Text("Read page") }
                        }
                        ActionStrip {
                            listOf(TaskAction.ASK, TaskAction.SUMMARIZE, TaskAction.EXPLAIN, TaskAction.CHECKLIST).forEach { task ->
                                androidx.compose.material3.TextButton(onClick = {
                                    viewModel.selectedTask.value = task
                                    viewModel.draft.value = content
                                    viewModel.sharedContent.value = null
                                    currentPage = AppPage.Chat
                                }) { Text(task.label) }
                            }
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.sharedContent.value = null }) { Text("Close") } }
            )
        }
    }
}

@Composable
private fun OffgridDrawerContent(
    viewModel: ChatViewModel,
    currentPage: AppPage,
    onSelectPage: (AppPage) -> Unit,
    onSelectChat: (String) -> Unit,
    onNewChat: () -> Unit
) {
    val chats by viewModel.savedChats.collectAsStateWithLifecycle()
    val activeChatId = viewModel.uiState.collectAsStateWithLifecycle().value.conversationId
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SavedChat?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SavedChat?>(null) }

    LaunchedEffect(searchQuery) {
        delay(250)
        viewModel.refreshPersonal(searchQuery)
    }

    ModalDrawerSheet(
        modifier = Modifier.width(310.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp)
        ) {
            // Drawer Top: Branding & New Chat button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.offgrid_mark), contentDescription = null, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "offgrid",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "New",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Search chats field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chats…", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = SoftMuted)
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )

            // Section Header: Saved Chats
            Text(
                "SAVED CHATS",
                style = MaterialTheme.typography.labelSmall,
                color = SoftMuted,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
            )

            // Saved Chats List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (chats.isEmpty()) {
                    item {
                        Text(
                            text = if (searchQuery.isBlank()) "No saved chats yet" else "No matches found",
                            fontSize = 13.sp,
                            color = SoftMuted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(chats, key = { it.id }) { chat ->
                        val isSelected = currentPage == AppPage.Chat && chat.id == activeChatId
                        DrawerChatRow(
                            chat = chat,
                            isSelected = isSelected,
                            onClick = { onSelectChat(chat.id) },
                            onRename = {
                                renameTarget = chat
                                renameTitle = chat.title
                            },
                            onDelete = { deleteTarget = chat }
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = FaintRule
            )

            // Navigation destinations: Ask, Library, Tools, Settings
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                NavigationDrawerItem(
                    label = { Text("Ask", fontWeight = if (currentPage == AppPage.Chat) FontWeight.SemiBold else FontWeight.Normal) },
                    selected = currentPage == AppPage.Chat,
                    onClick = { onSelectPage(AppPage.Chat) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(44.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Library", fontWeight = if (currentPage == AppPage.Library || currentPage == AppPage.Knowledge) FontWeight.SemiBold else FontWeight.Normal) },
                    selected = currentPage == AppPage.Library || currentPage == AppPage.Knowledge,
                    onClick = { onSelectPage(AppPage.Library) },
                    icon = { Icon(Icons.Default.LibraryBooks, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(44.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Tools", fontWeight = if (currentPage == AppPage.Tools) FontWeight.SemiBold else FontWeight.Normal) },
                    selected = currentPage == AppPage.Tools,
                    onClick = { onSelectPage(AppPage.Tools) },
                    icon = { Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(44.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Settings", fontWeight = if (currentPage == AppPage.Settings) FontWeight.SemiBold else FontWeight.Normal) },
                    selected = currentPage == AppPage.Settings,
                    onClick = { onSelectPage(AppPage.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(44.dp)
                )
            }
        }
    }

    renameTarget?.let { c ->
        EditDialog(
            label = "Rename chat",
            value = renameTitle,
            change = { renameTitle = it },
            dismiss = { renameTarget = null }
        ) {
            viewModel.renameChat(c.id, renameTitle)
            renameTarget = null
        }
    }

    deleteTarget?.let { c ->
        ConfirmDelete(
            title = c.title,
            dismiss = { deleteTarget = null }
        ) {
            viewModel.deleteChat(c.id)
            deleteTarget = null
        }
    }
}

@Composable
private fun DrawerChatRow(
    chat: SavedChat,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else SoftMuted
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = chat.title,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = InkBlack,
                modifier = Modifier.weight(1f)
            )

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Chat options",
                        modifier = Modifier.size(16.dp),
                        tint = SoftMuted
                    )
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
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
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp
        )
        TextButton(onClick = onRetry) {
            Text("Retry model download", color = InkBlack)
        }
        Text(
            text = "Library and Tools are ready to use. Choose a model in Settings to start chatting.",
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
        is ModelBootstrapUiState.Failed,
        is ModelBootstrapUiState.NeedsSelection -> return

        ModelBootstrapUiState.Checking -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
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
                    .background(MaterialTheme.colorScheme.background),
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
    var input by rememberSaveable(uiState.conversationId) { mutableStateOf("") }
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val selected by viewModel.selectedItem.collectAsStateWithLifecycle()
    val collection by viewModel.selectedCollection.collectAsStateWithLifecycle()
    val library by viewModel.libraryItems.collectAsStateWithLifecycle()
    val task by viewModel.selectedTask.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val voice = remember { OfflineVoice(context.applicationContext) { viewModel.notice.value = it } }
    DisposableEffect(voice) { onDispose { voice.close() } }
    var history by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draft) { if(draft.isNotBlank()) { input = draft; viewModel.draft.value = "" } }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.messages.isNotEmpty()) ActionStrip {
            androidx.compose.material3.TextButton(enabled = !uiState.isLoading, onClick = { viewModel.newChat() }) { Text("New chat") }
            androidx.compose.material3.TextButton(enabled = !uiState.isLoading && uiState.messages.isNotEmpty(), onClick = viewModel::retryLast) { Text("Retry last") }
        }

        Text(if(chatEnabled) "●  Ready offline · just on this device" else "Choose or download a model to chat", fontSize = 12.sp)
        if (selected != null || collection.isNotBlank()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(start=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Using ${selected?.title ?: collection}",Modifier.weight(1f),maxLines=2)
                TextButton(onClick={viewModel.selectedItem.value=null;viewModel.selectedCollection.value=""}) { Text("Clear") }
            }
        }
        if (options) ActionStrip {
            androidx.compose.material3.FilterChip(collection.isBlank(), { viewModel.selectedCollection.value = "" }, label = { Text("All knowledge") })
            library.map { it.collection }.distinct().forEach { name ->
                androidx.compose.material3.FilterChip(collection == name, { viewModel.selectedCollection.value = name }, label = { Text(name) })
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if(uiState.messages.isEmpty()) item {
                EmptyChatHint()
                Surface(onClick={ input="Help me make sense of " },color=MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("A GOOD PLACE TO START",style=MaterialTheme.typography.labelSmall)
                        Text("Make this\nmake sense ↗",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(vertical=12.dp))
                        Text("Bring a question, a note, or a tangled thought.")
                    }
                }
                TextButton(onClick={input="Explain this simply: ";viewModel.selectedTask.value=TaskAction.EXPLAIN}) { Text("Explain something simply ↗") }
            }
            items(uiState.messages, key = { it.id }) { message ->
                if (message.fromUser) {
                    UserMessageRow(text = message.text)
                } else {
                    AssistantMessageRow(text = message.text)
                    AnswerActions(viewModel, message, !uiState.isLoading, voice)
                }
            }
        }

        StatusRow(uiState = uiState)
        TextButton(onClick={options=!options}) { Text(if(options) "− Fewer options" else "+ Context & writing tools · ${task.label}") }
        if(options) ActionStrip { TaskAction.entries.filter { it != TaskAction.SHORTEN && it != TaskAction.SIMPLIFY }.forEach { action ->
            androidx.compose.material3.FilterChip(selected = task == action, enabled = !uiState.isLoading, onClick = { viewModel.selectedTask.value = action }, label = { Text(action.label) })
        } }
        if(options) VoiceControls(viewModel, voice, onText = { input = (input + " " + it).trim() })

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
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
                    viewModel.sendMessage(input, task)
                    viewModel.selectedTask.value = TaskAction.ASK
                    input = ""
                }
            },
            onStop = { viewModel.stopGeneration() }
        )
    }
    if(history) ChatHistoryDialog(viewModel) { history = false }
}

@Composable
private fun EmptyChatHint() {
    PocketHeading("Room for\na little curiosity.", "Big question. Small wonder. Start anywhere.")
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
        SelectionContainer { Text(
            text = assistantTextToAnnotated(text.ifBlank { "…" }),
            color = InkBlack,
            fontSize = 15.sp,
            lineHeight = 25.sp
        ) }
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
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal=16.dp,vertical=8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {

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
                    maxLines = 5,
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
                enabled = (chatEnabled && input.isNotBlank()) || isLoading
            )
        }
    }
}

private fun assistantTextToAnnotated(raw: String): AnnotatedString {
    val text = raw.trim()
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
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min=48.dp).padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
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
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        textStyle = LocalTextStyle.current.copy(color = InkBlack, fontSize = 14.sp),
                        maxLines = 5,
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
                        installed -> MaterialTheme.colorScheme.surfaceVariant
                        installing -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primary
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
                color = if (installed || installing) SoftMuted else MaterialTheme.colorScheme.onPrimary,
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
                    .background(if (deleting) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant)
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
