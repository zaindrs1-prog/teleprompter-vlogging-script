@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.teleprompterpro.app.ui.camera

import android.content.pm.ActivityInfo
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teleprompterpro.app.MainActivity
import com.teleprompterpro.app.audio.MicDevice
import com.teleprompterpro.app.reader.ScrollMath
import com.teleprompterpro.app.reader.VoiceSyncStatus
import com.teleprompterpro.app.recording.RecordingState
import com.teleprompterpro.app.settings.CameraFacing
import com.teleprompterpro.app.settings.OverlayPosition
import com.teleprompterpro.app.settings.ScrollMode
import com.teleprompterpro.app.settings.StabilizationMode
import com.teleprompterpro.app.settings.VoiceLanguage
import com.teleprompterpro.app.ui.components.BigSlider
import com.teleprompterpro.app.ui.components.ErrorBanner
import com.teleprompterpro.app.ui.components.SectionTitle
import com.teleprompterpro.app.ui.theme.AppColors
import com.teleprompterpro.app.util.TimeFormat
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class Sheet { NONE, READER, CAMERA, MIC }

/**
 * THE main screen: live camera preview with the script overlaid on top, and
 * the recording controls — all in one place.
 *
 * Layout contract (fixes the "controls cover the script" complaint):
 * the overlay region and the control region are separate children of a
 * Column (portrait) / Row (landscape). They share the screen; they never
 * stack. When controls collapse, the strip shrinks and the overlay grows.
 */
@Composable
fun CameraScreen(
    scriptId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onTrim: (String) -> Unit,
    vm: CameraViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    LaunchedEffect(scriptId, lifecycleOwner) { vm.start(scriptId, lifecycleOwner) }

    // Keep screen on and route remote keys while this screen is visible.
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (activity as? MainActivity)?.remoteHandler = { vm.onRemote(it) }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (activity as? MainActivity)?.remoteHandler = null
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Lock orientation for the duration of a take so the file's rotation is stable.
    LaunchedEffect(ui.isBusy) {
        activity?.requestedOrientation = if (ui.isBusy) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Feed display rotation to CameraX so preview + file orientation are right.
    LaunchedEffect(configuration) {
        val rotation = if (Build.VERSION.SDK_INT >= 30) view.display?.rotation
        else @Suppress("DEPRECATION") (activity?.windowManager?.defaultDisplay?.rotation)
        vm.onDisplayRotation(rotation ?: Surface.ROTATION_0)
    }

    BackHandler(enabled = ui.isBusy) { /* ignore back while recording; use Stop */ }

    var showDone by remember { mutableStateOf<RecordingState.Done?>(null) }
    LaunchedEffect(ui.recording) {
        val r = ui.recording
        if (r is RecordingState.Done) showDone = r
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ------------------------------------------------ camera preview (bottom layer)
        CameraPreview(
            vm = vm,
            mirror = ui.facing == CameraFacing.FRONT && ui.mirrorFrontPreview,
            onTap = { if (ui.isRecording) vm.toggleControls() },
        )

        when (val b = ui.binding) {
            is BindingState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, null, tint = AppColors.Warn, modifier = Modifier.size(40.dp))
                    Text(b.error.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(b.error.message, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::retry) { Text("Try again") }
                    TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                }
            }
            BindingState.Checking, BindingState.Binding -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            BindingState.Ready -> Unit
        }

        // ------------------------------------------------ overlay + controls (never overlap)
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    OverlayRegion(ui, vm, Modifier.weight(1f).fillMaxHeight())
                    ControlRegion(
                        ui, vm, landscape = true,
                        onBack = onBack, onEdit = onEdit, openSheet = { sheet = it },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    OverlayRegion(ui, vm, Modifier.weight(1f).fillMaxWidth())
                    ControlRegion(
                        ui, vm, landscape = false,
                        onBack = onBack, onEdit = onEdit, openSheet = { sheet = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ui.banner?.let { err ->
                ErrorBanner(err, onDismiss = vm::dismissBanner, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
            }
            when (val r = ui.recording) {
                is RecordingState.Countdown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${r.secondsLeft}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                }
                is RecordingState.Starting, is RecordingState.Stopping -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text((r as? RecordingState.Starting)?.step ?: (r as RecordingState.Stopping).step, color = Color.White)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------ sheets & dialogs
    when (sheet) {
        Sheet.READER -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) { ReaderSheet(ui, vm) }
        Sheet.CAMERA -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) { CameraSheet(ui, vm) }
        Sheet.MIC -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) { MicSheet(ui, vm) }
        Sheet.NONE -> Unit
    }

    showDone?.let { done ->
        AlertDialog(
            onDismissRequest = { showDone = null; vm.consumeTerminalState() },
            title = { Text("Saved to gallery") },
            text = {
                Text(
                    "${TimeFormat.mediaDuration(done.durationMs)} • ${TimeFormat.bytes(done.sizeBytes)}\n" +
                        "Movies/TeleprompterPro" + (done.notice?.let { "\n\n${it.message}" } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = { showDone = null; vm.consumeTerminalState(); onTrim(done.uri.toString()) }) {
                    Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(6.dp)); Text("Trim")
                }
            },
            dismissButton = { TextButton(onClick = { showDone = null; vm.consumeTerminalState() }) { Text("Done") } },
        )
    }
    (ui.recording as? RecordingState.Failed)?.let { f ->
        AlertDialog(
            onDismissRequest = vm::consumeTerminalState,
            title = { Text(f.error.title) },
            text = { Text(f.error.message) },
            confirmButton = { TextButton(onClick = vm::consumeTerminalState) { Text("OK") } },
        )
    }
}

@Composable
private fun CameraPreview(vm: CameraViewModel, mirror: Boolean, onTap: () -> Unit) {
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                previewRef = this
                vm.attachPreview(this)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = if (mirror) -1f else 1f }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        previewRef?.let { vm.focus(it, if (mirror) size.width - pos.x else pos.x, pos.y) }
                        onTap()
                    },
                )
            },
    )
    DisposableEffect(Unit) { onDispose { vm.detachPreview() } }
}

