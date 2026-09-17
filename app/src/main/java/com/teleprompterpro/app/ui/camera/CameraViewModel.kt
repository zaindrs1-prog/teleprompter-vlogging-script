package com.teleprompterpro.app.ui.camera

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.teleprompterpro.app.audio.MicDevice
import com.teleprompterpro.app.audio.MicProbeResult
import com.teleprompterpro.app.audio.MicrophoneMonitor
import com.teleprompterpro.app.audio.MicrophoneProbe
import com.teleprompterpro.app.camera.CameraCapabilityChecker
import com.teleprompterpro.app.camera.CameraFacingInfo
import com.teleprompterpro.app.camera.CameraSessionException
import com.teleprompterpro.app.camera.DeviceCapabilityReport
import com.teleprompterpro.app.camera.QualityAdvisor
import com.teleprompterpro.app.camera.SingleCameraSession
import com.teleprompterpro.app.reader.RemoteAction
import com.teleprompterpro.app.reader.ScrollMath
import com.teleprompterpro.app.reader.SpeechSyncEngine
import com.teleprompterpro.app.reader.VoiceSyncStatus
import com.teleprompterpro.app.reader.WordMatcher
import com.teleprompterpro.app.recording.BatteryThermalMonitor
import com.teleprompterpro.app.recording.LiveAudioStatus
import com.teleprompterpro.app.recording.RecordingController
import com.teleprompterpro.app.recording.RecordingState
import com.teleprompterpro.app.recording.StorageGuard
import com.teleprompterpro.app.script.ReaderSettings
import com.teleprompterpro.app.script.Script
import com.teleprompterpro.app.script.ScriptRepository
import com.teleprompterpro.app.settings.AppSettings
import com.teleprompterpro.app.settings.CameraFacing
import com.teleprompterpro.app.settings.FrameRate
import com.teleprompterpro.app.settings.OverlayPosition
import com.teleprompterpro.app.settings.ScrollMode
import com.teleprompterpro.app.settings.SettingsRepository
import com.teleprompterpro.app.settings.StabilizationMode
import com.teleprompterpro.app.settings.VideoResolution
import com.teleprompterpro.app.settings.VoiceLanguage
import com.teleprompterpro.app.util.AppError
import com.teleprompterpro.app.util.FileUtil
import com.teleprompterpro.app.util.Logger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface BindingState {
    data object Checking : BindingState
    data object Binding : BindingState
    data object Ready : BindingState
    data class Error(val error: AppError) : BindingState
}

