package com.offgrid.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.shared.models.ChatMessage
import com.offgrid.shared.models.AnswerSource
import java.text.DateFormat
import java.util.Date

@Composable
fun ActionStrip(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
fun NoticeBar(vm: ChatViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.toolBusy.collectAsStateWithLifecycle()
    if(busy) Row { Text("Working…", Modifier.weight(1f)); TextButton(onClick = vm::cancelTool) { Text("Cancel") } }
    notice?.let { text ->
        Row { Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { vm.notice.value = null }) { Text("Dismiss") } }
    }
}

@Composable
fun AnswerActions(vm: ChatViewModel, message: ChatMessage, enabled: Boolean, voice: OfflineVoice) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var source by remember { mutableStateOf<AnswerSource?>(null) }
    if(message.interrupted) Text("Response interrupted", style = MaterialTheme.typography.labelSmall)
    if(message.text.isNotBlank()) ActionStrip {
        TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text("Copy") }
        TextButton(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message.text) }, "Share answer"))
        }) { Text("Share") }
        TextButton(onClick = { vm.saveNote("Saved answer", message.text, "Answers") }) { Text("Save") }
        TextButton(onClick = { voice.speak(message.text) }) { Text("Listen") }
        TextButton(onClick = { voice.stop() }) { Text("Stop audio") }
        TextButton(enabled = enabled, onClick = { vm.taskPrompt("Make this shorter", message.text) }) { Text("Shorter") }
        TextButton(enabled = enabled, onClick = { vm.taskPrompt("Explain this simply", message.text) }) { Text("Simpler") }
    }
    if(message.sources.isNotEmpty()) {
        Text("Retrieved evidence — tap to inspect", style = MaterialTheme.typography.labelSmall)
        val invalid = CitationAudit.invalidReferences(message.text, message.sources.size)
        if (invalid.isNotEmpty()) Text("Check citations ${invalid.joinToString { "[$it]" }}: no matching source was retrieved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        ActionStrip { message.sources.forEachIndexed { i, s -> TextButton(onClick = { source = s }) { Text("[${i+1}] ${s.title.take(35)}") } } }
    } else if(message.text.isNotBlank()) Text("Model knowledge · no sources retrieved", style = MaterialTheme.typography.labelSmall)
    source?.let { s -> SourceDialog(s, onDismiss = { source = null }, onSave = { vm.saveNote(s.title, s.passage, "Sources", s.location) }) }
}