// -------------------------------------------------------------------- overlay region

@Composable
private fun OverlayRegion(ui: CameraUiState, vm: CameraViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        val h = maxHeight
        // Overlay height: ~45% of the region when idle, grows to ~60% once the
        // controls tuck away during recording. Position follows the setting.
        val frac = if (ui.isRecording && !ui.controlsExpanded) 0.62f else 0.48f
        val overlayH = h * frac
        val align = when (ui.overlayPosition) {
            OverlayPosition.TOP -> Alignment.TopCenter
            OverlayPosition.CENTER -> Alignment.Center
            OverlayPosition.BOTTOM -> Alignment.BottomCenter
        }
        val text = ui.script?.body.orEmpty()
        val cut = remember(text, ui.matchedWord, ui.reader.scrollMode) {
            if (ui.reader.scrollMode == ScrollMode.VOICE) charIndexForWord(text, ui.matchedWord) else 0
        }
        Column(Modifier.align(align)) {
            ScriptOverlay(
                text = text,
                progress = ui.progress,
                fontSp = ui.reader.fontSp,
                backgroundOpacity = ui.reader.backgroundOpacity,
                mirror = ui.reader.mirrorText,
                highlightUntilChar = cut,
                playing = ui.playing,
                onUserSeek = vm::seekTo,
                modifier = Modifier.fillMaxWidth().height(overlayH),
            )
            // Progress + ETA live directly under the text, never over it.
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ui.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = AppColors.Ready,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
        }
    }
}

