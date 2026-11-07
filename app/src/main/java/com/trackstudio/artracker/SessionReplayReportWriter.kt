package com.trackstudio.artracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists a compact, machine-readable replay screening report beside each completed CSV. */
internal object SessionReplayReportWriter {
    fun create(report: SessionReplayAnalyzer.Report): JSONObject {
        fun stream(summary: SessionReplayAnalyzer.StreamSummary) = JSONObject()
            .put("samples", summary.samples)
            .put("firstTimestampNs", summary.firstTimestampNs ?: JSONObject.NULL)
            .put("lastTimestampNs", summary.lastTimestampNs ?: JSONObject.NULL)
            .put("durationMs", summary.durationMs)
            .put("estimatedRateHz", summary.estimatedRateHz)
            .put("maximumGapMs", summary.maximumGapMs)

        val validation = report.validation
        return JSONObject()
            .put("schemaVersion", 1)
            .put("reportType", "session-replay-readiness")
            .put("replayReadyForVio", report.replayReadyForVio)
            .put("cameraTimestampSource", report.cameraTimestampSource)
            .put("streams", JSONObject()
                .put("camera", stream(report.frameStream))
                .put("gyroscope", stream(report.gyroStream))
                .put("accelerometer", stream(report.accelerometerStream)))
            .put("integrity", JSONObject()
                .put("headerValid", validation.headerValid)
                .put("isValid", validation.isValid)
                .put("totalRows", validation.totalRows)
                .put("frameRows", validation.frameRows)
                .put("imuRows", validation.imuRows)
                .put("gyroDeltaRows", validation.gyroDeltaRows)
                .put("accelerationDeltaRows", validation.accelerationDeltaRows)
                .put("malformedRows", validation.malformedRows)
                .put("invalidTimestamps", validation.invalidTimestamps)
                .put("outOfOrderTimestamps", validation.outOfOrderTimestamps)
                .put("unsupportedRows", validation.unsupportedRows))
            .put("warnings", JSONArray(report.warnings))
            .put("limitations", JSONArray()
                .put("This report screens capture integrity and timing only.")
                .put("It does not contain camera poses or prove metric calibration.")
                .put("Camera-to-IMU extrinsics and IMU bias/noise calibration are not estimated."))
    }

    fun writeAtomically(destination: File, report: SessionReplayAnalyzer.Report) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText(create(report).toString(2))
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not replace previous replay report")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not finalize replay report")
        }
    }
}
