package com.teleprompterpro.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import com.teleprompterpro.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real single-camera capability probe (adapted from creator-cam; the dual /
 * concurrent-camera oracles were removed because this app never uses them).
 *
 * Facing is resolved from characteristics — ID "0"/"1" semantics are never
 * assumed. Resolutions, FPS ranges, stabilization, exposure range and flash
 * are read directly from the HAL so the UI never offers a fake option.
 */
class CameraCapabilityChecker(private val context: Context) {

    suspend fun probe(): DeviceCapabilityReport = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val front = findFacing(manager, CameraMetadata.LENS_FACING_FRONT, Facing.FRONT)
        val rear = findFacing(manager, CameraMetadata.LENS_FACING_BACK, Facing.REAR)
        val mic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        Logger.i("Caps", "front=${front?.cameraId} rear=${rear?.cameraId} mic=$mic")
        DeviceCapabilityReport(frontCamera = front, rearCamera = rear, microphonePresent = mic)
    }

    private fun findFacing(
        manager: CameraManager,
        lensFacing: Int,
        facing: Facing,
    ): CameraFacingInfo? {
        for (id in runCatching { manager.cameraIdList }.getOrDefault(emptyArray())) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != lensFacing) continue
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width >= 640 }
                ?.sortedByDescending { it.width * it.height }
                .orEmpty()
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { IntRange(it.lower, it.upper) }
                .orEmpty()
            val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            return CameraFacingInfo(
                cameraId = id,
                facing = facing,
                videoSizes = sizes,
                fpsRanges = fpsRanges,
                opticalStabilization =
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                videoStabilizationModes =
                    chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?: intArrayOf(),
                maxDigitalZoom =
                    chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                exposureRange = if (aeRange != null) aeRange.lower..aeRange.upper else 0..0,
                exposureStepEv = aeStep?.toFloat() ?: 0f,
            ).also {
                Logger.d("Caps", "facing=$facing id=$id sizes=${sizes.take(4)} fps=$fpsRanges ae=$aeRange")
            }
        }
        return null
    }
}
