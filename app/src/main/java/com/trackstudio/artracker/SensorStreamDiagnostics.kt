package com.trackstudio.artracker

/**
 * Lightweight, allocation-free-per-sample diagnostics for one Android sensor stream.
 * Timestamps are expected to be monotonic nanoseconds from the sensor clock.
 */
internal class SensorStreamDiagnostics(
    private val longGapThresholdNs: Long = 50_000_000L
) {
    data class Snapshot(
        val acceptedSamples: Long,
        val invalidSamples: Long,
        val outOfOrderSamples: Long,
        val meanIntervalMs: Double,
        val maxIntervalMs: Double,
        val estimatedRateHz: Double,
        val longGapCount: Long,
        val lastTimestampNs: Long
    )

    private var accepted = 0L
    private var invalid = 0L
    private var outOfOrder = 0L
    private var intervalCount = 0L
    private var intervalSumNs = 0.0
    private var maxIntervalNs = 0L
    private var longGaps = 0L
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L

    @Synchronized
    fun reset() {
        accepted = 0L
        invalid = 0L
        outOfOrder = 0L
        intervalCount = 0L
        intervalSumNs = 0.0
        maxIntervalNs = 0L
        longGaps = 0L
        firstTimestampNs = 0L
        lastTimestampNs = 0L
    }

    /** Returns true only when this sample is valid and strictly newer than the last one. */
    @Synchronized
    fun record(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        if (timestampNs <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            invalid++
            return false
        }
        if (lastTimestampNs > 0L && timestampNs <= lastTimestampNs) {
            outOfOrder++
            return false
        }
        if (firstTimestampNs == 0L) firstTimestampNs = timestampNs
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

    @Synchronized
    fun snapshot(): Snapshot {
        val meanNs = if (intervalCount > 0L) intervalSumNs / intervalCount else 0.0
        val rate = if (meanNs > 0.0) 1_000_000_000.0 / meanNs else 0.0
        return Snapshot(
            acceptedSamples = accepted,
            invalidSamples = invalid,
            outOfOrderSamples = outOfOrder,
            meanIntervalMs = meanNs / 1_000_000.0,
            maxIntervalMs = maxIntervalNs / 1_000_000.0,
            estimatedRateHz = rate,
            longGapCount = longGaps,
            lastTimestampNs = lastTimestampNs
        )
    }
}