@Composable
fun SourceDialog(source: AnswerSource, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(source.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(source.location)
            if(source.savedAt > 0) Text("Saved/read ${DateFormat.getDateTimeInstance().format(Date(source.savedAt))}", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            SelectionContainer { Text(source.passage) }
        }
    }, confirmButton = { TextButton(onClick = onSave) { Text("Save offline") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun ChatHistoryDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val chats by vm.savedChats.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<SavedChat?>(null) }
    var title by remember { mutableStateOf("") }
    var deletion by remember { mutableStateOf<SavedChat?>(null) }
    LaunchedEffect(query) { kotlinx.coroutines.delay(250); vm.refreshPersonal(query) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Saved chats") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search chats and messages") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if(chats.isEmpty()) item { Text("No saved chats found.", Modifier.padding(12.dp)) }
                items(chats, key = { it.id }) { chat ->
                    Column {
                        TextButton(onClick = { vm.openChat(chat.id); onDismiss() }) { Text(chat.title) }
                        ActionStrip {
                            TextButton(onClick = { rename = chat; title = chat.title }) { Text("Rename") }
                            TextButton(onClick = { deletion = chat }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    rename?.let { c -> EditDialog("Rename chat", title, { title = it }, { rename = null }) { vm.renameChat(c.id, title); rename = null } }
    deletion?.let { c -> ConfirmDelete(c.title, { deletion = null }) { vm.deleteChat(c.id); deletion = null } }
}

@Composable
fun EditDialog(label: String, value: String, change: (String) -> Unit, dismiss: () -> Unit, save: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(label) }, text = { OutlinedTextField(value, change) }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = save) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun ConfirmDelete(title: String, dismiss: () -> Unit, remove: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Delete $title?") }, text = { Text("This removes the saved copy from Offgrid.") }, confirmButton = { TextButton(onClick = remove) { Text("Delete") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun LibraryPanel(vm: ChatViewModel, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val items by vm.libraryItems.collectAsStateWithLifecycle()
    val packs by vm.personalPacks.collectAsStateWithLifecycle()
    val busy by vm.toolBusy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var collection by rememberSaveable { mutableStateOf("Personal") }
    var packName by rememberSaveable { mutableStateOf("") }
    var packDescription by rememberSaveable { mutableStateOf("") }
    var creatingPack by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf<LibraryItem?>(null) }
    var deletion by remember { mutableStateOf<LibraryItem?>(null) }
    var moving by remember { mutableStateOf<LibraryItem?>(null) }
    var moveCollection by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importDocument(it, collection) } }
    LaunchedEffect(query) { kotlinx.coroutines.delay(250); vm.refreshPersonal(query) }
    Column(modifier) {
        Text("FIELD LIBRARY", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color(0xFF194C3A))
        Text("Take knowledge with you.", style = MaterialTheme.typography.headlineMedium)
        Text("Build a personal pack for a trip, city, or project. Everything in it stays on your device.", style = MaterialTheme.typography.bodyMedium)
        ActionStrip { TextButton(onClick = { creatingPack = true }) { Text("+ Create a pack") } }
        if (packs.isNotEmpty()) {
            Text("YOUR PACKS", style = MaterialTheme.typography.labelLarge)
            ActionStrip { packs.forEach { pack ->
                ElevatedCard(onClick = { collection = pack.name; vm.selectedCollection.value = pack.name }, colors = CardDefaults.elevatedCardColors(containerColor = if(collection == pack.name) androidx.compose.ui.graphics.Color(0xFFDAEBDD) else androidx.compose.ui.graphics.Color.White)) {
                    Column(Modifier.widthIn(min = 150.dp).padding(16.dp)) {
                        Text("FIELD PACK / ${pack.itemCount} ITEMS", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFF194C3A))
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        if(pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search library or collection") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(collection, { collection = it }, label = { Text("Collection for new items") }, modifier = Modifier.fillMaxWidth())
        ActionStrip {
            TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("text/plain", "text/markdown", "application/pdf")) }) { Text("Import file") }
            TextButton(onClick = { adding = !adding }) { Text("Write / paste note") }
        }
        if(adding) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
            TextButton(enabled = !busy && text.isNotBlank(), onClick = { vm.saveNote(title, text, collection); adding = false }) { Text("Save note") }
        }
        LazyColumn(Modifier.weight(1f)) {
            if(items.isEmpty()) item { Text("Save your first note or import a document. It stays available without internet.", Modifier.padding(16.dp)) }
            items(items, key = { it.id }) { item ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text("${item.collection} · ${item.text.length} characters", style = MaterialTheme.typography.bodySmall)
                    ActionStrip {
                        TextButton(onClick = { opened = item }) { Text("Read") }
                        TextButton(onClick = { vm.useSource(item); onAsk() }) { Text("Ask") }
                        TextButton(onClick = { moving = item; moveCollection = item.collection }) { Text("Collection") }
                        TextButton(onClick = { deletion = item }) { Text("Delete") }
                    }
                }
            }
        }
    }
    opened?.let { item -> SourceDialog(AnswerSource(item.title, item.text, item.location, item.savedAt), { opened = null }, { vm.notice.value = "Already saved offline." }) }
    deletion?.let { item -> ConfirmDelete(item.title, { deletion = null }) { vm.deleteItem(item.id); deletion = null } }
    moving?.let { item -> EditDialog("Move to collection", moveCollection, { moveCollection = it }, { moving = null }) { vm.moveItem(item.id, moveCollection); moving = null } }
    if (creatingPack) AlertDialog(onDismissRequest = { creatingPack = false }, title = { Text("Create a field pack") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start with a place or topic. Add notes, documents, and saved pages from this library.")
            OutlinedTextField(packName, { packName = it }, label = { Text("Pack name · e.g. Kyoto weekend") })
            OutlinedTextField(packDescription, { packDescription = it }, label = { Text("What is this pack for?") })
        }
    }, confirmButton = { TextButton(enabled = packName.isNotBlank(), onClick = {
        vm.createPersonalPack(packName, packDescription); collection = packName.trim(); creatingPack = false; packName = ""; packDescription = ""
    }) { Text("Create pack") } }, dismissButton = { TextButton(onClick = { creatingPack = false }) { Text("Cancel") } })
}

@Composable
fun ToolsPanel(vm: ChatViewModel, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf("Calculate") }
    Column(modifier) {
        ActionStrip { listOf("Calculate", "Convert", "Dates", "Web").forEach { label -> FilterChip(selected = section == label, onClick = { section = label }, label = { Text(label) }) } }
        when(section) {
            "Web" -> WebPanel(vm, onAsk)
            else -> UtilityPanel(section)
        }
    }
}

@Composable
private fun UtilityPanel(section: String) {
    var first by rememberSaveable(section) { mutableStateOf("") }
    var second by rememberSaveable(section) { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("km") }
    var to by rememberSaveable { mutableStateOf("mi") }
    var result by rememberSaveable(section) { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Works offline · calculated by code", style = MaterialTheme.typography.labelMedium)
        Text(when(section) { "Calculate" -> "Try (120 + 80) * 15% — % means divide by 100."; "Dates" -> "Use YYYY-MM-DD. Result excludes the starting day."; else -> "Length, weight, volume, and temperature." })
        OutlinedTextField(first, { first = it }, label = { Text(when(section) { "Dates" -> "Start date"; "Convert" -> "Value"; else -> "Expression" }) }, modifier = Modifier.fillMaxWidth())
        if(section == "Dates") OutlinedTextField(second, { second = it }, label = { Text("End date") }, modifier = Modifier.fillMaxWidth())
        if(section == "Convert") {
            Text("From"); ActionStrip { LocalTools.units.forEach { unit -> FilterChip(from == unit, { from = unit }, label = { Text(unit) }) } }
            Text("To"); ActionStrip { LocalTools.units.forEach { unit -> FilterChip(to == unit, { to = unit }, label = { Text(unit) }) } }
        }
        Button(onClick = { result = runCatching { when(section) { "Dates" -> LocalTools.daysBetween(first, second); "Convert" -> LocalTools.convert(first, from, to); else -> LocalTools.calculate(first) } }.getOrElse { it.message ?: "Check your input." } }) { Text("Calculate") }
        SelectionContainer { Text(result, style = MaterialTheme.typography.titleLarge) }
        if(result.isNotBlank()) TextButton(onClick = { clipboard.setText(AnnotatedString(result)) }) { Text("Copy result") }
    }
}

