package com.teleprompterpro.app.ui.editor

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleprompterpro.app.reader.ScrollMath
import com.teleprompterpro.app.script.Script
import com.teleprompterpro.app.script.ScriptRepository
import com.teleprompterpro.app.ui.components.TopBar
import com.teleprompterpro.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Editor state lives in a ViewModel and is autosaved to DataStore, so
 * switching to another app to copy text (or being killed in the background)
 * can never lose the draft. There is NO length limit anywhere in this file.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScriptRepository(app)

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _body = MutableStateFlow(TextFieldValue(""))
    val body: StateFlow<TextFieldValue> = _body
    private val _wpm = MutableStateFlow(140)
    val wpm: StateFlow<Int> = _wpm
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

    private var scriptId = -1L
    private var saveJob: Job? = null
    private var dirty = false

    fun load(id: Long) {
        if (scriptId == id && _loaded.value) return
        scriptId = id
        viewModelScope.launch {
            val s = repo.get(id)
            if (s != null) {
                _title.value = s.title
                _body.value = TextFieldValue(s.body, TextRange(0))
                _wpm.value = s.settings.wpm
            }
            _loaded.value = true
        }
    }

    fun setTitle(t: String) { _title.value = t; markDirty() }

    fun setBody(v: TextFieldValue) {
        // Stored exactly as typed: no trim, no whitespace collapsing.
        val changed = v.text != _body.value.text
        _body.value = v
        if (changed) markDirty()
    }

    fun appendClipboard(text: String?) {
        if (text.isNullOrEmpty()) { _importMessage.value = "Clipboard is empty"; return }
        val cur = _body.value
        val sel = cur.selection
        val start = minOf(sel.start, sel.end).coerceIn(0, cur.text.length)
        val end = maxOf(sel.start, sel.end).coerceIn(0, cur.text.length)
        val newText = cur.text.substring(0, start) + text + cur.text.substring(end)
        _body.value = TextFieldValue(newText, TextRange(start + text.length))
        _importMessage.value = "Pasted ${Script.countWords(text)} words"
        markDirty()
        flushNow()
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
            }.onFailure { Logger.w("Editor", "Import failed", it) }.getOrNull()
        }
        if (text == null) { _importMessage.value = "Could not read that file"; return@launch }
        // Only normalization: Windows line endings → \n. Spacing/blank lines are kept.
        val normalized = text.replace("\r\n", "\n").removePrefix("\uFEFF")
        _body.value = TextFieldValue(normalized, TextRange(0))
        if (_title.value.isBlank() || _title.value == "Untitled script") {
            val name = runCatching {
                getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull()
            if (!name.isNullOrBlank()) _title.value = name.removeSuffix(".txt")
        }
        _importMessage.value = "Imported ${Script.countWords(normalized)} words"
        markDirty()
        flushNow()
    }

    fun clearMessage() { _importMessage.value = null }

    private fun markDirty() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600) // debounce keystrokes
            flushNow()
        }
    }

    /** Persist immediately (called on pause / dispose / explicit actions). */
    fun flushNow() {
        if (!dirty || scriptId < 0 || !_loaded.value) return
        dirty = false
        val t = _title.value.ifBlank { "Untitled script" }
        val b = _body.value.text
        viewModelScope.launch { repo.updateBody(scriptId, t, b) }
    }

    override fun onCleared() {
        // Last line of defence: synchronous save if the VM is destroyed while dirty.
        if (dirty && scriptId >= 0 && _loaded.value) {
            val t = _title.value.ifBlank { "Untitled script" }
            val b = _body.value.text
            runCatching { runBlocking { repo.updateBody(scriptId, t, b) } }
        }
        super.onCleared()
    }
}

@Composable
fun EditorScreen(
    scriptId: Long,
    onBack: () -> Unit,
    onRecord: () -> Unit,
    vm: EditorViewModel = viewModel(),
) {
    LaunchedEffect(scriptId) { vm.load(scriptId) }
    val title by vm.title.collectAsState()
    val body by vm.body.collectAsState()
    val wpm by vm.wpm.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val message by vm.importMessage.collectAsState()
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Save whenever the app goes to the background (e.g. user switches to copy text).
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) vm.flushNow()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            vm.flushNow()
        }
    }

    val openTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }

    LaunchedEffect(message) {
        if (message != null) { kotlinx.coroutines.delay(2500); vm.clearMessage() }
    }

    val words = Script.countWords(body.text)
    val eta = ScrollMath.clock(ScrollMath.totalDurationMs(words, wpm))

    Scaffold(
        topBar = {
            TopBar(title = "Edit script", onBack = { vm.flushNow(); onBack() }) {
                Button(onClick = { vm.flushNow(); onRecord() }, enabled = loaded) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Record")
                }
                Spacer(Modifier.width(8.dp))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = vm::setTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.appendClipboard(clipboard.getText()?.text) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Paste")
                }
                OutlinedButton(onClick = { openTxt.launch(arrayOf("text/plain", "text/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import .txt")
                }
            }
            Text(
                message ?: "$words words • ≈$eta at $wpm wpm • no length limit • autosaved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = body,
                onValueChange = vm::setBody,
                placeholder = { Text("Type or paste your script here. Line breaks and spacing are kept exactly as you write them.") },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp, fontFamily = FontFamily.Default),
                enabled = loaded,
            )
        }
    }
}