/** Everything the camera + teleprompter screen renders, in one immutable snapshot. */
data class CameraUiState(
    // Camera
    val binding: BindingState = BindingState.Checking,
    val facing: CameraFacing = CameraFacing.REAR,
    val hasFront: Boolean = false,
    val hasRear: Boolean = false,
    val mirrorFrontPreview: Boolean = true,
    val torch: Boolean = false,
    val flashAvailable: Boolean = false,
    val zoom: Float = 1f,
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
    val exposureIndex: Int = 0,
    val exposureRange: IntRange = 0..0,
    val exposureStepEv: Float = 0f,
    val resolution: VideoResolution = VideoResolution.AUTO,
    val resolutionOptions: List<VideoResolution> = emptyList(),
    val frameRate: FrameRate = FrameRate.AUTO,
    val frameRateOptions: List<FrameRate> = listOf(FrameRate.AUTO),
    val stabilization: StabilizationMode = StabilizationMode.STANDARD,
    val stabilizationAvailable: Boolean = false,
    val activeQualityLabel: String = "",
    // Recording
    val recording: RecordingState = RecordingState.Idle,
    val countdownSeconds: Int = 3,
    val controlsExpanded: Boolean = true,
    val storageLine: String = "",
    val storageWarn: Boolean = false,
    val batteryPct: Int = -1,
    val thermalWarn: Boolean = false,
    // Microphone
    val micEnabled: Boolean = true,
    val micDevices: List<MicDevice> = listOf(MicDevice.SYSTEM_DEFAULT),
    val selectedMic: MicDevice = MicDevice.SYSTEM_DEFAULT,
    val micProbe: MicProbeResult? = null,
    val micProbing: Boolean = false,
    val idleMicLevel: Float = 0f,
    val liveAudio: LiveAudioStatus = LiveAudioStatus.UNKNOWN,
    val liveAudioLevel: Float = 0f,
    // Script / reader
    val script: Script? = null,
    val reader: ReaderSettings = ReaderSettings(),
    val overlayPosition: OverlayPosition = OverlayPosition.TOP,
    val progress: Float = 0f,
    val playing: Boolean = false,
    val voiceStatus: VoiceSyncStatus = VoiceSyncStatus.Off,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.ENGLISH,
    val voiceAvailable: Boolean = true,
    val matchedWord: Int = 0,
    val banner: AppError? = null,
) {
    val isRecording: Boolean get() = recording is RecordingState.Recording
    val isBusy: Boolean
        get() = recording !is RecordingState.Idle &&
            recording !is RecordingState.Done && recording !is RecordingState.Failed
    val words: Int get() = script?.wordCount ?: 0
    val totalMs: Long get() = ScrollMath.totalDurationMs(words, reader.wpm)
    val remainingMs: Long get() = ScrollMath.remainingMs(words, reader.wpm, progress)
    val elapsedMs: Long get() = ScrollMath.elapsedMs(words, reader.wpm, progress)
    val micLabel: String
        get() = when {
            !micEnabled -> "Mic off"
            isRecording -> when (liveAudio) {
                LiveAudioStatus.ACTIVE -> "${micProbe?.routed?.name ?: selectedMic.name} • OK"
                else -> liveAudio.label
            }
            micProbe != null && micProbe.requested.id == selectedMic.id -> micProbe.summary
            else -> selectedMic.name
        }
    val micWarning: Boolean
        get() = micEnabled && (
            (isRecording && liveAudio != LiveAudioStatus.ACTIVE && liveAudio != LiveAudioStatus.UNKNOWN) ||
                (micProbe != null && micProbe.requested.id == selectedMic.id && (micProbe.fellBack || !micProbe.hasSignal))
            )
}

