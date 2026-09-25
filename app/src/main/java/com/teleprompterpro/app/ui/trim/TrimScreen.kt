package com.teleprompterpro.app.ui.trim

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleprompterpro.app.media.MediaStoreSaver
import com.teleprompterpro.app.media.VideoMetadata
import com.teleprompterpro.app.media.VideoTrimmer
import com.teleprompterpro.app.ui.components.TopBar
import com.teleprompterpro.app.util.FileUtil
import com.teleprompterpro.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrimUi(
    val durationMs: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val mute: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    val loaded: Boolean = false,
)

class TrimViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TrimUi())
    val ui: StateFlow<TrimUi> = _ui
    private var uri: Uri? = null

    fun load(uriString: String) {
        if (uri != null) return
        uri = Uri.parse(uriString)
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { VideoMetadata.of(getApplication(), uri!!) }
            _ui.value = TrimUi(durationMs = meta.durationMs, startMs = 0L, endMs = meta.durationMs, loaded = true)
        }
    }

    fun setRange(start: Long, end: Long) { _ui.value = _ui.value.copy(startMs = start, endMs = end) }
    fun setMute(m: Boolean) { _ui.value = _ui.value.copy(mute = m) }

    /** Lossless trim (reused VideoTrimmer) → saved as a NEW file; the original is kept. */
    fun export() {
        val src = uri ?: return
        val s = _ui.value
        if (s.working) return
        _ui.value = s.copy(working = true, message = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val out = FileUtil.newStagingFile(app, "trim")
            val result = withContext(Dispatchers.IO) { VideoTrimmer.trim(app, src, s.startMs, s.endMs, out, s.mute) }
            if (!result.success) {
                FileUtil.deleteQuietly(out)
                _ui.value = _ui.value.copy(working = false, message = "Trim failed: ${result.error?.javaClass?.simpleName ?: "unknown"}")
                return@launch
            }
            val saved = runCatching { MediaStoreSaver(app).saveVideo(out, "TP_trim_${TimeFormat.fileTimestamp()}.mp4") }
            FileUtil.deleteQuietly(out)
            _ui.value = _ui.value.copy(
                working = false,
                message = if (saved.isSuccess) "Saved trimmed copy to Movies/TeleprompterPro" else "Could not save the trimmed file",
            )
        }
    }
}

@Composable
fun TrimScreen(uriString: String, onBack: () -> Unit, vm: TrimViewModel = viewModel()) {
    LaunchedEffect(uriString) { vm.load(uriString) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopBar("Trim", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!ui.loaded) { CircularProgressIndicator(); return@Column }
            Text("Video length ${TimeFormat.mediaDuration(ui.durationMs)}", style = MaterialTheme.typography.titleMedium)
            val total = ui.durationMs.coerceAtLeast(1L).toFloat()
            RangeSlider(
                value = ui.startMs.toFloat()..ui.endMs.toFloat(),
                onValueChange = { r ->
                    val start = r.start.toLong()
                    val end = r.endInclusive.toLong().coerceAtLeast(start + 500L).coerceAtMost(ui.durationMs)
                    vm.setRange(start, end)
                },
                valueRange = 0f..total,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Start ${TimeFormat.mediaDuration(ui.startMs)}")
                Text("Keep ${TimeFormat.mediaDuration(ui.endMs - ui.startMs)}")
                Text("End ${TimeFormat.mediaDuration(ui.endMs)}")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Remove audio")
                Switch(checked = ui.mute, onCheckedChange = vm::setMute)
            }
            Text(
                "Lossless: no re-encoding, so quality is untouched and it takes seconds. The cut snaps to the nearest keyframe. The original recording is kept.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::export, enabled = !ui.working, modifier = Modifier.fillMaxWidth()) {
                if (ui.working) { CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.padding(6.dp)) }
                Text(if (ui.working) "Trimming…" else "Save trimmed copy")
            }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
