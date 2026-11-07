package com.trackstudio.artracker

/**
 * Computes deterministic replay diagnostics from a completed capture CSV.
 * Consumes the input exactly once and retains only stream counters, not the capture rows.
 * This summarizes recorded measurements; it does not reconstruct poses or certify calibration.
 */
internal object SessionReplayAnalyzer {
    data class StreamSummary(
        val samples: Long,
        val firstTimestampNs: Long?,
        val lastTimestampNs: Long?,
        val durationMs: Double,
        val estimatedRateHz: Double,
        val maximumGapMs: Double
    )

    data class Report(
        val validation: SessionCsvValidator.Report,
        val frameStream: StreamSummary,
        val gyroStream: StreamSummary,
        val accelerometerStream: StreamSummary,
        val cameraTimestampSource: String,
        val replayReadyForVio: Boolean,
        val warnings: List<String>
    )

    private data class MutableStream(
        var count: Long = 0,
        var first: Long? = null,
        var last: Long? = null,
        var maxGapNs: Long = 0
    ) {
        fun add(timestamp: Long) {
            val previous = last
            if (first == null) first = timestamp
            if (previous != null && timestamp > previous) {
                maxGapNs = maxOf(maxGapNs, timestamp - previous)
            }
            last = timestamp
            count++
        }

        fun snapshot(): StreamSummary {
            val start = first
            val end = last
            val durationNs = if (start != null && end != null) (end - start).coerceAtLeast(0) else 0
            val rate = if (durationNs > 0 && count > 1) {
                (count - 1) * 1_000_000_000.0 / durationNs
            } else 0.0
            return StreamSummary(
                count, start, end, durationNs / 1_000_000.0, rate, maxGapNs / 1_000_000.0
            )
        }
    }

    fun analyze(lines: Sequence<String>): Report {
        val validator = SessionCsvValidator.Accumulator()
        val frames = MutableStream()
        val gyros = MutableStream()
        val accelerometers = MutableStream()
        val timestampSources = linkedSetOf<String>()

        for (line in lines) {
            val fields = validator.accept(line) ?: continue
            val timestamp = fields[1].toLongOrNull() ?: continue
            when (fields[0]) {
                "FRAME" -> {
                    frames.add(timestamp)
                    fields[17].takeIf { it.isNotBlank() }?.let(timestampSources::add)
                }
                "IMU" -> when (fields[8]) {
                    "4" -> gyros.add(timestamp) // Android Sensor.TYPE_GYROSCOPE
                    "1" -> accelerometers.add(timestamp) // Android Sensor.TYPE_ACCELEROMETER
                }
            }
        }

        val validation = validator.finish()
        val frameSummary = frames.snapshot()
        val gyroSummary = gyros.snapshot()
        val accelSummary = accelerometers.snapshot()
        val clock = timestampSources.singleOrNull() ?: "UNKNOWN"
        val warnings = buildList {
            if (!validation.isValid) add("CSV integrity checks failed; repair or recapture before replay.")
            if (frameSummary.samples < 2) add("At least two camera frames are required for a timed replay.")
            if (gyroSummary.samples < 2) add("Gyroscope coverage is missing or too short.")
            if (accelSummary.samples < 2) add("Accelerometer coverage is missing or too short.")
            if (clock != "REALTIME") add("Camera timestamp clock is not confirmed compatible with Android sensor timestamps.")
            if (frameSummary.maximumGapMs > 100.0) add("Camera stream contains a gap longer than 100 ms.")
            if (gyroSummary.maximumGapMs > 50.0) add("Gyroscope stream contains a gap longer than 50 ms.")
            if (accelSummary.maximumGapMs > 50.0) add("Accelerometer stream contains a gap longer than 50 ms.")
        }
        return Report(
            validation, frameSummary, gyroSummary, accelSummary, clock,
            replayReadyForVio = warnings.isEmpty(), warnings = warnings
        )
    }
}
