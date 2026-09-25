package com.teleprompterpro.app.util

/**
 * Specific, user-actionable error model (ported from creator-cam, dual-camera
 * cases removed). The UI renders [title] + [message].
 */
sealed class AppError(
    open val title: String,
    open val message: String,
    open val recoverable: Boolean = true,
) {
    data class CameraBusy(
        override val title: String = "Camera Busy",
        override val message: String =
            "Another app is currently using the camera. Close it and try again.",
    ) : AppError(title, message)

    data class CameraMissing(
        override val title: String = "Camera Not Found",
        override val message: String =
            "TeleprompterPro could not find a usable camera on this device.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class MicUnavailable(
        override val title: String = "Microphone Unavailable",
        override val message: String =
            "Please close other apps using the microphone, or continue without audio.",
    ) : AppError(title, message)

    data class MicFallback(
        val wanted: String,
        val actual: String,
        override val title: String = "Using a different microphone",
        override val message: String =
            "\"$wanted\" is not delivering audio. Recording is using \"$actual\" instead.",
    ) : AppError(title, message)

    data class PermissionRequired(
        val permissionLabel: String,
        override val title: String = "Permission Required",
        override val message: String =
            "TeleprompterPro needs $permissionLabel to record. You can enable it in Android settings.",
    ) : AppError(title, message)

    data class StorageTooLow(
        override val title: String = "Storage Too Low",
        override val message: String =
            "Free more storage before recording.",
    ) : AppError(title, message)

    data class BatteryLow(
        override val title: String = "Battery Low",
        override val message: String =
            "Battery is below 15%. A long recording may stop unexpectedly.",
    ) : AppError(title, message)

    data class ThermalWarning(
        override val title: String = "Device Too Hot",
        override val message: String =
            "Your device is overheating. Recording has been stopped safely and your video was saved.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class StorageStop(
        override val title: String = "Storage Almost Full",
        override val message: String =
            "Recording was stopped safely before storage ran out. Your video was saved.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class RecordingFailed(
        override val title: String = "Recording Failed",
        override val message: String =
            "The camera stopped unexpectedly. Anything captured so far has been saved if possible.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class ExportFailed(
        override val title: String = "Export Failed",
        override val message: String =
            "The video could not be saved to the gallery.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class VoiceSyncUnavailable(
        val reason: String,
        override val title: String = "Voice sync paused",
        override val message: String = "$reason Scrolling switched to timed mode.",
    ) : AppError(title, message)
}
