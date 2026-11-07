package com.trackstudio.artracker

import kotlin.math.max

/** Timestamp-domain-aware gyro integration between consecutive camera frames. */
internal class ImuPreintegrator(
    private val maxExtrapolationNs: Long = 50_000_000L,
    private val historyNs: Long = 2_000_000_000L,
    private val maxSamples: Int = 4096
) {
    data class Delta(
        val valid: Boolean,
        val rotationXRad: Double = 0.0,
        val rotationYRad: Double = 0.0,
        val rotationZRad: Double = 0.0,
        val intervalSeconds: Double = 0.0,
        val sampleCount: Int = 0
    )

    private data class Sample(
        val timestampNs: Long,
        val x: Double,
        val y: Double,
        val z: Double
    )

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun reset() {
        samples.clear()
    }

    @Synchronized
    fun addGyroscope(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (timestampNs <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val last = samples.lastOrNull()
        if (last != null && timestampNs <= last.timestampNs) return
        samples.addLast(Sample(timestampNs, x.toDouble(), y.toDouble(), z.toDouble()))
        val cutoff = timestampNs - historyNs
        while (samples.size > 2 && samples.first().timestampNs < cutoff) samples.removeFirst()
        while (samples.size > maxSamples) samples.removeFirst()
    }

    @Synchronized
    fun integrate(startNs: Long, endNs: Long): Delta {
        if (startNs <= 0L || endNs <= startNs || samples.size < 2) return Delta(false)

        val all = samples.toList()
        val beforeIndex = all.indexOfLast { it.timestampNs <= startNs }
        if (beforeIndex < 0) return Delta(false)

        val before = all[beforeIndex]
        val afterStart = all.getOrNull(beforeIndex + 1)
        val start = when {
            before.timestampNs == startNs -> before
            afterStart != null -> interpolate(before, afterStart, startNs)
            else -> return Delta(false)
        }

        val points = ArrayList<Sample>()
        points.add(start)
        for (sample in all) {
            if (sample.timestampNs > startNs && sample.timestampNs < endNs) points.add(sample)
        }

        val beforeEndIndex = all.indexOfLast { it.timestampNs <= endNs }
        if (beforeEndIndex < 0) return Delta(false)
        val beforeEnd = all[beforeEndIndex]
        val afterEnd = all.getOrNull(beforeEndIndex + 1)
        val end = when {
            beforeEnd.timestampNs == endNs -> beforeEnd
            afterEnd != null -> interpolate(beforeEnd, afterEnd, endNs)
            endNs - beforeEnd.timestampNs <= maxExtrapolationNs ->
                beforeEnd.copy(timestampNs = endNs)
            else -> return Delta(false)
        }
        if (end.timestampNs > points.last().timestampNs) points.add(end)
        if (points.size < 2) return Delta(false)

        var rx = 0.0
        var ry = 0.0
        var rz = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = (b.timestampNs - a.timestampNs) / 1_000_000_000.0
            if (dt <= 0.0 || dt > maxExtrapolationNs / 1_000_000_000.0 * 2.0) {
                return Delta(false)
            }
            rx += (a.x + b.x) * 0.5 * dt
            ry += (a.y + b.y) * 0.5 * dt
            rz += (a.z + b.z) * 0.5 * dt
        }
        return Delta(
            valid = true,
            rotationXRad = rx,
            rotationYRad = ry,
            rotationZRad = rz,
            intervalSeconds = (endNs - startNs) / 1_000_000_000.0,
            sampleCount = max(0, points.size - 1)
        )
    }

    private fun interpolate(a: Sample, b: Sample, timestampNs: Long): Sample {
        val span = (b.timestampNs - a.timestampNs).toDouble()
        if (span <= 0.0) return a.copy(timestampNs = timestampNs)
        val t = ((timestampNs - a.timestampNs) / span).coerceIn(0.0, 1.0)
        return Sample(
            timestampNs,
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }
}
