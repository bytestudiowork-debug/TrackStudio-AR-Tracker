package com.trackstudio.artracker

/** Structural and timestamp-order validator for the capture CSV (not a VIO accuracy test). */
internal object SessionCsvValidator {
    const val EXPECTED_COLUMN_COUNT = 27
    val EXPECTED_HEADER = listOf(
        "record_type", "timestamp_ns", "frame_width", "frame_height",
        "features_detected", "features_tracked", "mean_flow_x_px", "mean_flow_y_px",
        "sensor_type", "vector_x", "vector_y", "vector_z",
        "delta_rotation_x_rad", "delta_rotation_y_rad", "delta_rotation_z_rad",
        "interval_ms", "imu_samples", "camera_timestamp_source",
        "delta_velocity_x_mps", "delta_velocity_y_mps", "delta_velocity_z_mps",
        "delta_position_x_m", "delta_position_y_m", "delta_position_z_m",
        "gravity_x_mps2", "gravity_y_mps2", "gravity_z_mps2"
    )

    data class Report(
        val headerValid: Boolean,
        val totalRows: Long,
        val frameRows: Long,
        val imuRows: Long,
        val gyroDeltaRows: Long,
        val accelerationDeltaRows: Long,
        val malformedRows: Long,
        val invalidTimestamps: Long,
        val outOfOrderTimestamps: Long,
        val unsupportedRows: Long,
        val isValid: Boolean
    )

    /** Stateful, constant-memory validator shared with the replay analyzer. */
    internal class Accumulator {
        private var headerSeen = false
        var headerValid = false
            private set
        private var total = 0L
        private var frames = 0L
        private var imu = 0L
        private var gyroDelta = 0L
        private var accelDelta = 0L
        private var malformed = 0L
        private var invalid = 0L
        private var outOfOrder = 0L
        private var unsupported = 0L
        private val lastByStream = HashMap<String, Long>()

        /** Returns fields only for a structurally usable row. */
        fun accept(line: String): List<String>? {
            if (!headerSeen) {
                headerSeen = true
                headerValid = line.removePrefix("\uFEFF").split(',') == EXPECTED_HEADER
                return null
            }
            if (!headerValid || line.isBlank()) return null
            total++
            val fields = line.split(',')
            if (fields.size != EXPECTED_COLUMN_COUNT) {
                malformed++
                return null
            }

            // Validate the timestamp before sensor/type-specific checks so a row
            // with multiple defects is consistently classified as a bad timestamp.
            val timestamp = fields[1].toLongOrNull()
            if (timestamp == null || timestamp <= 0L) {
                invalid++
                return null
            }

            val type = fields[0]
            val stream = when (type) {
                "FRAME" -> "FRAME"
                "IMU" -> {
                    if (fields[8] != "1" && fields[8] != "4") {
                        unsupported++
                        return null
                    }
                    "IMU:${fields[8]}"
                }
                "IMU_DELTA" -> "IMU_DELTA"
                "ACCEL_DELTA" -> "ACCEL_DELTA"
                else -> {
                    unsupported++
                    return null
                }
            }
            when (type) {
                "FRAME" -> {
                    if ((fields[2].toIntOrNull() ?: 0) <= 0 ||
                        (fields[3].toIntOrNull() ?: 0) <= 0
                    ) {
                        malformed++
                        return null
                    }
                    frames++
                }
                "IMU" -> {
                    // Basalt consumes the raw sensor vectors; never treat blank,
                    // NaN or infinite values as valid measurements.
                    if ((9..11).any { index ->
                            fields[index].toDoubleOrNull()?.isFinite() != true
                        }
                    ) {
                        malformed++
                        return null
                    }
                    imu++
                }
                "IMU_DELTA" -> gyroDelta++
                "ACCEL_DELTA" -> accelDelta++
            }
            val previous = lastByStream[stream]
            if (previous != null && timestamp <= previous) {
                outOfOrder++
            } else {
                lastByStream[stream] = timestamp
            }
            return fields
        }

        fun finish(): Report {
            if (!headerSeen || !headerValid) return emptyReport(headerValid)
            return Report(
                headerValid, total, frames, imu, gyroDelta, accelDelta,
                malformed, invalid, outOfOrder, unsupported,
                malformed == 0L && invalid == 0L && outOfOrder == 0L && unsupported == 0L
            )
        }
    }

    fun validate(lines: Sequence<String>): Report {
        val accumulator = Accumulator()
        lines.forEach(accumulator::accept)
        return accumulator.finish()
    }

    private fun emptyReport(headerValid: Boolean) =
        Report(headerValid, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)
}
