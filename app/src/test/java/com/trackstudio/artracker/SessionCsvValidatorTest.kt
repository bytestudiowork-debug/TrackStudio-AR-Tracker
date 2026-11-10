package com.trackstudio.artracker

import org.junit.Assert.*
import org.junit.Test

class SessionCsvValidatorTest {
    private val header = SessionCsvValidator.EXPECTED_HEADER.joinToString(",")
    private fun row(type: String, timestamp: Long, sensor: String = ""): String {
        val f = MutableList(27) { "" }
        f[0] = type
        f[1] = timestamp.toString()
        f[8] = sensor
        when (type) {
            "FRAME" -> {
                f[2] = "1920"
                f[3] = "1080"
            }
            "IMU" -> {
                f[9] = "0.0"
                f[10] = "0.0"
                f[11] = "9.81"
            }
        }
        return f.joinToString(",")
    }

    @Test fun acceptsWellFormedInterleavedSensorStreams() {
        val r = SessionCsvValidator.validate(sequenceOf(
            header, row("FRAME",100), row("IMU",110,"4"), row("IMU",105,"1"),
            row("FRAME",120), row("IMU",130,"4")
        ))
        assertTrue(r.headerValid); assertTrue(r.isValid)
        assertEquals(2L, r.frameRows); assertEquals(3L, r.imuRows)
    }

    @Test fun detectsOutOfOrderFrameTimestamps() {
        val r = SessionCsvValidator.validate(sequenceOf(header,row("FRAME",200),row("FRAME",190)))
        assertFalse(r.isValid); assertEquals(1L,r.outOfOrderTimestamps)
    }

    @Test fun detectsMalformedRowsAndInvalidTimestamps() {
        val r = SessionCsvValidator.validate(sequenceOf(header,"FRAME,100",row("IMU",0)))
        assertEquals(1L,r.malformedRows); assertEquals(1L,r.invalidTimestamps)
        assertFalse(r.isValid)
    }

    @Test fun rejectsMissingFrameDimensionsAndNonFiniteRawImuVectors() {
        val missingDimensions = row("FRAME", 100).split(",").toMutableList().also {
            it[2] = ""
            it[3] = ""
        }.joinToString(",")
        val nonFiniteImu = row("IMU", 110, "4").split(",").toMutableList().also {
            it[10] = "NaN"
        }.joinToString(",")
        val r = SessionCsvValidator.validate(sequenceOf(header, missingDimensions, nonFiniteImu))
        assertEquals(2L, r.malformedRows)
        assertFalse(r.isValid)
    }

    @Test fun acceptsLegacyImuRowsWithOmittedTrailingEmptyColumns() {
        fun legacyImu(timestamp: Long, sensor: String): String {
            val fields = row("IMU", timestamp, sensor).split(",").toMutableList()
            fields[17] = "UNKNOWN"
            return fields.take(18).joinToString(",")
        }
        val r = SessionCsvValidator.validate(sequenceOf(
            header, legacyImu(100, "1"), legacyImu(110, "4")
        ))
        assertTrue(r.headerValid)
        assertTrue(r.isValid)
        assertEquals(2L, r.imuRows)
        assertEquals(0L, r.malformedRows)
    }

    @Test fun rejectsRowsWithMoreColumnsThanTheSchema() {
        val tooWide = row("IMU", 100, "4") + ",unexpected"
        val r = SessionCsvValidator.validate(sequenceOf(header, tooWide))
        assertEquals(1L, r.malformedRows)
        assertFalse(r.isValid)
    }

    @Test fun rejectsWrongHeader() {
        val r = SessionCsvValidator.validate(sequenceOf("wrong,header"))
        assertFalse(r.headerValid); assertFalse(r.isValid)
    }
}
