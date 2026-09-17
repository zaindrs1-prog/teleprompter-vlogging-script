package com.teleprompterpro.app.camera

import android.util.Size
import androidx.camera.video.Quality
import com.teleprompterpro.app.settings.FrameRate
import com.teleprompterpro.app.settings.VideoResolution

/**
 * Adapted from creator-cam: the dual-camera intersection logic is gone; what
 * remains clamps the user's preference to what the *active* lens can really
 * do, and lists only supported resolutions for the picker.
 */
object QualityAdvisor {

    /** Ordered fallback chain offered to CameraX [androidx.camera.video.Recorder]. */
    fun qualityChain(resolution: VideoResolution): List<Quality> = when (resolution) {
        VideoResolution.UHD_4K -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        VideoResolution.FULL_HD_1080P -> listOf(Quality.FHD, Quality.HD, Quality.SD)
        VideoResolution.HD_720P -> listOf(Quality.HD, Quality.SD)
        VideoResolution.AUTO -> listOf(Quality.FHD, Quality.UHD, Quality.HD, Quality.SD)
    }

    private val ordered = listOf(
        VideoResolution.HD_720P,
        VideoResolution.FULL_HD_1080P,
        VideoResolution.UHD_4K,
    )

    private val areas = mapOf(
        VideoResolution.HD_720P to 1280 * 720,
        VideoResolution.FULL_HD_1080P to 1920 * 1080,
        VideoResolution.UHD_4K to 3840 * 2160,
    )

    /** Resolutions the lens actually supports (largest recorder size ≥ target). */
    fun supportedResolutions(info: CameraFacingInfo?): List<VideoResolution> {
        val maxArea = info?.largestVideoSize?.let { it.width * it.height } ?: (1280 * 720)
        val list = ordered.filter { (areas[it] ?: 0) <= maxArea }
        return if (list.isEmpty()) listOf(VideoResolution.HD_720P) else list
    }

    fun supportedFrameRates(info: CameraFacingInfo?): List<FrameRate> {
        val max = info?.maxFps ?: 30
        return listOf(FrameRate.AUTO) + FrameRate.entries.filter { it.fps in 1..max }
    }

    fun resolve(
        info: CameraFacingInfo?,
        wantResolution: VideoResolution,
        wantFps: FrameRate,
    ): ResolvedCaptureSpec {
        val maxArea = info?.largestVideoSize?.let { it.width * it.height } ?: (1280 * 720)
        val resolution = when (wantResolution) {
            VideoResolution.AUTO ->
                if (maxArea >= 1920 * 1080) VideoResolution.FULL_HD_1080P else VideoResolution.HD_720P
            else -> wantResolution
        }.let { clampResolution(it, maxArea) }
        val maxFps = info?.maxFps ?: 30
        val fps = when (wantFps) {
            FrameRate.AUTO -> if (maxFps >= 30) 30 else maxFps.coerceAtLeast(15)
            else -> wantFps.fps.coerceAtMost(maxFps)
        }
        return ResolvedCaptureSpec(resolution, fps, maxArea, maxFps)
    }

    private fun clampResolution(want: VideoResolution, maxArea: Int): VideoResolution {
        var current = want
        while ((areas[current] ?: 0) > maxArea) {
            val idx = ordered.indexOf(current)
            if (idx <= 0) break
            current = ordered[idx - 1]
        }
        return current
    }

    fun sizeLabel(size: Size): String {
        val long = maxOf(size.width, size.height)
        return when {
            long >= 3840 -> "4K"
            long >= 1920 -> "1080p"
            long >= 1280 -> "720p"
            long >= 960 -> "540p"
            else -> "${size.width}×${size.height}"
        }
    }
}

data class ResolvedCaptureSpec(
    val resolution: VideoResolution,
    val fps: Int,
    val ceilingArea: Int,
    val ceilingFps: Int,
)
