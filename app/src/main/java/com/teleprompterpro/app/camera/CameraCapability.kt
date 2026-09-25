package com.teleprompterpro.app.camera

import android.util.Size

/**
 * Result of the real hardware capability probe (ported from creator-cam,
 * concurrent-camera fields removed). Nothing here is guessed: every flag
 * comes from CameraManager / CameraCharacteristics.
 */
data class DeviceCapabilityReport(
    val frontCamera: CameraFacingInfo?,
    val rearCamera: CameraFacingInfo?,
    val microphonePresent: Boolean,
) {
    val frontSupported: Boolean get() = frontCamera != null
    val rearSupported: Boolean get() = rearCamera != null
    val anyCamera: Boolean get() = frontSupported || rearSupported
}

data class CameraFacingInfo(
    val cameraId: String,
    val facing: Facing,
    /** All video-capable sizes for RECORDER-class outputs, largest first. */
    val videoSizes: List<Size>,
    /** AE target FPS ranges reported by the HAL. */
    val fpsRanges: List<IntRange>,
    val opticalStabilization: Boolean,
    val videoStabilizationModes: IntArray,
    val maxDigitalZoom: Float,
    val flashAvailable: Boolean,
    /** Exposure compensation index range from CONTROL_AE_COMPENSATION_RANGE. */
    val exposureRange: IntRange,
    /** Exposure compensation step in EV (numerator/denominator). */
    val exposureStepEv: Float,
) {
    val largestVideoSize: Size? get() = videoSizes.firstOrNull()
    val supportsVideoStabilization: Boolean get() = videoStabilizationModes.any { it != 0 }
    val maxFps: Int get() = fpsRanges.maxOfOrNull { it.last } ?: 30
    val supportsManualExposure: Boolean get() = exposureRange.first < exposureRange.last
}

enum class Facing { FRONT, REAR }
