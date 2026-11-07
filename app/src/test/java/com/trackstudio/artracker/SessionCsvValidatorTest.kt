package com.trackstudio.artracker

import org.junit.Assert.*
import org.junit.Test

class SessionCsvValidatorTest {
    private val header = (1..27).joinToString(",") {
        when (it) { 1 -> "record_type"; 2 -> "timestamp_ns"; else -> "column$it" }
    }
    private fun row(type: String, timestamp: Long, sensor: String = ""): String {
        val f = MutableList(27) { "" }
        f[0] = type; f[1] = timestamp.toString(); f[8] = sensor
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

    @Test fun rejectsWrongHeader() {
        val r = SessionCsvValidator.validate(sequenceOf("wrong,header"))
        assertFalse(r.headerValid); assertFalse(r.isValid)
    }
}
