package com.teleprompterpro.app.recording

import com.teleprompterpro.app.settings.VideoResolution
import com.teleprompterpro.app.util.TimeFormat
import java.io.File

/**
 * Preflight + in-recording storage safety (ported from creator-cam, single
 * stream). We would rather stop cleanly than corrupt a take.
 */
object StorageGuard {

    /** Below this we warn; recording may continue. */
    const val WARN_BYTES: Long = 1L * 1024 * 1024 * 1024

    /** Below this we stop safely instead of corrupting the file. */
    const val CRITICAL_BYTES: Long = 250L * 1024 * 1024

    /** Conservative bytes-per-minute (video + audio + container overhead). */
    fun bytesPerMinute(resolution: VideoResolution): Long = when (resolution) {
        VideoResolution.UHD_4K -> 450L * 1024 * 1024
        VideoResolution.FULL_HD_1080P -> 180L * 1024 * 1024
        VideoResolution.HD_720P -> 90L * 1024 * 1024
        VideoResolution.AUTO -> 180L * 1024 * 1024
    }

    data class Preflight(
        val availableBytes: Long,
        val estimatedTenMinutesBytes: Long,
        val ready: Boolean,
        val statusLine: String,
    )

    fun preflight(stagingDir: File, resolution: VideoResolution): Preflight {
        val available = stagingDir.usableSpace
        val estimate = (bytesPerMinute(resolution) * 1.2 * 10).toLong()
        val ready = available >= CRITICAL_BYTES * 2 && available >= estimate / 5
        val status = if (ready) {
            "Ready — 10 min ≈ ${TimeFormat.bytes(estimate)} • free ${TimeFormat.bytes(available)}"
        } else {
            "Low — 10 min ≈ ${TimeFormat.bytes(estimate)} • free ${TimeFormat.bytes(available)}"
        }
        return Preflight(available, estimate, ready, status)
    }

    /** Minutes of recording that fit in the available space. */
    fun minutesAvailable(stagingDir: File, resolution: VideoResolution): Int =
        ((stagingDir.usableSpace - CRITICAL_BYTES).coerceAtLeast(0L) / bytesPerMinute(resolution)).toInt()

    fun shouldWarn(dir: File): Boolean = dir.usableSpace < WARN_BYTES
    fun shouldStop(dir: File): Boolean = dir.usableSpace < CRITICAL_BYTES
}