/**
 * Owns the camera session, the recorder, the microphone checks and the
 * scroll engine. Survives rotation; the screen only attaches/detaches views.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val scriptRepo = ScriptRepository(app)
    private val checker = CameraCapabilityChecker(app)
    private val session = SingleCameraSession(app)
    private val controller = RecordingController(app)
    private val micMonitor = MicrophoneMonitor(app)
    private val micProbe = MicrophoneProbe(app)
    private val batteryThermal = BatteryThermalMonitor(app)
    private val speech = SpeechSyncEngine(app)

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui

    private var report: DeviceCapabilityReport? = null
    private var settings: AppSettings = AppSettings()
    private var boundOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var started = false
    private var scriptId = -1L
    private var scrollJob: Job? = null
    private var collapseJob: Job? = null
    private var matcher: WordMatcher? = null
    private var displayRotation = Surface.ROTATION_0
    private var saveReaderJob: Job? = null

    private fun update(f: (CameraUiState) -> CameraUiState) { _ui.value = f(_ui.value) }

    private val activeInfo: CameraFacingInfo?
        get() = if (ui.value.facing == CameraFacing.REAR) report?.rearCamera else report?.frontCamera

    // ------------------------------------------------------------ lifecycle

    fun start(scriptId: Long, owner: LifecycleOwner) {
        this.scriptId = scriptId
        if (started) {
            if (boundOwner !== owner && !ui.value.isBusy) bind(owner)
            return
        }
        started = true
        boundOwner = owner
        viewModelScope.launch {
            settings = settingsRepo.settings.first()
            update {
                it.copy(
                    facing = settings.defaultCamera,
                    mirrorFrontPreview = settings.mirrorFrontPreview,
                    micEnabled = settings.micEnabled,
                    resolution = settings.resolution,
                    frameRate = settings.frameRate,
                    stabilization = settings.stabilization,
                    countdownSeconds = settings.countdownSeconds,
                    overlayPosition = settings.overlayPosition,
                    voiceLanguage = settings.voiceLanguage,
                    voiceAvailable = speech.isAvailable(),
                )
            }
            launch { observeScript() }
            launch { observeRecording() }
            launch { observeAudio() }
            launch { observeIdleMic() }
            launch { observeMicDevices() }
            launch { observeVoice() }
            launch { pollHealth() }
            micProbe.startWatching()
            probeAndBind(owner)
        }
    }

    private suspend fun observeScript() {
        scriptRepo.script(scriptId).collect { s ->
            if (s == null) return@collect
            val first = ui.value.script == null
            update { it.copy(script = s, reader = if (first) s.settings else it.reader) }
            if (first) scriptRepo.setLastOpened(s.id)
            if (matcher == null || matcher?.tokens?.size != WordMatcher.tokenize(s.body).size) {
                matcher = WordMatcher(s.body).also { m -> m.reset(matchedWordFor(ui.value.progress, m.total)) }
            }
        }
    }

    private fun matchedWordFor(progress: Float, total: Int): Int = (progress * total).toInt().coerceIn(0, total)

    private suspend fun probeAndBind(owner: LifecycleOwner) {
        update { it.copy(binding = BindingState.Checking) }
        val r = try {
            checker.probe()
        } catch (e: Exception) {
            Logger.e("CameraVM", "Probe failed", e)
            update { it.copy(binding = BindingState.Error(AppError.CameraMissing())) }
            return
        }
        report = r
        if (!r.anyCamera) {
            update { it.copy(binding = BindingState.Error(AppError.CameraMissing())) }
            return
        }
        update {
            val facing = when {
                it.facing == CameraFacing.FRONT && !r.frontSupported -> CameraFacing.REAR
                it.facing == CameraFacing.REAR && !r.rearSupported -> CameraFacing.FRONT
                else -> it.facing
            }
            it.copy(hasFront = r.frontSupported, hasRear = r.rearSupported, facing = facing)
        }
        bind(owner)
    }

    private fun bind(owner: LifecycleOwner) {
        boundOwner = owner
        viewModelScope.launch {
            update { it.copy(binding = BindingState.Binding) }
            try {
                val info = activeInfo
                val spec = QualityAdvisor.resolve(info, ui.value.resolution, ui.value.frameRate)
                val bound = session.open(
                    owner,
                    SingleCameraSession.SessionSpec(
                        selector = if (ui.value.facing == CameraFacing.REAR) CameraSelector.DEFAULT_BACK_CAMERA
                        else CameraSelector.DEFAULT_FRONT_CAMERA,
                        resolution = spec.resolution,
                        fps = spec.fps,
                        stabilization = if (info?.supportsVideoStabilization == true) ui.value.stabilization else StabilizationMode.OFF,
                        targetRotation = displayRotation,
                    ),
                )
                previewView?.let { session.attachPreviewView(it) }
                val zoom = bound.camera.cameraInfo.zoomState.value
                val exp = bound.camera.cameraInfo.exposureState
                update {
                    it.copy(
                        binding = BindingState.Ready,
                        banner = null,
                        zoomRange = if (zoom == null) 1f..1f else zoom.minZoomRatio..zoom.maxZoomRatio,
                        zoom = zoom?.zoomRatio ?: 1f,
                        flashAvailable = bound.camera.cameraInfo.hasFlashUnit(),
                        exposureRange = exp.exposureCompensationRange.lower..exp.exposureCompensationRange.upper,
                        exposureStepEv = exp.exposureCompensationStep.toFloat(),
                        exposureIndex = exp.exposureCompensationIndex,
                        resolutionOptions = QualityAdvisor.supportedResolutions(info),
                        frameRateOptions = QualityAdvisor.supportedFrameRates(info),
                        stabilizationAvailable = info?.supportsVideoStabilization == true,
                        activeQualityLabel = "${spec.resolution.label} • ${spec.fps} FPS",
                        torch = false,
                    )
                }
                restartIdleMicMeter()
            } catch (e: CameraSessionException) {
                Logger.e("CameraVM", "Bind failed", e)
                update { it.copy(binding = BindingState.Error(e.error)) }
            } catch (e: Exception) {
                Logger.e("CameraVM", "Bind failed", e)
                update { it.copy(binding = BindingState.Error(AppError.CameraBusy())) }
            }
        }
    }

    fun retry() {
        val owner = boundOwner ?: return
        viewModelScope.launch { probeAndBind(owner) }
    }

    fun attachPreview(view: PreviewView) {
        previewView = view
        session.attachPreviewView(view)
    }

    fun detachPreview() {
        previewView = null
        session.detachPreview()
    }

    /** Called by the screen when the display rotates; locked while recording. */
    fun onDisplayRotation(rotation: Int) {
        displayRotation = rotation
        if (!ui.value.isBusy) session.updateTargetRotation(rotation)
    }

    // ---------------------------------------------------------------- camera

    fun flipCamera() {
        if (ui.value.isBusy) return
        val s = ui.value
        val next = if (s.facing == CameraFacing.REAR) CameraFacing.FRONT else CameraFacing.REAR
        if (next == CameraFacing.FRONT && !s.hasFront) return
        if (next == CameraFacing.REAR && !s.hasRear) return
        update { it.copy(facing = next) }
        boundOwner?.let { bind(it) }
    }

    fun toggleMirrorPreview() {
        val next = !ui.value.mirrorFrontPreview
        update { it.copy(mirrorFrontPreview = next) }
        viewModelScope.launch { settingsRepo.setMirrorFrontPreview(next) }
    }

    fun toggleTorch() {
        val bound = session.current() ?: return
        val next = !ui.value.torch
        session.setTorch(bound.camera, next)
        update { it.copy(torch = next) }
    }

    fun setZoom(ratio: Float) {
        val bound = session.current() ?: return
        session.setZoom(bound.camera, ratio)
        update { it.copy(zoom = ratio.coerceIn(it.zoomRange)) }
    }

    /** Manual brightness (exposure compensation). Works while recording too. */
    fun setExposure(index: Int) {
        val bound = session.current() ?: return
        val clamped = index.coerceIn(ui.value.exposureRange)
        session.setExposure(bound.camera, clamped)
        update { it.copy(exposureIndex = clamped) }
    }

    fun focus(view: PreviewView, x: Float, y: Float) {
        val bound = session.current() ?: return
        session.focusAt(bound.camera, view, x, y)
    }

    fun setQuality(resolution: VideoResolution, fps: FrameRate, stab: StabilizationMode) {
        if (ui.value.isBusy) return
        update { it.copy(resolution = resolution, frameRate = fps, stabilization = stab) }
        viewModelScope.launch {
            settingsRepo.setResolution(resolution)
            settingsRepo.setFrameRate(fps)
            settingsRepo.setStabilization(stab)
            boundOwner?.let { bind(it) }
        }
    }

    fun setCountdown(seconds: Int) {
        update { it.copy(countdownSeconds = seconds.coerceIn(0, 10)) }
        viewModelScope.launch { settingsRepo.setCountdown(seconds) }
    }

    fun setOverlayPosition(pos: OverlayPosition) {
        update { it.copy(overlayPosition = pos) }
        viewModelScope.launch { settingsRepo.setOverlayPosition(pos) }
    }

    // ------------------------------------------------------------ microphone

    private suspend fun observeMicDevices() {
        micProbe.devices.collect { devices ->
            val preferred = devices.firstOrNull { it.id == settings.preferredMicId }
            update { s ->
                val stillPresent = devices.any { it.id == s.selectedMic.id }
                val selected = when {
                    stillPresent -> s.selectedMic
                    preferred != null -> preferred
                    else -> MicDevice.SYSTEM_DEFAULT
                }
                var banner = s.banner
                if (!stillPresent && s.selectedMic.id != -1) {
                    // The chosen external mic vanished — say so instead of silently using the phone mic.
                    banner = AppError.MicFallback(s.selectedMic.name, "Phone microphone")
                }
                s.copy(micDevices = devices, selectedMic = selected, banner = banner,
                    micProbe = if (stillPresent) s.micProbe else null)
            }
        }
    }

    fun selectMic(device: MicDevice) {
        update { it.copy(selectedMic = device, micProbe = null) }
        viewModelScope.launch { settingsRepo.setPreferredMicId(device.id) }
        if (!ui.value.isBusy) testMic()
    }

    fun toggleMic() {
        if (ui.value.isBusy) return
        val next = !ui.value.micEnabled
        update { it.copy(micEnabled = next) }
        viewModelScope.launch { settingsRepo.setMicEnabled(next) }
        restartIdleMicMeter()
    }

    /** Opens the selected mic and verifies it is really delivering signal. */
    fun testMic() {
        if (ui.value.isBusy || ui.value.micProbing || !ui.value.micEnabled) return
        val device = ui.value.selectedMic
        viewModelScope.launch {
            micMonitor.stop()
            stopVoiceSync(keepMode = true)
            update { it.copy(micProbing = true) }
            val result = micProbe.probe(device)
            update {
                it.copy(
                    micProbing = false,
                    micProbe = result,
                    banner = when {
                        result.fellBack -> AppError.MicFallback(device.name, result.routed?.name ?: "Phone microphone")
                        !result.opened -> AppError.MicUnavailable()
                        !result.hasSignal && device.id != -1 -> AppError.MicUnavailable(
                            title = "No signal from ${device.name}",
                            message = "The microphone is connected but no audio is arriving. Check it is powered on / unmuted. Recording would be silent.",
                        )
                        else -> it.banner
                    },
                )
            }
            restartIdleMicMeter()
            if (ui.value.reader.scrollMode == ScrollMode.VOICE && ui.value.playing) startVoiceSync()
        }
    }

    private fun restartIdleMicMeter() {
        if (!ui.value.micEnabled || ui.value.isBusy || ui.value.binding !is BindingState.Ready ||
            ui.value.voiceStatus is VoiceSyncStatus.Listening || ui.value.voiceStatus is VoiceSyncStatus.Starting
        ) {
            micMonitor.stop()
            return
        }
        micMonitor.start(viewModelScope)
    }

    private suspend fun observeIdleMic() {
        micMonitor.level.collect { level -> update { it.copy(idleMicLevel = level) } }
    }

    private suspend fun observeAudio() {
        launchIn { controller.audio.collect { a -> update { it.copy(liveAudio = a) } } }
        controller.audioLevel.collect { l -> update { it.copy(liveAudioLevel = l) } }
    }

    private fun launchIn(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // ------------------------------------------------------------- recording

    fun toggleRecord() {
        if (ui.value.isRecording) stopRecording()
        else if (ui.value.recording is RecordingState.Countdown) cancelRecording()
        else startRecording()
    }

    fun startRecording() {
        val s = ui.value
        if (s.binding !is BindingState.Ready || s.isBusy) return
        val bound = session.current() ?: run { update { it.copy(banner = AppError.CameraBusy()) }; return }
        val battery = batteryThermal.batteryPercent()
        if (battery in 1..14 && !batteryThermal.isCharging()) {
            update { it.copy(banner = AppError.BatteryLow()) }
        }
        micMonitor.stop()
        val info = activeInfo
        val spec = QualityAdvisor.resolve(info, s.resolution, s.frameRate)
        // Bluetooth SCO mics need the link up so the recorder can use them.
        if (s.micEnabled && s.selectedMic.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            micProbe.enableBluetoothSco(true)
        }
        controller.record(
            bound,
            RecordingController.Options(
                micEnabled = s.micEnabled,
                resolution = spec.resolution,
                countdownSeconds = s.countdownSeconds,
                scriptTitle = s.script?.title ?: "Take",
            ),
        )
    }

    fun stopRecording() { controller.requestStop() }

    fun cancelRecording() {
        controller.requestCancel()
    }

    fun consumeTerminalState() {
        controller.consumeTerminal()
    }

    private suspend fun observeRecording() {
        controller.state.collect { state ->
            val prev = ui.value.recording
            update { it.copy(recording = state) }
            when (state) {
                is RecordingState.Recording -> {
                    // Script starts scrolling with the take; controls tuck away 2 s later.
                    if (prev !is RecordingState.Recording) {
                        if (ui.value.progress >= 0.999f) seekTo(0f)
                        play()
                        scheduleCollapse()
                    }
                }
                is RecordingState.Done, is RecordingState.Failed -> {
                    pause()
                    collapseJob?.cancel()
                    update { it.copy(controlsExpanded = true) }
                    micProbe.enableBluetoothSco(false)
                    val notice = (state as? RecordingState.Done)?.notice
                    if (notice != null) update { it.copy(banner = notice) }
                    restartIdleMicMeter()
                }
                is RecordingState.Idle -> restartIdleMicMeter()
                else -> Unit
            }
        }
    }

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = viewModelScope.launch {
            delay(2000)
            if (ui.value.isRecording) update { it.copy(controlsExpanded = false) }
        }
    }

    /** Tap on the preview while recording: show controls again, auto-hide after 4 s. */
    fun toggleControls() {
        val expanded = !ui.value.controlsExpanded
        update { it.copy(controlsExpanded = expanded) }
        collapseJob?.cancel()
        if (expanded && ui.value.isRecording) {
            collapseJob = viewModelScope.launch {
                delay(4000)
                if (ui.value.isRecording) update { it.copy(controlsExpanded = false) }
            }
        }
    }

    fun dismissBanner() { update { it.copy(banner = null) } }

    private suspend fun pollHealth() {
        val app = getApplication<Application>()
        while (coroutineContext.isActive) {
            val staging = FileUtil.stagingDir(app)
            val res = ui.value.resolution
            val preflight = StorageGuard.preflight(staging, res)
            val pct = batteryThermal.batteryPercent()
            val thermal = batteryThermal.thermalStatus()
            update {
                it.copy(
                    storageLine = "${StorageGuard.minutesAvailable(staging, res)} min of space • ${preflight.statusLine}",
                    storageWarn = StorageGuard.shouldWarn(staging),
                    batteryPct = pct,
                    thermalWarn = batteryThermal.shouldWarn(thermal) || batteryThermal.mustStop(thermal),
                )
            }
            delay(3000)
        }
    }

    // ------------------------------------------------------------ reader

    fun setFont(sp: Float) = updateReader { it.copy(fontSp = sp.coerceIn(14f, 96f)) }
    fun setWpm(wpm: Int) = updateReader { it.copy(wpm = wpm.coerceIn(ScrollMath.MIN_WPM, ScrollMath.MAX_WPM)) }
    fun setOpacity(o: Float) = updateReader { it.copy(backgroundOpacity = o.coerceIn(0f, 1f)) }
    fun setMirrorText(m: Boolean) = updateReader { it.copy(mirrorText = m) }

    /** Set pace by target total duration; WPM is derived so ETA stays exact. */
    fun setTargetDuration(ms: Long) {
        val words = ui.value.words
        if (words <= 0) return
        setWpm(ScrollMath.wpmForDuration(words, ms))
    }

    fun setScrollMode(mode: ScrollMode) {
        updateReader { it.copy(scrollMode = mode) }
        if (mode == ScrollMode.VOICE) {
            if (ui.value.playing) startVoiceSync()
        } else {
            stopVoiceSync(keepMode = true)
        }
    }

    fun setVoiceLanguage(lang: VoiceLanguage) {
        update { it.copy(voiceLanguage = lang) }
        viewModelScope.launch { settingsRepo.setVoiceLanguage(lang) }
        if (ui.value.voiceStatus !is VoiceSyncStatus.Off) { stopVoiceSync(keepMode = true); startVoiceSync() }
    }

    private fun updateReader(f: (ReaderSettings) -> ReaderSettings) {
        update { it.copy(reader = f(it.reader)) }
        val id = scriptId
        val settings = ui.value.reader
        saveReaderJob?.cancel()
        saveReaderJob = viewModelScope.launch {
            delay(400)
            scriptRepo.updateSettings(id, settings)
        }
    }

    fun togglePlay() { if (ui.value.playing) pause() else play() }

    fun play() {
        if (ui.value.playing) return
        if (ui.value.progress >= 0.999f) seekTo(0f)
        update { it.copy(playing = true) }
        if (ui.value.reader.scrollMode == ScrollMode.VOICE) startVoiceSync()
        scrollJob?.cancel()
        scrollJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive && ui.value.playing) {
                delay(16)
                val now = SystemClock.elapsedRealtime()
                val dt = now - last
                last = now
                val s = ui.value
                when (s.reader.scrollMode) {
                    ScrollMode.TIMED -> {
                        val next = ScrollMath.advance(s.progress, dt, s.words, s.reader.wpm)
                        update { it.copy(progress = next) }
                        if (next >= 1f) { pause(); break }
                    }
                    ScrollMode.VOICE -> {
                        // Glide toward the last matched word; never move on volume.
                        val m = matcher ?: continue
                        val target = ScrollMath.progressForWord(m.cursor, m.total)
                        if (target > s.progress) {
                            val step = ((target - s.progress) * 0.12f).coerceAtLeast(0.0005f)
                            update { it.copy(progress = (it.progress + step).coerceAtMost(target), matchedWord = m.cursor) }
                        }
                    }
                }
            }
        }
    }

    fun pause() {
        scrollJob?.cancel()
        scrollJob = null
        update { it.copy(playing = false) }
        stopVoiceSync(keepMode = true)
    }

    fun seekTo(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        update { it.copy(progress = p) }
        matcher?.let { m -> m.reset(matchedWordFor(p, m.total)); update { it.copy(matchedWord = m.cursor) } }
    }

    /** Nudge by seconds of reading time (remote / buttons). */
    fun skip(seconds: Int) {
        val s = ui.value
        val total = s.totalMs
        if (total <= 0) return
        seekTo(s.progress + seconds * 1000f / total)
    }

    fun restart() = seekTo(0f)

    /** Handles Bluetooth remote / media / keyboard keys. Returns true if consumed. */
    fun onRemote(action: RemoteAction): Boolean {
        when (action) {
            RemoteAction.PLAY_PAUSE -> togglePlay()
            RemoteAction.REWIND -> skip(-5)
            RemoteAction.FORWARD -> skip(5)
            RemoteAction.SPEED_UP -> setWpm(ui.value.reader.wpm + 10)
            RemoteAction.SLOW_DOWN -> setWpm(ui.value.reader.wpm - 10)
            RemoteAction.RESTART -> restart()
            RemoteAction.TOGGLE_RECORD -> toggleRecord()
        }
        return true
    }

    // ------------------------------------------------------------ voice sync

    private var voiceJob: Job? = null

    private fun startVoiceSync() {
        if (!ui.value.voiceAvailable) {
            fallbackToTimed("No speech recognizer is installed on this phone.")
            return
        }
        micMonitor.stop()
        matcher?.let { m -> m.reset(matchedWordFor(ui.value.progress, m.total)) }
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            speech.hypotheses.collect { text ->
                val m = matcher ?: return@collect
                if (m.feed(text)) update { it.copy(matchedWord = m.cursor) }
            }
        }
        speech.start(ui.value.voiceLanguage)
    }

    private fun stopVoiceSync(keepMode: Boolean) {
        voiceJob?.cancel()
        voiceJob = null
        speech.stop()
        if (!keepMode) updateReader { it.copy(scrollMode = ScrollMode.TIMED) }
    }

    private suspend fun observeVoice() {
        speech.status.collect { status ->
            update { it.copy(voiceStatus = status) }
            if (status is VoiceSyncStatus.Unavailable && ui.value.reader.scrollMode == ScrollMode.VOICE) {
                fallbackToTimed(status.reason)
            }
            if (status is VoiceSyncStatus.Off) restartIdleMicMeter()
        }
    }

    /** Voice sync can't run right now: switch to timed scrolling and say why. */
    private fun fallbackToTimed(reason: String) {
        voiceJob?.cancel()
        voiceJob = null
        updateReader { it.copy(scrollMode = ScrollMode.TIMED) }
        update { it.copy(banner = AppError.VoiceSyncUnavailable(reason)) }
        // The timed loop is already running inside play(); it reads the mode live.
    }

    override fun onCleared() {
        scrollJob?.cancel()
        voiceJob?.cancel()
        speech.stop()
        micMonitor.stop()
        micProbe.stopWatching()
        micProbe.enableBluetoothSco(false)
        if (controller.isRecording) controller.requestStop()
        session.close()
        controller.release()
        super.onCleared()
    }
}
