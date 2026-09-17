package com.teleprompterpro.app.ui.recordings

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleprompterpro.app.media.MediaStoreSaver
import com.teleprompterpro.app.media.RecordingItem
import com.teleprompterpro.app.ui.components.TopBar
import com.teleprompterpro.app.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {
    val saver = MediaStoreSaver(app)
    private val _items = MutableStateFlow<List<RecordingItem>>(emptyList())
    val items: StateFlow<List<RecordingItem>> = _items
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        _items.value = saver.listRecordings()
        _loading.value = false
    }

    fun delete(item: RecordingItem) = viewModelScope.launch { saver.delete(item.uri); refresh() }
}

@Composable
fun RecordingsScreen(onBack: () -> Unit, onTrim: (String) -> Unit, vm: RecordingsViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(topBar = { TopBar("Recordings", onBack) }) { padding ->
        if (!loading && items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No recordings yet. Takes are saved to Movies/TeleprompterPro and appear in your gallery immediately.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { item ->
                    val thumb by produceState<Bitmap?>(null, item.uri) { value = vm.saver.loadThumbnail(item.uri) }
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
                                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${TimeFormat.mediaDuration(item.durationMs)} • ${item.resolutionLabel} • ${TimeFormat.bytes(item.sizeBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(TimeFormat.displayDate(item.dateTakenMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    IconButton(onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(item.uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        })
                                    }) { Icon(Icons.Default.PlayArrow, "Play") }
                                    IconButton(onClick = { onTrim(item.uri.toString()) }) { Icon(Icons.Default.ContentCut, "Trim") }
                                    IconButton(onClick = {
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Share video"))
                                    }) { Icon(Icons.Default.Share, "Share") }
                                    IconButton(onClick = { deleteTarget = item }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text(item.displayName) },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