/** Character offset of the start of word [wordIndex] (used to dim already-spoken text). */
private fun charIndexForWord(text: String, wordIndex: Int): Int {
    if (wordIndex <= 0) return 0
    var count = 0
    var inWord = false
    for (i in text.indices) {
        val ch = text[i]
        val wordChar = ch.isLetterOrDigit() || ch == '\'' || ch == '’'
        if (wordChar && !inWord) {
            inWord = true
            if (count == wordIndex) return i
            count++
        } else if (!wordChar) {
            inWord = false
        }
    }
    return text.length
}

// -------------------------------------------------------------------- control region

@Composable
private fun ControlRegion(
    ui: CameraUiState,
    vm: CameraViewModel,
    landscape: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    openSheet: (Sheet) -> Unit,
    modifier: Modifier,
) {
    val collapsed = ui.isRecording && !ui.controlsExpanded
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = if (collapsed) 0.45f else 0.72f),
        shape = if (landscape) RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
        else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        if (collapsed) {
            CollapsedStrip(ui, vm, landscape)
        } else if (landscape) {
            Column(
                Modifier.width(196.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusLine(ui)
                TransportRow(ui, vm)
                EtaRow(ui)
                RecordRow(ui, vm) { openSheet(Sheet.MIC) }
                ToolRow(ui, vm, onBack, onEdit, openSheet)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusLine(ui)
                EtaRow(ui)
                // Transport on its own line so it fits 360 dp phones; record + mic below.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TransportRow(ui, vm) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RecordButton(ui, vm)
                    Box(Modifier.align(Alignment.CenterEnd)) { MicChip(ui) { openSheet(Sheet.MIC) } }
                }
                ToolRow(ui, vm, onBack, onEdit, openSheet)
            }
        }
    }
}

@Composable
private fun CollapsedStrip(ui: CameraUiState, vm: CameraViewModel, landscape: Boolean) {
    val content: @Composable () -> Unit = {
        RecDot()
        Text(
            TimeFormat.recordingClock(elapsedRecordingMs(ui)),
            color = Color.White, style = MaterialTheme.typography.labelLarge,
        )
        Icon(
            if (ui.micWarning) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = ui.micLabel,
            tint = if (ui.micWarning) AppColors.Warn else AppColors.Ready, modifier = Modifier.size(18.dp),
        )
        Text("ETA ${ScrollMath.clock(ui.remainingMs)}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = vm::togglePlay, modifier = Modifier.size(32.dp)) {
            Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
        }
        IconButton(onClick = vm::stopRecording, modifier = Modifier.size(32.dp)) {
            Box(Modifier.size(16.dp).background(AppColors.Record, RoundedCornerShape(3.dp)))
        }
    }
    if (landscape) {
        Column(Modifier.width(56.dp).fillMaxHeight().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } else {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun RecDot() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(600); on = !on } }
    Box(Modifier.size(10.dp).background(if (on) AppColors.Record else AppColors.Record.copy(alpha = 0.3f), CircleShape))
}

@Composable
private fun elapsedRecordingMs(ui: CameraUiState): Long {
    val start = (ui.recording as? RecordingState.Recording)?.startedElapsedMs ?: return 0L
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(250) } }
    return now - start
}

