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

        companion object {
            fun from(profile: BasaltCalibrationProfile) = CalibrationGate(
                cameraModelValidatedForAnalysisStream = profile.cameraModelValid,
                cameraToImuExtrinsicsCalibrated = profile.extrinsicsValid,
                gyroBiasAndNoiseCalibrated = profile.gyroCalibrationValid,
                accelerometerBiasAndNoiseCalibrated = profile.accelerometerCalibrationValid
            )
        }
    }

    data class Report(
        val csv: SessionReplayAnalyzer.Report,
        val archive: BasaltFrameArchiveReader.Summary?,
        val frameTimestampsMatch: Boolean,
        val frameDimensionsMatch: Boolean,
        val frameDimensionsStable: Boolean,
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
        var dimensionsMatch = false
        var dimensionsStable = false

        try {
            val comparison = compareFrameRecords(csvFile, frameArchiveFile)
            archiveSummary = comparison.summary
            timestampsMatch = comparison.timestampsMatch
            dimensionsMatch = comparison.dimensionsMatch
            dimensionsStable = comparison.dimensionsStable
            if (archiveSummary.framesDropped != 0L) {
                warnings += "The frame archive dropped ${archiveSummary.framesDropped} camera frames."
            }
            if (archiveSummary.frameCount != csvReport.frameStream.samples) {
                warnings += "CSV frame count and archived frame count do not match."
            }
            if (!timestampsMatch) {
                warnings += "CSV camera timestamps and archived frame timestamps do not match."
            }
            if (!dimensionsMatch) {
                warnings += "CSV camera frame dimensions and archived frame dimensions do not match."
            }
            if (!dimensionsStable) {
                warnings += "Archived camera frame dimensions change within the session."
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
            timestampsMatch && dimensionsMatch && dimensionsStable
        val ready = csvReport.replayReadyForVio && archiveValid && calibration.complete
        return Report(
            csv = csvReport,
            archive = archiveSummary,
            frameTimestampsMatch = timestampsMatch,
            frameDimensionsMatch = dimensionsMatch,
            frameDimensionsStable = dimensionsStable,
            calibrationComplete = calibration.complete,
            readyForNativeVio = ready,
            warnings = warnings.distinct()
        )
    }

    private data class CsvFrame(
        val timestampNs: Long?,
        val width: Int?,
        val height: Int?
    )

    private data class FrameComparison(
        val summary: BasaltFrameArchiveReader.Summary,
        val timestampsMatch: Boolean,
        val dimensionsMatch: Boolean,
        val dimensionsStable: Boolean
    )

    private fun compareFrameRecords(
        csvFile: File,
        archiveFile: File
    ): FrameComparison {
        fun advanceCsvFrame(source: BufferedReader): CsvFrame? {
            while (true) {
                val line = source.readLine() ?: return null
                val fields = line.split(',')
                if (fields.size == SessionCsvValidator.EXPECTED_COLUMN_COUNT &&
                    fields[0] == "FRAME"
                ) {
                    return CsvFrame(
                        fields[1].toLongOrNull(),
                        fields[2].toIntOrNull(),
                        fields[3].toIntOrNull()
                    )
                }
            }
        }

        csvFile.bufferedReader().use { reader ->
            val header = reader.readLine()?.removePrefix("\uFEFF")
            if (header != SessionCsvValidator.EXPECTED_HEADER.joinToString(",")) {
                throw IOException("Unexpected session CSV header")
            }

            var nextCsvFrame = advanceCsvFrame(reader)
            var csvExhausted = false
            var timestampsMatch = true
            var dimensionsMatch = true
            var dimensionsStable = true
            var firstDimensions: Pair<Int, Int>? = null

            val summary = BasaltFrameArchiveReader.read(archiveFile) { frame ->
                if (csvExhausted) {
                    timestampsMatch = false
                    dimensionsMatch = false
                } else {
                    if (nextCsvFrame?.timestampNs != frame.timestampNs) timestampsMatch = false
                    if (nextCsvFrame?.width != frame.width || nextCsvFrame?.height != frame.height) {
                        dimensionsMatch = false
                    }
                    val currentDimensions = frame.width to frame.height
                    if (firstDimensions == null) firstDimensions = currentDimensions
                    else if (firstDimensions != currentDimensions) dimensionsStable = false

                    nextCsvFrame = advanceCsvFrame(reader)
                    if (nextCsvFrame == null) csvExhausted = true
                }
            }
            if (nextCsvFrame != null) {
                timestampsMatch = false
                dimensionsMatch = false
            }
            return FrameComparison(
                summary,
                timestampsMatch && nextCsvFrame == null,
                dimensionsMatch && nextCsvFrame == null,
                dimensionsStable && summary.frameCount > 0L
            )
        }
    }
}
