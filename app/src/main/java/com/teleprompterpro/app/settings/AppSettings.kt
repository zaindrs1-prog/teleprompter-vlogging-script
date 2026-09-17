package com.teleprompterpro.app.settings

/**
 * Persisted user preferences (creator-cam pattern). Everything here is a hint:
 * the camera engine intersects these choices with real hardware capabilities.
 */
data class AppSettings(
    // Camera
    val defaultCamera: CameraFacing = CameraFacing.REAR,
    val resolution: VideoResolution = VideoResolution.AUTO,
    val frameRate: FrameRate = FrameRate.AUTO,
    val stabilization: StabilizationMode = StabilizationMode.STANDARD,
    val mirrorFrontPreview: Boolean = true,
    // Audio
    val micEnabled: Boolean = true,
    /** AudioDeviceInfo id of the preferred mic, or -1 for system default. */
    val preferredMicId: Int = -1,
    // Reader defaults (per-script values override these)
    val defaultFontSp: Float = 30f,
    val defaultWpm: Int = 140,
    val defaultOpacity: Float = 0.55f,
    val defaultScrollMode: ScrollMode = ScrollMode.TIMED,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.ENGLISH,
    val countdownSeconds: Int = 3,
    val overlayPosition: OverlayPosition = OverlayPosition.TOP,
    // App
    val theme: AppTheme = AppTheme.DARK,
)

enum class CameraFacing(val label: String) { REAR("Rear"), FRONT("Front") }

enum class VideoResolution(val label: String, val longEdge: Int) {
    AUTO("Auto (best supported)", 0),
    HD_720P("720p", 1280),
    FULL_HD_1080P("1080p", 1920),
    UHD_4K("4K", 3840),
}

enum class FrameRate(val label: String, val fps: Int) {
    AUTO("Auto", 0),
    FPS_24("24 FPS", 24),
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
}

enum class StabilizationMode(val label: String) { OFF("Off"), STANDARD("Standard") }

enum class ScrollMode(val label: String) {
    TIMED("Timed"),
    VOICE("Voice sync"),
}

enum class VoiceLanguage(val label: String, val tag: String) {
    ENGLISH("English", "en-US"),
    URDU("Urdu (اردو)", "ur-PK"),
}

enum class OverlayPosition(val label: String) { TOP("Top"), CENTER("Center"), BOTTOM("Bottom") }

enum class AppTheme(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }
