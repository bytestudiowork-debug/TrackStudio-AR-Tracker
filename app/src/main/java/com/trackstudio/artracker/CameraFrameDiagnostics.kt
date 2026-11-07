package com.trackstudio.artracker

import java.nio.ByteBuffer

/** Validates ImageReader timestamps independently of the IMU clock. */
internal class CameraFrameDiagnostics(private val longGapThresholdNs: Long = 100_000_000L) {
    data class Snapshot(
        val acceptedFrames: Long, val invalidTimestamps: Long, val outOfOrderTimestamps: Long,
        val meanIntervalMs: Double, val maximumIntervalMs: Double, val estimatedRateHz: Double,
        val longGapCount: Long, val lastTimestampNs: Long
    )
    private var accepted = 0L
    private var invalid = 0L
    private var outOfOrder = 0L
    private var intervalCount = 0L
    private var intervalSumNs = 0.0
    private var maxIntervalNs = 0L
    private var longGaps = 0L
    private var lastTimestampNs = 0L

    @Synchronized fun reset() {
        accepted = 0L; invalid = 0L; outOfOrder = 0L
        intervalCount = 0L; intervalSumNs = 0.0; maxIntervalNs = 0L
        longGaps = 0L; lastTimestampNs = 0L
    }

    @Synchronized fun record(timestampNs: Long): Boolean {
        if (timestampNs <= 0L) { invalid++; return false }
        if (lastTimestampNs > 0L && timestampNs <= lastTimestampNs) {
            outOfOrder++
            return false
        }
        if (lastTimestampNs > 0L) {
            val interval = timestampNs - lastTimestampNs
            intervalCount++
            intervalSumNs += interval.toDouble()
            if (interval > maxIntervalNs) maxIntervalNs = interval
            if (interval > longGapThresholdNs) longGaps++
        }
        lastTimestampNs = timestampNs
        accepted++
        return true
    }

    @Synchronized fun snapshot(): Snapshot {
        val meanNs = if (intervalCount > 0L) intervalSumNs / intervalCount else 0.0
        return Snapshot(
            accepted, invalid, outOfOrder, meanNs / 1_000_000.0,
            maxIntervalNs / 1_000_000.0,
            if (meanNs > 0.0) 1_000_000_000.0 / meanNs else 0.0,
            longGaps, lastTimestampNs
        )
    }
}

internal data class PackedLumaFrame(val width: Int, val height: Int, val pixels: ByteArray)

/** Copies the Image crop rectangle while respecting Y-plane row and pixel strides. */
internal object LumaFramePacker {
    fun pack(
        buffer: ByteBuffer, imageWidth: Int, imageHeight: Int, rowStride: Int, pixelStride: Int,
        cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int
    ): PackedLumaFrame {
        require(imageWidth > 0 && imageHeight > 0 && rowStride > 0 && pixelStride > 0)
        require(cropLeft >= 0 && cropTop >= 0 && cropRight <= imageWidth && cropBottom <= imageHeight)
        require(cropRight > cropLeft && cropBottom > cropTop)
        val width = cropRight - cropLeft
        val height = cropBottom - cropTop
        val outputSize = width.toLong() * height.toLong()
        require(outputSize <= Int.MAX_VALUE) { "Cropped image is too large" }
        val base = buffer.position()
        val last = base.toLong() + (cropBottom - 1L) * rowStride +
            (cropRight - 1L) * pixelStride
        require(last < buffer.limit().toLong()) {
            "Camera luma plane buffer is shorter than its stride/crop metadata"
        }
        val source = buffer.duplicate()
        val output = ByteArray(outputSize.toInt())
        var destination = 0
        for (y in cropTop until cropBottom) {
            val rowStart = base + y * rowStride
            for (x in cropLeft until cropRight) output[destination++] =
                source.get(rowStart + x * pixelStride)
        }
        return PackedLumaFrame(width, height, output)
    }
}

internal data class CameraAnalysisStreamMetadata(
    val width: Int, val height: Int, val imageFormat: Int,
    val cropLeft: Int, val cropTop: Int, val cropRight: Int, val cropBottom: Int,
    val rowStride: Int, val pixelStride: Int
)
