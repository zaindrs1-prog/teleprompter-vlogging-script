package com.teleprompterpro.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.camera.video.AudioStats
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.teleprompterpro.app.camera.SingleCameraSession
import com.teleprompterpro.app.media.MediaStoreSaver
import com.teleprompterpro.app.media.VideoMetadata
import com.teleprompterpro.app.settings.VideoResolution
import com.teleprompterpro.app.util.AppError
import com.teleprompterpro.app.util.FileUtil
import com.teleprompterpro.app.util.Logger
import com.teleprompterpro.app.util.TimeFormat
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/**
 * Single-stream recording pipeline, adapted from creator-cam's
 * DualRecordingController.recordSingle():
 *   preflight (storage/battery) → countdown → start → monitor (storage,
 *   thermal) → stop → finalize → MediaStore save.
 *
 * Nothing here throws to the UI. Every failure — including a safety stop
 * mid-take — lands in [state] as Done(notice) or Failed(error) and the file
 * captured so far is saved whenever CameraX finalized it successfully.
 */
class RecordingController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val _audio = MutableStateFlow(LiveAudioStatus.UNKNOWN)
    /** Live audio health from the recorder (visible mic indicator). */
    val audio: StateFlow<LiveAudioStatus> = _audio

    private val _audioLevel = MutableStateFlow(0f)
    /** Recorder-reported audio amplitude 0..1 (API-independent, from AudioStats). */
    val audioLevel: StateFlow<Float> = _audioLevel

    private val saver = MediaStoreSaver(context)
    private val thermal = BatteryThermalMonitor(context)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private data class FinalizeInfo(val ok: Boolean, val code: Int, val cause: Throwable?)

    private class Active(
        val recording: Recording,
        val file: File,
        val finalize: CompletableDeferred<FinalizeInfo>,
        val stopSignal: CompletableDeferred<AppError?>,
    )

    private var active: Active? = null
    private var monitorJob: Job? = null
    private var generation = 0

    val isBusy: Boolean
        get() = _state.value !is RecordingState.Idle &&
            _state.value !is RecordingState.Done &&
            _state.value !is RecordingState.Failed

    val isRecording: Boolean get() = _state.value is RecordingState.Recording

    data class Options(
        val micEnabled: Boolean,
        val resolution: VideoResolution,
        val countdownSeconds: Int,
        val scriptTitle: String,
    )

    fun record(bound: SingleCameraSession.BoundSingle, options: Options) {
        if (isBusy) return
        scope.launch { run(bound, options) }
    }

    private suspend fun run(bound: SingleCameraSession.BoundSingle, options: Options) {
        FileUtil.pruneStaging(context)
        val myGen = ++generation
        _state.value = RecordingState.Starting("Checking storage")
        val staging = FileUtil.stagingDir(context)
        if (!StorageGuard.preflight(staging, options.resolution).ready) {
            _state.value = RecordingState.Failed(AppError.StorageTooLow())
            return
        }
        val withAudio = options.micEnabled && hasAudioPermission()
        if (options.countdownSeconds > 0) {
            for (s in options.countdownSeconds downTo 1) {
                if (myGen != generation) { _state.value = RecordingState.Idle; return }
                _state.value = RecordingState.Countdown(s)
                delay(1000)
            }
        }
        if (myGen != generation) { _state.value = RecordingState.Idle; return }

        _state.value = RecordingState.Starting("Starting camera")
        val file = FileUtil.newStagingFile(context, "take")
        _audio.value = if (withAudio) LiveAudioStatus.UNKNOWN else LiveAudioStatus.DISABLED
        _audioLevel.value = 0f
        try {
            val finD = CompletableDeferred<FinalizeInfo>()
            val startD = CompletableDeferred<Unit>()
            val recording = startStream(bound, file, withAudio, startD, finD)
            val stopSignal = CompletableDeferred<AppError?>()
            active = Active(recording, file, finD, stopSignal)

            // If the camera never starts (HAL problem), don't hang forever.
            val started = withTimeoutOrNull(8_000) { startD.await() }
            if (started == null && !finD.isCompleted) {
                Logger.w("Record", "Start event never arrived; proceeding on timer")
            }
            _state.value = RecordingState.Recording(startedElapsedMs = SystemClock.elapsedRealtime())
            startMonitors(staging, stopSignal)

            // Wait for user stop, a safety stop, or CameraX finalizing on its own
            // (e.g. camera taken by another app) — none of these crash.
            val notice: AppError? = kotlinx.coroutines.selects.select {
                stopSignal.onAwait { it }
                finD.onAwait { info -> if (info.ok) null else AppError.RecordingFailed() }
            }

            active = null
            monitorJob?.cancel()
            _state.value = RecordingState.Stopping("Saving video")
            runCatching { recording.stop() }
            val fin = withTimeoutOrNull(20_000) { finD.await() }
            runCatching { recording.close() }
            if (fin?.ok != true || !file.exists() || file.length() == 0L) {
                // Some HALs report an error code but still produce a playable file;
                // keep it if it has content instead of throwing the take away.
                if (file.exists() && file.length() > 0L && fin != null &&
                    fin.code != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                ) {
                    Logger.w("Record", "Finalize error ${fin.code} but file has data; saving")
                } else {
                    FileUtil.deleteQuietly(file)
                    _state.value = RecordingState.Failed(AppError.RecordingFailed())
                    return
                }
            }
            val meta = VideoMetadata.of(file)
            val uri = withContext(Dispatchers.IO) { saver.saveVideo(file, displayName(options)) }
            FileUtil.deleteQuietly(file)
            _state.value = RecordingState.Done(uri, meta.durationMs, meta.sizeBytes, notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Record", "Recording failed", e)
            abort()
            _state.value = RecordingState.Failed(mapFailure(e))
        } finally {
            monitorJob?.cancel()
            _audioLevel.value = 0f
        }
    }

    /** User pressed stop. */
    fun requestStop() {
        active?.stopSignal?.complete(null)
    }

    /** Discard the countdown / take entirely. */
    fun requestCancel() {
        generation++
        val a = active
        if (a == null) {
            if (_state.value is RecordingState.Countdown || _state.value is RecordingState.Starting) {
                _state.value = RecordingState.Idle
            }
            return
        }
        a.stopSignal.complete(null)
    }

    /** Reset Done/Failed back to Idle once the UI has shown it. */
    fun consumeTerminal() {
        if (_state.value is RecordingState.Done || _state.value is RecordingState.Failed) {
            _state.value = RecordingState.Idle
        }
    }

    private fun startStream(
        bound: SingleCameraSession.BoundSingle,
        file: File,
        withAudio: Boolean,
        startD: CompletableDeferred<Unit>,
        finD: CompletableDeferred<FinalizeInfo>,
    ): Recording {
        val pending = bound.video.output.prepareRecording(
            context, FileOutputOptions.Builder(file).build()
        )
        if (withAudio) {
            pending.withAudioEnabled() // throws SecurityException without permission
        }
        return pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Logger.d("Record", "Started: ${file.name} audio=$withAudio")
                    startD.complete(Unit)
                    updateAudio(event.recordingStats.audioStats)
                }
                is VideoRecordEvent.Status -> updateAudio(event.recordingStats.audioStats)
                is VideoRecordEvent.Pause, is VideoRecordEvent.Resume -> Unit
                is VideoRecordEvent.Finalize -> {
                    Logger.i("Record", "Finalized: ${file.name} error=${event.hasError()} code=${event.error}")
                    startD.complete(Unit)
                    finD.complete(FinalizeInfo(!event.hasError(), event.error, event.cause))
                }
                else -> Unit
            }
        }
    }

    private fun updateAudio(stats: AudioStats) {
        _audioLevel.value = stats.audioAmplitude.toFloat().coerceIn(0f, 1f)
        _audio.value = when (stats.audioState) {
            AudioStats.AUDIO_STATE_ACTIVE -> LiveAudioStatus.ACTIVE
            AudioStats.AUDIO_STATE_DISABLED -> LiveAudioStatus.DISABLED
            AudioStats.AUDIO_STATE_SOURCE_SILENCED -> LiveAudioStatus.SILENCED
            AudioStats.AUDIO_STATE_ENCODER_ERROR -> LiveAudioStatus.ENCODER_ERROR
            AudioStats.AUDIO_STATE_SOURCE_ERROR -> LiveAudioStatus.SOURCE_ERROR
            else -> LiveAudioStatus.UNKNOWN
        }
    }

    private fun startMonitors(staging: File, stopSignal: CompletableDeferred<AppError?>) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(2000)
                if (StorageGuard.shouldStop(staging)) {
                    Logger.w("Record", "Storage critical — stopping safely")
                    stopSignal.complete(AppError.StorageStop())
                    return@launch
                }
                val status = thermal.thermalStatus()
                if (thermal.mustStop(status)) {
                    Logger.w("Record", "Thermal severe — stopping safely")
                    stopSignal.complete(AppError.ThermalWarning())
                    return@launch
                }
            }
        }
    }

    private fun abort() {
        val a = active
        active = null
        runCatching { a?.recording?.stop() }
        runCatching { a?.recording?.close() }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun displayName(options: Options): String {
        val slug = options.scriptTitle.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(' ', '_')
            .take(40)
            .ifBlank { "Take" }
        return "TP_${slug}_${TimeFormat.fileTimestamp()}.mp4"
    }

    private fun mapFailure(e: Exception): AppError = when (e) {
        is SecurityException -> AppError.PermissionRequired("microphone or camera")
        is IllegalStateException -> AppError.CameraBusy()
        else -> AppError.RecordingFailed()
    }

    fun release() {
        monitorJob?.cancel()
        abort()
    }
}
