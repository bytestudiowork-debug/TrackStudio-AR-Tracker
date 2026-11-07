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

    fun validate(lines: Sequence<String>): Report {
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return emptyReport(false)
        val header = iterator.next().removePrefix("\uFEFF").split(',')
        if (header != EXPECTED_HEADER) return emptyReport(false)

        var total = 0L
        var frames = 0L
        var imu = 0L
        var gyroDelta = 0L
        var accelDelta = 0L
        var malformed = 0L
        var invalid = 0L
        var outOfOrder = 0L
        var unsupported = 0L
        val lastByStream = HashMap<String, Long>()

        while (iterator.hasNext()) {
            val row = iterator.next()
            if (row.isBlank()) continue
            total++
            val fields = row.split(',')
            if (fields.size != EXPECTED_COLUMN_COUNT) {
                malformed++
                continue
            }
            val type = fields[0]
            val stream = when (type) {
                "FRAME" -> "FRAME"
                "IMU" -> "IMU:${fields[8]}"
                "IMU_DELTA" -> "IMU_DELTA"
                "ACCEL_DELTA" -> "ACCEL_DELTA"
                else -> {
                    unsupported++
                    null
                }
            }
            if (stream == null) continue

            val timestamp = fields[1].toLongOrNull()
            if (timestamp == null || timestamp <= 0L) {
                invalid++
                continue
            }
            when (type) {
                "FRAME" -> frames++
                "IMU" -> imu++
                "IMU_DELTA" -> gyroDelta++
                "ACCEL_DELTA" -> accelDelta++
            }
            val previous = lastByStream[stream]
            if (previous != null && timestamp <= previous) {
                outOfOrder++
            } else {
                lastByStream[stream] = timestamp
            }
        }

        return Report(
            headerValid = true,
            totalRows = total,
            frameRows = frames,
            imuRows = imu,
            gyroDeltaRows = gyroDelta,
            accelerationDeltaRows = accelDelta,
            malformedRows = malformed,
            invalidTimestamps = invalid,
            outOfOrderTimestamps = outOfOrder,
            unsupportedRows = unsupported,
            isValid = malformed == 0L && invalid == 0L &&
                outOfOrder == 0L && unsupported == 0L
        )
    }

    private fun emptyReport(headerValid: Boolean) = Report(
        headerValid, 0, 0, 0, 0, 0, 0, 0, 0, 0, false
    )
}
