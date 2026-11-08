package com.trackstudio.artracker

import java.io.BufferedReader
import java.io.File
import java.io.IOException

/**
 * Cross-checks the recorded camera archive and raw sensor CSV before a session is
 * handed to the native Basalt adapter. This is an ingestion gate, not a VIO runner.
 */
internal object BasaltReplayPreflight {
    data class CalibrationGate(
        val cameraModelValidatedForAnalysisStream: Boolean,
        val cameraToImuExtrinsicsCalibrated: Boolean,
        val gyroBiasAndNoiseCalibrated: Boolean,
        val accelerometerBiasAndNoiseCalibrated: Boolean
    ) {
        val complete: Boolean
            get() = cameraModelValidatedForAnalysisStream &&
                cameraToImuExtrinsicsCalibrated &&
                gyroBiasAndNoiseCalibrated &&
                accelerometerBiasAndNoiseCalibrated
    }

    data class Report(
        val csv: SessionReplayAnalyzer.Report,
        val archive: BasaltFrameArchiveReader.Summary?,
        val frameTimestampsMatch: Boolean,
        val calibrationComplete: Boolean,
        val readyForNativeVio: Boolean,
        val warnings: List<String>
    )

    @Throws(IOException::class)
    fun inspect(csvFile: File, frameArchiveFile: File, calibration: CalibrationGate): Report {
        require(csvFile.isFile) { "Session CSV does not exist" }
        require(frameArchiveFile.isFile) { "Basalt frame archive does not exist" }

        val csvReport = csvFile.bufferedReader().use { reader ->
            SessionReplayAnalyzer.analyze(reader.lineSequence())
        }
        val warnings = csvReport.warnings.toMutableList()
        var archiveSummary: BasaltFrameArchiveReader.Summary? = null
        var timestampsMatch = false

        try {
            val comparison = compareFrameTimestamps(csvFile, frameArchiveFile)
            archiveSummary = comparison.first
            timestampsMatch = comparison.second
            if (archiveSummary.framesDropped != 0L) {
                warnings += "The frame archive dropped ${archiveSummary.framesDropped} camera frames."
            }
            if (archiveSummary.frameCount != csvReport.frameStream.samples) {
                warnings += "CSV frame count and archived frame count do not match."
            }
            if (!timestampsMatch) {
                warnings += "CSV camera timestamps and archived frame timestamps do not match."
            }
        } catch (error: IOException) {
            warnings += "Frame archive validation failed: ${error.message ?: "invalid archive"}"
        }

        if (!calibration.cameraModelValidatedForAnalysisStream) {
            warnings += "Camera intrinsics/distortion have not been validated for the exact cropped analysis stream."
        }
        if (!calibration.cameraToImuExtrinsicsCalibrated) {
            warnings += "Camera-to-IMU rotation and translation calibration is missing."
        }
        if (!calibration.gyroBiasAndNoiseCalibrated) {
            warnings += "Gyroscope bias, noise density and random-walk calibration is missing."
        }
        if (!calibration.accelerometerBiasAndNoiseCalibrated) {
            warnings += "Accelerometer bias, noise density and random-walk calibration is missing."
        }

        val archiveValid = archiveSummary != null &&
            archiveSummary.framesDropped == 0L &&
            archiveSummary.frameCount == csvReport.frameStream.samples &&
            timestampsMatch
        val ready = csvReport.replayReadyForVio && archiveValid && calibration.complete
        return Report(
            csv = csvReport,
            archive = archiveSummary,
            frameTimestampsMatch = timestampsMatch,
            calibrationComplete = calibration.complete,
            readyForNativeVio = ready,
            warnings = warnings.distinct()
        )
    }

    private fun compareFrameTimestamps(
        csvFile: File,
        archiveFile: File
    ): Pair<BasaltFrameArchiveReader.Summary, Boolean> {
        fun advanceCsvFrame(source: BufferedReader): Long? {
            while (true) {
                val line = source.readLine() ?: return null
                val fields = line.split(',')
                if (fields.size == SessionCsvValidator.EXPECTED_COLUMN_COUNT &&
                    fields[0] == "FRAME"
                ) return fields[1].toLongOrNull()
            }
        }

        csvFile.bufferedReader().use { reader ->
            val header = reader.readLine()?.removePrefix("\uFEFF")
            if (header != SessionCsvValidator.EXPECTED_HEADER.joinToString(",")) {
                throw IOException("Unexpected session CSV header")
            }

            var nextCsvFrameTimestamp = advanceCsvFrame(reader)
            var csvExhausted = false
            var matches = true
            val summary = BasaltFrameArchiveReader.read(archiveFile) { frame ->
                if (csvExhausted) {
                    matches = false
                } else {
                    if (nextCsvFrameTimestamp != frame.timestampNs) matches = false
                    nextCsvFrameTimestamp = advanceCsvFrame(reader)
                    if (nextCsvFrameTimestamp == null) csvExhausted = true
                }
            }
            if (nextCsvFrameTimestamp != null) matches = false
            return summary to (matches && nextCsvFrameTimestamp == null)
        }
    }
}
