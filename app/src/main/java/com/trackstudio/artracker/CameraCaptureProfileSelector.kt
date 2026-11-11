package com.trackstudio.artracker

import kotlin.math.abs

internal data class CameraStreamCandidate(
    val width: Int,
    val height: Int,
    /** Zero means the HAL did not report a minimum duration. */
    val minFrameDurationNs: Long
)

internal data class CameraFpsRangeCandidate(val lower: Int, val upper: Int)

/**
 * Chooses conservative camera settings from capabilities actually advertised by Camera2.
 * A reported minimum frame duration is a hardware limit, not a promise of delivered FPS.
 */
internal object CameraCaptureProfileSelector {
    private const val TARGET_WIDTH = 640
    private const val TARGET_HEIGHT = 480
    private const val TARGET_FRAME_DURATION_NS = 33_333_333L
    private const val MAX_REQUESTED_FPS = 30

    fun chooseAnalysisStream(candidates: List<CameraStreamCandidate>): CameraStreamCandidate? {
        val valid = candidates.filter {
            it.width > 0 && it.height > 0 && it.minFrameDurationNs >= 0L
        }
        if (valid.isEmpty()) return null

        val bounded = valid.filter { it.width <= TARGET_WIDTH && it.height <= TARGET_HEIGHT }
        val pool = bounded.ifEmpty { valid }
        val canMeetTarget = pool.filter {
            it.minFrameDurationNs == 0L || it.minFrameDurationNs <= TARGET_FRAME_DURATION_NS
        }

        return if (canMeetTarget.isNotEmpty()) {
            canMeetTarget.sortedWith(
                compareByDescending<CameraStreamCandidate> { it.width.toLong() * it.height }
                    .thenBy { abs(it.width.toDouble() / it.height - 4.0 / 3.0) }
            ).first()
        } else {
            // If no candidate can reach 30 FPS, prefer the stream with the lowest advertised
            // frame duration rather than selecting a larger but slower image.
            pool.sortedWith(
                compareBy<CameraStreamCandidate> { it.minFrameDurationNs }
                    .thenByDescending { it.width.toLong() * it.height }
            ).first()
        }
    }

    fun chooseFpsRange(candidates: List<CameraFpsRangeCandidate>): CameraFpsRangeCandidate? =
        candidates.asSequence()
            .filter { it.lower > 0 && it.upper >= it.lower && it.upper <= MAX_REQUESTED_FPS }
            .sortedWith(
                compareByDescending<CameraFpsRangeCandidate> { it.upper }
                    .thenByDescending { it.lower }
            )
            .firstOrNull()
}