@Composable
fun WebPanel(vm: ChatViewModel, onAsk: () -> Unit) {
    val allowed by vm.webAllowed.collectAsStateWithLifecycle()
    val results by vm.webResults.collectAsStateWithLifecycle()
    val page by vm.readPage.collectAsStateWithLifecycle()
    val busy by vm.toolBusy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var brave by rememberSaveable { mutableStateOf(false) }
    var inspect by remember { mutableStateOf<AnswerSource?>(null) }
    Column {
        Row { Text("Web allowed", Modifier.weight(1f)); Switch(allowed, vm::setWebAllowed) }
        Text("Search sends only this query to the selected provider. Reading contacts that website. Answers run on your phone.", style = MaterialTheme.typography.bodySmall)
        ActionStrip {
            FilterChip(!brave, { brave = false }, label = { Text("Wikipedia") })
            FilterChip(brave, { brave = true }, label = { Text("Web · Brave") })
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search or paste an HTTPS page URL") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = allowed && !busy && query.isNotBlank(), onClick = { if(query.startsWith("https://")) vm.readWebPage(query.trim()) else vm.searchWeb(query, brave) }) { Text("Search / Read") }
        LazyColumn {
            page?.let { s -> item {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                ActionStrip {
                    TextButton(onClick = { inspect = s }) { Text("Read page") }
                    TextButton(onClick = { vm.saveNote(s.title, s.passage, "Travel", s.location) }) { Text("Save offline") }
                    TextButton(onClick = { vm.useSource(LibraryItem("web", s.title, "Web", s.passage, s.location, s.savedAt)); vm.taskPrompt("Summarize the selected source", ""); onAsk() }) { Text("Summarize / Ask") }
                }
            } }
            items(results, key = { it.url }) { r ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium); Text(r.snippet); Text(r.url, style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !busy && allowed, onClick = { vm.readWebPage(r.url) }) { Text("Read this page") }
                }
            }
        }
    }
    inspect?.let { s -> SourceDialog(s, { inspect = null }, { vm.saveNote(s.title, s.passage, "Travel", s.location) }) }
}

@Composable
fun AssistantSettings(vm: ChatViewModel) {
    val memory by vm.memories.collectAsStateWithLifecycle()
    var editedMemory by remember(memory) { mutableStateOf(memory) }
    var key by remember { mutableStateOf(vm.webTools.braveKey) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preferences to remember", style = MaterialTheme.typography.titleMedium)
        Text("Optional, stored on this device. For example: use metric units; explain briefly. Never added to web searches.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(editedMemory, { editedMemory = it.take(1500) }, label = { Text("Your preferences") }, modifier = Modifier.fillMaxWidth())
        ActionStrip {
            TextButton(onClick = { vm.setMemories(editedMemory); vm.notice.value = "Preferences saved." }) { Text("Save preferences") }
            TextButton(onClick = { vm.setMemories(""); editedMemory = "" }) { Text("Forget all") }
        }
        OutlinedTextField(key, { key = it }, label = { Text("Brave Search API key (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Used only for general web search. Kept for this app session only. Wikipedia search needs no key.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.webTools.braveKey = key.trim(); vm.notice.value = "Search key updated for this session." }) { Text("Use key") }
    }
}

@Composable
fun SettingsWithPreferences(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    var preferences by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        ActionStrip {
            FilterChip(!preferences, { preferences = false }, label = { Text("Models") })
            FilterChip(preferences, { preferences = true }, label = { Text("Assistant preferences") })
        }
        if(preferences) Column(Modifier.verticalScroll(rememberScrollState())) { AssistantSettings(viewModel) }
        else SettingsPanel(viewModel, Modifier.weight(1f))
    }
}

@Composable
fun VoiceControls(vm: ChatViewModel, voice: OfflineVoice, onText: (String) -> Unit) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    val currentOnText by rememberUpdatedState(onText)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) { listening = true; voice.listen(currentOnText) { listening = false } }
        else vm.notice.value = "Microphone access was denied. You can still type your question."
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, voice) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { voice.stop(); listening = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.stop() }
    }
    TextButton(onClick = {
        if(listening) { voice.stop(); listening = false }
        else if(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listening = true; voice.listen(currentOnText) { listening = false }
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text(if(listening) "Listening… tap to stop" else "Dictate offline") }
}
