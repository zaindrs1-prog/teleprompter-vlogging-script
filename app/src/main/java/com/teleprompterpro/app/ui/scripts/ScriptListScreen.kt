package com.teleprompterpro.app.ui.scripts

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleprompterpro.app.reader.ScrollMath
import com.teleprompterpro.app.script.Script
import com.teleprompterpro.app.script.ScriptRepository
import com.teleprompterpro.app.ui.components.TopBar
import com.teleprompterpro.app.util.TimeFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScriptListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScriptRepository(app)
    val scripts = repo.scripts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(onCreated: (Long) -> Unit) = viewModelScope.launch {
        val s = repo.create()
        onCreated(s.id)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch { repo.rename(id, title.ifBlank { "Untitled script" }) }
    fun duplicate(id: Long) = viewModelScope.launch { repo.duplicate(id) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@Composable
fun ScriptListScreen(
    onOpenEditor: (Long) -> Unit,
    onRecord: (Long) -> Unit,
    onRecordings: () -> Unit,
    onSettings: () -> Unit,
    vm: ScriptListViewModel = viewModel(),
) {
    val scripts by vm.scripts.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<Script?>(null) }
    var deleteTarget by remember { mutableStateOf<Script?>(null) }

    Scaffold(
        topBar = {
            TopBar(title = "My Scripts", onBack = null) {
                IconButton(onClick = onRecordings) { Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.create(onOpenEditor) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New script") },
            )
        },
    ) { padding ->
        if (scripts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No scripts yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Create a script, then record with it overlaid on your camera. " +
                            "Scripts are saved on this phone permanently — no limits, no expiry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { vm.create(onOpenEditor) }) { Text("Create your first script") }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(scripts, key = { it.id }) { s ->
                    ScriptCard(
                        script = s,
                        onOpen = { onOpenEditor(s.id) },
                        onRecord = { onRecord(s.id) },
                        onRename = { renameTarget = s },
                        onDuplicate = { vm.duplicate(s.id) },
                        onDelete = { deleteTarget = s },
                    )
                }
            }
        }
    }

    renameTarget?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename script") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, title); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${s.title}\"?") },
            text = { Text("This is the only way a script is ever removed. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScriptCard(
    script: Script,
    onOpen: () -> Unit,
    onRecord: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val words = script.wordCount
    val eta = ScrollMath.clock(ScrollMath.totalDurationMs(words, script.settings.wpm))
    Card(onClick = onOpen, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(script.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$words words • ≈$eta at ${script.settings.wpm} wpm • ${TimeFormat.displayDate(script.updatedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (script.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    script.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Record with this script")
            }
        }
    }
}