@Composable
private fun StatusLine(ui: CameraUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (ui.isRecording) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecDot(); Text(TimeFormat.recordingClock(elapsedRecordingMs(ui)), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Text(ui.activeQualityLabel.ifBlank { "Camera" }, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
        val voice = ui.voiceStatus
        val voiceText = when {
            ui.reader.scrollMode != ScrollMode.VOICE -> "Timed • ${ui.reader.wpm} wpm"
            voice is VoiceSyncStatus.Listening -> "Voice • hearing: " + voice.lastHeard.takeLast(28).ifBlank { "…" }
            voice is VoiceSyncStatus.Starting -> "Voice • starting"
            voice is VoiceSyncStatus.Unavailable -> "Voice unavailable"
            else -> "Voice • paused"
        }
        Text(voiceText, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun EtaRow(ui: CameraUiState) {
    Text(
        "${ScrollMath.clock(ui.elapsedMs)} / ${ScrollMath.clock(ui.totalMs)} • ${ScrollMath.clock(ui.remainingMs)} left • ${ui.words} words",
        color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun TransportRow(ui: CameraUiState, vm: CameraViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::restart) { Icon(Icons.Default.Replay, "Restart", tint = Color.White) }
        IconButton(onClick = { vm.skip(-5) }) { Icon(Icons.Default.Replay5, "Back 5s", tint = Color.White) }
        IconButton(onClick = vm::togglePlay, modifier = Modifier.size(52.dp)) {
            Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (ui.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = { vm.skip(5) }) { Icon(Icons.Default.Forward5, "Forward 5s", tint = Color.White) }
    }
}

@Composable
private fun RecordRow(ui: CameraUiState, vm: CameraViewModel, onMic: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordButton(ui, vm)
        MicChip(ui, onMic)
    }
}

@Composable
private fun RecordButton(ui: CameraUiState, vm: CameraViewModel) {
    val enabled = ui.binding is BindingState.Ready && (ui.isRecording || !ui.isBusy || ui.recording is RecordingState.Countdown)
    Box(
        Modifier
            .size(72.dp)
            .background(Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(enabled = enabled) { vm.toggleRecord() },
        contentAlignment = Alignment.Center,
    ) {
        if (ui.isRecording) {
            Box(Modifier.size(28.dp).background(AppColors.Record, RoundedCornerShape(6.dp)))
        } else {
            Box(Modifier.size(56.dp).background(if (enabled) AppColors.Record else AppColors.Record.copy(alpha = 0.4f), CircleShape))
        }
    }
}

@Composable
private fun MicChip(ui: CameraUiState, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = if (ui.micWarning) AppColors.Warn.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (!ui.micEnabled || ui.micWarning) Icons.Default.MicOff else Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Column {
                Text(ui.micLabel, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                val level = if (ui.isRecording) ui.liveAudioLevel else ui.idleMicLevel
                LinearProgressIndicator(progress = { level }, modifier = Modifier.width(96.dp).height(3.dp), color = AppColors.Ready, trackColor = Color.White.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
private fun ToolRow(ui: CameraUiState, vm: CameraViewModel, onBack: () -> Unit, onEdit: () -> Unit, openSheet: (Sheet) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !ui.isBusy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        IconButton(onClick = onEdit, enabled = !ui.isBusy) { Icon(Icons.Default.Edit, "Edit script", tint = Color.White) }
        IconButton(onClick = { openSheet(Sheet.READER) }) { Icon(Icons.Default.Tune, "Text & speed", tint = Color.White) }
        IconButton(onClick = { openSheet(Sheet.CAMERA) }) { Icon(Icons.Default.Cameraswitch, "Camera", tint = Color.White) }
        IconButton(onClick = { openSheet(Sheet.MIC) }) { Icon(if (ui.micWarning) Icons.Default.MicOff else Icons.Default.Mic, "Microphone", tint = if (ui.micWarning) AppColors.Warn else Color.White) }
        if (ui.flashAvailable) IconButton(onClick = vm::toggleTorch) { Icon(if (ui.torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch", tint = Color.White) }
    }
}

// -------------------------------------------------------------------- sheets

@Composable
private fun ReaderSheet(ui: CameraUiState, vm: CameraViewModel) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Text & scrolling", style = MaterialTheme.typography.titleLarge)
        BigSlider("Font size", "${ui.reader.fontSp.toInt()} sp", ui.reader.fontSp, 14f..96f, vm::setFont)
        BigSlider(
            "Speed", "${ui.reader.wpm} wpm • total ${ScrollMath.clock(ui.totalMs)}",
            ui.reader.wpm.toFloat(), ScrollMath.MIN_WPM.toFloat()..ScrollMath.MAX_WPM.toFloat(),
            { vm.setWpm(it.toInt()) },
        )
        if (ui.words > 0) {
            val minTotal = ScrollMath.totalDurationMs(ui.words, ScrollMath.MAX_WPM).toFloat()
            val maxTotal = ScrollMath.totalDurationMs(ui.words, ScrollMath.MIN_WPM).toFloat()
            BigSlider(
                "Target duration", ScrollMath.clock(ui.totalMs),
                ui.totalMs.toFloat().coerceIn(minTotal, maxTotal), minTotal..maxTotal,
                { vm.setTargetDuration(it.toLong()) },
            )
        }
        BigSlider("Background dimness", "${(ui.reader.backgroundOpacity * 100).toInt()}%", ui.reader.backgroundOpacity, 0f..1f, vm::setOpacity)

        SectionTitle("Scroll mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.reader.scrollMode == ScrollMode.TIMED, onClick = { vm.setScrollMode(ScrollMode.TIMED) }, label = { Text("Timed") })
            FilterChip(selected = ui.reader.scrollMode == ScrollMode.VOICE, onClick = { vm.setScrollMode(ScrollMode.VOICE) }, label = { Text("Voice sync (word matching)") }, enabled = ui.voiceAvailable)
        }
        if (ui.reader.scrollMode == ScrollMode.VOICE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceLanguage.entries.forEach { l ->
                    FilterChip(selected = ui.voiceLanguage == l, onClick = { vm.setVoiceLanguage(l) }, label = { Text(l.label) })
                }
            }
            val v = ui.voiceStatus
            Text(
                when (v) {
                    is VoiceSyncStatus.Listening -> (if (v.onDevice) "On-device recognizer • " else "System recognizer • ") + "heard: " + v.lastHeard.takeLast(60).ifBlank { "(waiting for speech)" }
                    is VoiceSyncStatus.Unavailable -> v.reason
                    VoiceSyncStatus.Starting -> "Starting recognizer…"
                    VoiceSyncStatus.Off -> "Press play: the text advances only when your spoken words match the script. Noise and other languages do not scroll it."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Note: while recording, some phones give the recognizer silence because the video recorder owns the microphone. If that happens the app switches to timed scrolling and tells you.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!ui.voiceAvailable) {
            Text("Voice sync is unavailable: this phone has no speech recognition service installed.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Overlay position")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayPosition.entries.forEach { p -> FilterChip(selected = ui.overlayPosition == p, onClick = { vm.setOverlayPosition(p) }, label = { Text(p.label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Mirror text (for beam-splitter glass)")
            Switch(checked = ui.reader.mirrorText, onCheckedChange = vm::setMirrorText)
        }
        Text("Countdown before recording: ${ui.countdownSeconds}s", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 3, 5, 10).forEach { s -> FilterChip(selected = ui.countdownSeconds == s, onClick = { vm.setCountdown(s) }, label = { Text("${s}s") }) }
        }
    }
}

@Composable
private fun CameraSheet(ui: CameraUiState, vm: CameraViewModel) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Camera", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::flipCamera, enabled = !ui.isBusy && ui.hasFront && ui.hasRear) {
                Icon(Icons.Default.Cameraswitch, null); Spacer(Modifier.width(6.dp)); Text("Switch to ${if (ui.facing == CameraFacing.REAR) "front" else "rear"}")
            }
            if (ui.facing == CameraFacing.FRONT) {
                OutlinedButton(onClick = vm::toggleMirrorPreview) {
                    Icon(Icons.Default.Flip, null); Spacer(Modifier.width(6.dp)); Text(if (ui.mirrorFrontPreview) "Mirror: on" else "Mirror: off")
                }
            }
        }
        if (ui.facing == CameraFacing.FRONT) {
            Text("Mirror affects the preview only; the saved video is never mirrored.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Brightness (exposure)")
        if (ui.exposureRange.first < ui.exposureRange.last) {
            val ev = ui.exposureIndex * ui.exposureStepEv
            BigSlider(
                "Exposure compensation", String.format(java.util.Locale.US, "%+.1f EV", ev),
                ui.exposureIndex.toFloat(), ui.exposureRange.first.toFloat()..ui.exposureRange.last.toFloat(),
                { vm.setExposure(kotlin.math.round(it).toInt()) },
                steps = (ui.exposureRange.last - ui.exposureRange.first - 1).coerceAtLeast(0),
            )
            Text("Works live, even while recording. Drag right to brighten a dark room.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("This lens does not expose manual exposure control.", style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.zoomRange.endInclusive > ui.zoomRange.start) {
            BigSlider("Zoom", String.format(java.util.Locale.US, "%.1f×", ui.zoom), ui.zoom, ui.zoomRange, vm::setZoom)
        }

        SectionTitle("Resolution (only options this lens supports)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.resolutionOptions.forEach { r ->
                FilterChip(selected = ui.resolution == r, enabled = !ui.isBusy, onClick = { vm.setQuality(r, ui.frameRate, ui.stabilization) }, label = { Text(r.label) })
            }
        }
        SectionTitle("Frame rate")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.frameRateOptions.forEach { f ->
                FilterChip(selected = ui.frameRate == f, enabled = !ui.isBusy, onClick = { vm.setQuality(ui.resolution, f, ui.stabilization) }, label = { Text(f.label) })
            }
        }
        if (ui.stabilizationAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Video stabilization")
                Switch(checked = ui.stabilization == StabilizationMode.STANDARD, enabled = !ui.isBusy, onCheckedChange = { vm.setQuality(ui.resolution, ui.frameRate, if (it) StabilizationMode.STANDARD else StabilizationMode.OFF) })
            }
        }
        Text("Active: ${ui.activeQualityLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ui.storageLine, style = MaterialTheme.typography.labelMedium, color = if (ui.storageWarn) AppColors.Warn else MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.batteryPct >= 0) Text("Battery ${ui.batteryPct}%" + if (ui.thermalWarn) " • device is warm" else "", style = MaterialTheme.typography.labelMedium, color = if (ui.thermalWarn) AppColors.Warn else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MicSheet(ui: CameraUiState, vm: CameraViewModel) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Microphone", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Record audio")
            Switch(checked = ui.micEnabled, enabled = !ui.isBusy, onCheckedChange = { vm.toggleMic() })
        }
        SectionTitle("Input device")
        ui.micDevices.forEach { d -> MicRow(d, selected = d.id == ui.selectedMic.id, enabled = !ui.isBusy && ui.micEnabled) { vm.selectMic(d) } }
        Spacer(Modifier.height(4.dp))
        Button(onClick = vm::testMic, enabled = !ui.isBusy && ui.micEnabled && !ui.micProbing) {
            if (ui.micProbing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)) }
            Text(if (ui.micProbing) "Listening…" else "Test microphone signal")
        }
        val p = ui.micProbe
        if (p != null) {
            val ok = p.hasSignal && !p.fellBack
            Surface(shape = RoundedCornerShape(12.dp), color = (if (ok) AppColors.Ready else AppColors.Warn).copy(alpha = 0.15f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.summary, style = MaterialTheme.typography.titleMedium, color = if (ok) AppColors.Ready else AppColors.Warn)
                    Text("Peak level ${(p.peakLevel * 100).toInt()}% • routed from ${p.routed?.name ?: "unknown"}", style = MaterialTheme.typography.labelMedium)
                    if (!ok) Text("Speak or tap the mic during the test. If the level stays at 0, the recording would be silent — the app will not pretend otherwise.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (ui.isRecording) Text("Live: ${ui.liveAudio.label}", style = MaterialTheme.typography.bodyMedium, color = if (ui.micWarning) AppColors.Warn else AppColors.Ready)
        Text(
            "External mics (Bluetooth, USB-C, wired) appear automatically when connected. No Bluetooth permission is needed for this.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MicRow(d: MicDevice, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, null, tint = if (d.external) AppColors.Info else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                Text(if (d.id == -1) "Android picks the best available input" else d.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Text("Selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
