package com.teleprompterpro.app.recording

import android.net.Uri
import com.teleprompterpro.app.util.AppError

/** UI-observable state of the capture pipeline (adapted from creator-cam). */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Countdown(val secondsLeft: Int) : RecordingState
    data class Starting(val step: String) : RecordingState
    data class Recording(val startedElapsedMs: Long) : RecordingState
    data class Stopping(val step: String) : RecordingState
    data class Done(
        val uri: Uri,
        val durationMs: Long,
        val sizeBytes: Long,
        /** Set when the take was stopped by a safety monitor rather than the user. */
        val notice: AppError? = null,
    ) : RecordingState
    data class Failed(val error: AppError) : RecordingState
}

/** Live microphone status while recording, derived from CameraX AudioStats. */
enum class LiveAudioStatus(val label: String) {
    UNKNOWN("Audio: starting…"),
    ACTIVE("Audio: OK"),
    SILENCED("Audio silenced by system"),
    SOURCE_ERROR("Microphone error — no audio!"),
    ENCODER_ERROR("Audio encoder error"),
    DISABLED("Audio disabled"),
}
