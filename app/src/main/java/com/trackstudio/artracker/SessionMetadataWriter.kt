package com.trackstudio.artracker

import android.content.Context
import android.hardware.Sensor
import android.hardware.camera2.CameraCharacteristics
import android.graphics.Rect
import android.os.Build
import android.util.Size
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes a sidecar JSON file for every capture. It records calibration metadata exposed by
 * Camera2 and the actual sensor-stream timing diagnostics. It does not claim to calibrate
 * camera-to-IMU extrinsics or sensor bias.
 */
internal object SessionMetadataWriter {
    fun create(
        context: Context,
        startedAtUnixMs: Long,
        cameraId: String?,
        characteristics: CameraCharacteristics?,
        gyro: Sensor?,
        accelerometer: Sensor?,
        gyroDiagnostics: SensorStreamDiagnostics.Snapshot,
        accelDiagnostics: SensorStreamDiagnostics.Snapshot,
        frameDiagnostics: CameraFrameDiagnostics.Snapshot,
        analysisStream: CameraAnalysisStreamMetadata?,
        frameArchiveFile: File?,
        frameArchiveSnapshot: BasaltFrameArchiveWriter.Snapshot?,
        frameArchiveRecording: Boolean,
        frameArchiveStartError: String?
    ): JSONObject {
        val root = JSONObject()
            .put("schemaVersion", 3)
            .put("sessionStartedAtUnixMs", startedAtUnixMs)
            .put("app", JSONObject()
                .put("applicationId", context.packageName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL))
            .put("timestampNotes", JSONObject()
                .put("sensorEventTimestamp", "Nanoseconds in the Android sensor time base")
                .put("cameraTimestampSource", timestampSource(characteristics))
                .put("fusionAllowed", timestampSource(characteristics) == "REALTIME")
                .put("warning", "Only REALTIME camera timestamps are treated as sharing the elapsed-realtime time base. UNKNOWN is recorded but not fused."))
            .put("sensors", JSONObject()
                .put("gyroscope", sensorJson(gyro))
                .put("accelerometer", sensorJson(accelerometer)))
            .put("sensorDiagnostics", JSONObject()
                .put("gyroscope", diagnosticsJson(gyroDiagnostics))
                .put("accelerometer", diagnosticsJson(accelDiagnostics)))
            .put("cameraFrameDiagnostics", frameDiagnosticsJson(frameDiagnostics))
            .put("analysisStream", analysisStreamJson(analysisStream))
            .put("basaltFrameArchive", frameArchiveJson(
                frameArchiveFile, frameArchiveSnapshot, frameArchiveRecording, frameArchiveStartError
            ))

        if (cameraId != null && characteristics != null) {
            root.put("camera", cameraJson(cameraId, characteristics))
        } else {
            root.put("camera", JSONObject().put("available", false))
        }
        root.put("calibrationReadiness", JSONObject()
            .put("cameraIntrinsicsAvailable", characteristics?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null)
            .put("cameraDistortionAvailable", characteristics?.get(CameraCharacteristics.LENS_DISTORTION) != null)
            .put("androidLensPoseAvailable", characteristics?.get(CameraCharacteristics.LENS_POSE_ROTATION) != null &&
                characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION) != null)
            .put("cameraImuExtrinsicsCalibrated", false)
            .put("imuBiasCalibrated", false)
            .put("worldGravityCompensationImplemented", false)
            .put("note", "Camera2 metadata is a calibration input, not a complete camera-IMU calibration. Run a dedicated synchronized calibration before metric VIO."))
        return root
    }

    fun writeAtomically(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun timestampSource(characteristics: CameraCharacteristics?): String =
        when (characteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN, null -> "UNKNOWN"
            else -> "UNKNOWN"
        }

    private fun cameraJson(id: String, c: CameraCharacteristics): JSONObject {
        val pixelArray: Size? = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray: Rect? = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physicalSize: SizeF? = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return JSONObject()
            .put("available", true)
            .put("cameraId", id)
            .put("lensFacing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            })
            .put("sensorOrientationDegrees", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("hardwareLevel", hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
            .put("timestampSource", timestampSource(c))
            .put("pixelArray", sizeJson(pixelArray))
            .put("activeArray", activeArray?.let {
                JSONObject().put("left", it.left).put("top", it.top)
                    .put("right", it.right).put("bottom", it.bottom)
                    .put("width", it.width()).put("height", it.height())
            } ?: JSONObject.NULL)
            .put("physicalSensorSizeMm", physicalSize?.let {
                JSONArray().put(it.width.toDouble()).put(it.height.toDouble())
            } ?: JSONObject.NULL)
            .put("availableFocalLengthsMm", floatArrayJson(focalLengths))
            .put("intrinsicsFxFyCxCySkew", floatArrayJson(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)))
            .put("distortionK1K2K3P1P2", floatArrayJson(c.get(CameraCharacteristics.LENS_DISTORTION)))
            .put("androidLensPoseRotation", floatArrayJson(c.get(CameraCharacteristics.LENS_POSE_ROTATION)))
            .put("androidLensPoseTranslationMeters", floatArrayJson(c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)))
    }

    private fun sensorJson(sensor: Sensor?): JSONObject {
        if (sensor == null) return JSONObject().put("available", false)
        return JSONObject()
            .put("available", true)
            .put("name", sensor.name)
            .put("vendor", sensor.vendor)
            .put("type", sensor.type)
            .put("version", sensor.version)
            .put("resolution", sensor.resolution.toDouble())
            .put("maximumRange", sensor.maximumRange.toDouble())
            .put("minimumDelayUs", sensor.minDelay)
            .put("maximumDelayUs", sensor.maxDelay)
            .put("reportingMode", sensor.reportingMode)
            .put("wakeUpSensor", sensor.isWakeUpSensor)
    }

    private fun frameDiagnosticsJson(s: CameraFrameDiagnostics.Snapshot) = JSONObject()
        .put("acceptedFrames", s.acceptedFrames)
        .put("invalidTimestamps", s.invalidTimestamps)
        .put("outOfOrderTimestamps", s.outOfOrderTimestamps)
        .put("meanIntervalMs", s.meanIntervalMs)
        .put("maximumIntervalMs", s.maximumIntervalMs)
        .put("estimatedRateHz", s.estimatedRateHz)
        .put("gapsOver100ms", s.longGapCount)
        .put("lastTimestampNs", s.lastTimestampNs)

    private fun analysisStreamJson(s: CameraAnalysisStreamMetadata?): JSONObject {
        if (s == null) return JSONObject().put("available", false)
        return JSONObject()
            .put("available", true)
            .put("imageWidth", s.width)
            .put("imageHeight", s.height)
            .put("imageFormat", s.imageFormat)
            .put("cropRect", JSONObject()
                .put("left", s.cropLeft).put("top", s.cropTop)
                .put("right", s.cropRight).put("bottom", s.cropBottom)
                .put("width", s.cropRight - s.cropLeft)
                .put("height", s.cropBottom - s.cropTop))
            .put("yPlaneRowStride", s.rowStride)
            .put("yPlanePixelStride", s.pixelStride)
            .put("intrinsicsTransformApplied", false)
            .put("note", "Crop and plane layout describe the observed ImageReader frame. Camera2 intrinsics are retained in their native coordinate convention; no unverified transform is applied.")
    }

    private fun frameArchiveJson(
        file: File?,
        snapshot: BasaltFrameArchiveWriter.Snapshot?,
        recording: Boolean,
        startError: String?
    ) = JSONObject()
        .put("available", file?.exists() == true)
        .put("fileName", file?.name ?: JSONObject.NULL)
        .put("format", "TSBF")
        .put("formatVersion", BasaltFrameArchiveWriter.FORMAT_VERSION)
        .put("pixelFormat", "GRAY8")
        .put("compression", "DEFLATE")
        .put("recording", recording)
        .put("framesWritten", snapshot?.framesWritten ?: 0L)
        .put("framesDropped", snapshot?.framesDropped ?: 0L)
        .put("archiveComplete", snapshot?.complete ?: false)
        .put("archiveBytes", snapshot?.archiveBytes ?: (file?.length() ?: 0L))
        .put("error", snapshot?.error ?: startError ?: JSONObject.NULL)

    private fun diagnosticsJson(s: SensorStreamDiagnostics.Snapshot) = JSONObject()
        .put("acceptedSamples", s.acceptedSamples)
        .put("invalidSamples", s.invalidSamples)
        .put("outOfOrderSamples", s.outOfOrderSamples)
        .put("meanIntervalMs", s.meanIntervalMs)
        .put("maximumIntervalMs", s.maxIntervalMs)
        .put("estimatedRateHz", s.estimatedRateHz)
        .put("gapsOver50ms", s.longGapCount)
        .put("lastTimestampNs", s.lastTimestampNs)

    private fun sizeJson(size: Size?): Any =
        size?.let { JSONObject().put("width", it.width).put("height", it.height) } ?: JSONObject.NULL

    private fun floatArrayJson(values: FloatArray?): JSONArray? {
        if (values == null) return null
        val result = JSONArray()
        values.forEach { result.put(if (it.isFinite()) it.toDouble() else JSONObject.NULL) }
        return result
    }

    private fun hardwareLevel(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
