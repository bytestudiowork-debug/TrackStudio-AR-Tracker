package com.trackstudio.artracker

import org.junit.Assert.*
import org.junit.Test

class SessionReplayAnalyzerTest {
    private val header = SessionCsvValidator.EXPECTED_HEADER.joinToString(",")
    private fun row(type: String, timestamp: Long, sensor: String = "", clock: String = "REALTIME"): String {
        val fields = MutableList(27) { "" }
        fields[0] = type
        fields[1] = timestamp.toString()
        fields[8] = sensor
        fields[17] = clock
        when (type) {
            "FRAME" -> {
                fields[2] = "1920"
                fields[3] = "1080"
            }
            "IMU" -> {
                fields[9] = "0.0"
                fields[10] = "0.0"
                fields[11] = "9.81"
            }
        }
        return fields.joinToString(",")
    }

    @Test fun summarizesStreamsAndConfirmsReplayReadinessForCompleteClockAlignedCapture() {
        val report = SessionReplayAnalyzer.analyze(sequenceOf(
            header,
            row("FRAME", 1_000_000_000), row("IMU", 1_001_000_000, "4"),
            row("IMU", 1_002_000_000, "1"), row("FRAME", 1_033_000_000),
            row("IMU", 1_011_000_000, "4"), row("IMU", 1_012_000_000, "1"),
            row("FRAME", 1_066_000_000), row("IMU", 1_021_000_000, "4"),
            row("IMU", 1_022_000_000, "1")
        ))
        assertTrue(report.validation.isValid)
        assertEquals(3L, report.frameStream.samples)
        assertEquals(30.3, report.frameStream.estimatedRateHz, 0.1)
        assertEquals(33.0, report.frameStream.maximumGapMs, 0.1)
        assertEquals("REALTIME", report.cameraTimestampSource)
        assertTrue(report.replayReadyForVio)
        assertTrue(report.warnings.isEmpty())
    }

    @Test fun refusesReplayReadinessWhenClockOrSensorCoverageIsMissing() {
        val report = SessionReplayAnalyzer.analyze(sequenceOf(
            header, row("FRAME", 100), row("FRAME", 200, clock = "UNKNOWN")
        ))
        assertFalse(report.replayReadyForVio)
        assertTrue(report.warnings.any { it.contains("Gyroscope") })
        assertTrue(report.warnings.any { it.contains("clock") })
    }

    @Test fun detectsUnsupportedRecordTypesAndRejectsHeaderDrift() {
        val unsupported = SessionCsvValidator.validate(sequenceOf(header, row("FUTURE_TYPE", 100)))
        assertEquals(1L, unsupported.unsupportedRows)
        assertFalse(unsupported.isValid)
        val changedHeader = SessionCsvValidator.validate(sequenceOf(header.replace("frame_width", "width")))
        assertFalse(changedHeader.headerValid)
    }

    @Test fun reportsLongSensorGapsWithoutCallingSessionCalibrated() {
        val report = SessionReplayAnalyzer.analyze(sequenceOf(
            header,
            row("FRAME", 1_000_000_000), row("FRAME", 1_033_000_000),
            row("IMU", 1_000_000_000, "4"), row("IMU", 1_100_000_001, "4"),
            row("IMU", 1_000_000_000, "1"), row("IMU", 1_100_000_001, "1")
        ))
        assertFalse(report.replayReadyForVio)
        assertTrue(report.warnings.any { it.contains("Gyroscope stream") })
        assertTrue(report.warnings.any { it.contains("Accelerometer stream") })
    }

    @Test fun consumesOneShotSequenceWithoutMaterializingOrReopeningIt() {
        var iteratorRequested = false
        val oneShot = Sequence {
            check(!iteratorRequested) { "Analyzer requested a second iterator" }
            iteratorRequested = true
            listOf(
                header,
                row("FRAME", 1_000_000_000),
                row("FRAME", 1_033_000_000),
                row("IMU", 1_000_000_000, "4"),
                row("IMU", 1_010_000_000, "4"),
                row("IMU", 1_000_000_000, "1"),
                row("IMU", 1_010_000_000, "1")
            ).iterator()
        }
        val report = SessionReplayAnalyzer.analyze(oneShot)
        assertTrue(iteratorRequested)
        assertEquals(2L, report.frameStream.samples)
        assertTrue(report.validation.isValid)
    }

    @Test fun rejectsMixedCameraTimestampDomains() {
        val report = SessionReplayAnalyzer.analyze(sequenceOf(
            header, row("FRAME", 1_000_000_000, clock = "REALTIME"),
            row("FRAME", 1_033_000_000, clock = "UNKNOWN")
        ))
        assertEquals("UNKNOWN", report.cameraTimestampSource)
        assertFalse(report.replayReadyForVio)
    }
}
