package com.trackstudio.artracker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BasaltReplayPreflightTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsMatchingCompleteSessionOnlyWhenCalibrationGateIsComplete() {
        val csv = temporaryFolder.newFile("session.csv")
        val archive = File(temporaryFolder.root, "session.basalt.frames")
        writeValidSession(csv, archive)

        val calibrated = BasaltReplayPreflight.CalibrationGate(
            cameraModelValidatedForAnalysisStream = true,
            cameraToImuExtrinsicsCalibrated = true,
            gyroBiasAndNoiseCalibrated = true,
            accelerometerBiasAndNoiseCalibrated = true
        )
        val accepted = BasaltReplayPreflight.inspect(csv, archive, calibrated)
        assertTrue(accepted.frameTimestampsMatch)
        assertTrue(accepted.calibrationComplete)
        assertTrue(accepted.readyForNativeVio)

        val uncalibrated = calibrated.copy(cameraToImuExtrinsicsCalibrated = false)
        val rejected = BasaltReplayPreflight.inspect(csv, archive, uncalibrated)
        assertTrue(rejected.frameTimestampsMatch)
        assertFalse(rejected.calibrationComplete)
        assertFalse(rejected.readyForNativeVio)
    }

    @Test
    fun rejectsArchiveWhoseFrameTimestampsDifferFromCsv() {
        val csv = temporaryFolder.newFile("mismatch.csv")
        val archive = File(temporaryFolder.root, "mismatch.basalt.frames")
        writeValidSession(csv, archive, archiveFrameTimestamps = listOf(1_000_000_000L, 1_033_333_334L))

        val calibrated = BasaltReplayPreflight.CalibrationGate(true, true, true, true)
        val report = BasaltReplayPreflight.inspect(csv, archive, calibrated)
        assertFalse(report.frameTimestampsMatch)
        assertFalse(report.readyForNativeVio)
    }

    private fun writeValidSession(
        csv: File,
        archive: File,
        archiveFrameTimestamps: List<Long> = listOf(1_000_000_000L, 1_033_333_333L)
    ) {
        val frameTimestamps = listOf(1_000_000_000L, 1_033_333_333L)
        val lines = mutableListOf(SessionCsvValidator.EXPECTED_HEADER.joinToString(","))
        lines += row("IMU", 990_000_000L, sensorType = 4)
        lines += row("IMU", 991_000_000L, sensorType = 1)
        lines += row("FRAME", frameTimestamps[0], width = 2, height = 2)
        lines += row("IMU", 1_010_000_000L, sensorType = 4)
        lines += row("IMU", 1_011_000_000L, sensorType = 1)
        lines += row("FRAME", frameTimestamps[1], width = 2, height = 2)
        lines += row("IMU", 1_040_000_000L, sensorType = 4)
        lines += row("IMU", 1_041_000_000L, sensorType = 1)
        csv.writeText(lines.joinToString("\n"))

        val writer = BasaltFrameArchiveWriter(archive)
        archiveFrameTimestamps.forEach { timestamp ->
            assertTrue(writer.offer(timestamp, 2, 2, byteArrayOf(1, 2, 3, 4)))
        }
        assertTrue(writer.finish().complete)
    }

    private fun row(
        type: String,
        timestampNs: Long,
        sensorType: Int? = null,
        width: Int? = null,
        height: Int? = null
    ): String {
        val fields = MutableList(SessionCsvValidator.EXPECTED_COLUMN_COUNT) { "" }
        fields[0] = type
        fields[1] = timestampNs.toString()
        if (sensorType != null) {
            fields[8] = sensorType.toString()
            fields[9] = "0.1"
            fields[10] = "0.2"
            fields[11] = "0.3"
        }
        if (width != null && height != null) {
            fields[2] = width.toString()
            fields[3] = height.toString()
            fields[17] = "REALTIME"
        }
        return fields.joinToString(",")
    }
}
