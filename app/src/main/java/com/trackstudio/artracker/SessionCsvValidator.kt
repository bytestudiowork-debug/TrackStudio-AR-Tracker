package com.trackstudio.artracker

/** Structural and timestamp-order validator for the capture CSV (not a VIO accuracy test). */
internal object SessionCsvValidator {
    const val EXPECTED_COLUMN_COUNT = 27
    data class Report(
        val headerValid: Boolean, val totalRows: Long, val frameRows: Long, val imuRows: Long,
        val gyroDeltaRows: Long, val accelerationDeltaRows: Long, val malformedRows: Long,
        val invalidTimestamps: Long, val outOfOrderTimestamps: Long, val isValid: Boolean
    )

    fun validate(lines: Sequence<String>): Report {
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return emptyReport(false)
        val header = iterator.next().split(',')
        val headerValid = header.size == EXPECTED_COLUMN_COUNT &&
            header.firstOrNull() == "record_type" && header.getOrNull(1) == "timestamp_ns"
        if (!headerValid) return emptyReport(false)

        var total = 0L; var frames = 0L; var imu = 0L
        var gyroDelta = 0L; var accelDelta = 0L; var malformed = 0L
        var invalid = 0L; var outOfOrder = 0L
        val lastByStream = HashMap<String, Long>()
        while (iterator.hasNext()) {
            val row = iterator.next()
            if (row.isBlank()) continue
            total++
            val fields = row.split(',')
            if (fields.size != EXPECTED_COLUMN_COUNT) { malformed++; continue }
            val type = fields[0]
            val timestamp = fields[1].toLongOrNull()
            if (timestamp == null || timestamp <= 0L) { invalid++; continue }
            val stream = when (type) {
                "FRAME" -> "FRAME"
                "IMU" -> "IMU:${fields[8]}"
                "IMU_DELTA" -> "IMU_DELTA"
                "ACCEL_DELTA" -> "ACCEL_DELTA"
                else -> null
            }
            when (type) {
                "FRAME" -> frames++
                "IMU" -> imu++
                "IMU_DELTA" -> gyroDelta++
                "ACCEL_DELTA" -> accelDelta++
            }
            if (stream != null) {
                val previous = lastByStream[stream]
                if (previous != null && timestamp <= previous) outOfOrder++
                else lastByStream[stream] = timestamp
            }
        }
        return Report(
            true, total, frames, imu, gyroDelta, accelDelta, malformed, invalid, outOfOrder,
            malformed == 0L && invalid == 0L && outOfOrder == 0L
        )
    }

    private fun emptyReport(headerValid: Boolean) =
        Report(headerValid, 0, 0, 0, 0, 0, 0, 0, 0, false)
}
