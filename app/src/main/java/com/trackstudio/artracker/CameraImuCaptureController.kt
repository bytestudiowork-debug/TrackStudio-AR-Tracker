package com.trackstudio.artracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

data class CaptureStats(
    val status: String = "Waiting for camera permission",
    val frameCount: Long = 0,
    val featureCount: Int = 0,
    val trackedFeatureCount: Int = 0,
    val meanMotionX: String = "0.000",
    val meanMotionY: String = "0.000",
    val gyroX: String = "0.000",
    val gyroY: String = "0.000",
    val gyroZ: String = "0.000",
    val accelX: String = "0.000",
    val accelY: String = "0.000",
    val accelZ: String = "0.000",
    val gyroDeltaX: String = "0.000",
    val gyroDeltaY: String = "0.000",
    val gyroDeltaZ: String = "0.000",
    val imuIntervalMs: String = "0.0",
    val imuSampleCount: Int = 0,
    val imuDeltaValid: Boolean = false,
    val cameraTimestampSource: String = "UNKNOWN",
    val accelDeltaVelocity: String = "0.000, 0.000, 0.000",
    val accelDeltaPosition: String = "0.000, 0.000, 0.000",
    val accelDeltaValid: Boolean = false,
    val gravityEstimate: String = "unavailable",
    val gyroRateHz: String = "0.0",
    val accelRateHz: String = "0.0",
    val gyroMaxGapMs: String = "0.0",
    val accelMaxGapMs: String = "0.0",
    val cameraCalibrationStatus: String = "not available",
    val cameraRateHz: String = "0.0",
    val cameraMaxGapMs: String = "0.0",
    val cameraTimestampErrors: Long = 0,
    val analysisStreamSummary: String = "not available"
)

/**
 * Camera2/IMU acquisition and first-stage visual-inertial timing.
 * Optical flow and gyro samples are associated using camera/IMU monotonic timestamps.
 * This is not yet a metric VIO pose estimator or a 3D reconstruction engine.
 */
class CameraImuCaptureController(
    private val context: Context,
    private val onStats: (CaptureStats) -> Unit
) : TextureView.SurfaceTextureListener, SensorEventListener {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(context.mainLooper)
    private val imuPreintegrator = ImuPreintegrator()
    private val gyroDiagnostics = SensorStreamDiagnostics()
    private val accelDiagnostics = SensorStreamDiagnostics()
    private val frameDiagnostics = CameraFrameDiagnostics()
    @Volatile private var analysisStreamMetadata: CameraAnalysisStreamMetadata? = null
    private var gyroSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var sessionStartedAtUnixMs = 0L
    private var sessionMetadataFile: File? = null
    private var frameArchiveFile: File? = null
    private var frameArchiveWriter: BasaltFrameArchiveWriter? = null
    @Volatile private var frameArchiveSnapshot: BasaltFrameArchiveWriter.Snapshot? = null
    @Volatile private var frameArchiveStartError: String? = null
    private var activeCameraId: String? = null
    private var activeCameraCharacteristics: CameraCharacteristics? = null

    private var textureView: TextureView? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var loggerThread: HandlerThread? = null
    private var loggerHandler: Handler? = null
    private var sessionLogFile: File? = null
    private var sessionWriter: BufferedWriter? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile private var running = false
    @Volatile private var cameraTimestampSource = "UNKNOWN"
    private var frameCount = 0L
    private var lastUiUpdateNs = 0L
    private var latestFeatures = 0
    private var latestTracked = 0
    private var latestMotionX = 0.0
    private var latestMotionY = 0.0
    @Volatile private var lastGyro = floatArrayOf(0f, 0f, 0f)
    @Volatile private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var previousFrameTimestampNs = 0L
    private var latestGyroDelta = ImuPreintegrator.Delta(false)
    private var latestAccelDelta = ImuPreintegrator.AccelerationDelta(false)

    fun attach(view: TextureView) {
        if (textureView === view) return
        textureView = view
        view.surfaceTextureListener = this
        if (running && view.isAvailable) openCameraIfReady()
    }

    fun start() {
        if (running) return
        try {
            startCapture()
        } catch (error: Exception) {
            recoverFromStartFailure(error)
        } catch (error: LinkageError) {
            recoverFromStartFailure(error)
        }
    }

    private fun recoverFromStartFailure(error: Throwable) {
        // A partial startup may already own worker threads, sensors, or file handles.
        // Stop what was initialized, but keep cleanup failures from crashing the activity.
        try {
            stop()
        } catch (_: Exception) {
            running = false
        } catch (_: LinkageError) {
            running = false
        }
        publish("Capture startup failed: ${error.javaClass.simpleName}")
    }

    private fun startCapture() {
        if (running) return
        running = true
        NativeFeatureTracker.reset()
        imuPreintegrator.reset()
        previousFrameTimestampNs = 0L
        frameCount = 0L
        cameraTimestampSource = "UNKNOWN"
        gyroDiagnostics.reset()
        accelDiagnostics.reset()
        frameDiagnostics.reset()
        analysisStreamMetadata = null
        sessionStartedAtUnixMs = System.currentTimeMillis()
        activeCameraId = null
        activeCameraCharacteristics = null
        latestGyroDelta = ImuPreintegrator.Delta(false)
        cameraThread = HandlerThread("AR-Camera-IMU").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        // Keep high-rate sensor callbacks off the image-processing thread. Optical flow can
        // take several milliseconds per frame; sharing the handler would starve IMU sampling.
        sensorThread = HandlerThread("AR-Sensor-IMU").also { it.start() }
        sensorHandler = Handler(sensorThread!!.looper)
        loggerThread = HandlerThread("AR-Session-Logger").also { it.start() }
        loggerHandler = Handler(loggerThread!!.looper)
        val logDir = File(context.filesDir, "sessions").apply { mkdirs() }
        val sessionStem = "capture-$sessionStartedAtUnixMs"
        sessionLogFile = File(logDir, "$sessionStem.csv")
        sessionMetadataFile = File(logDir, "$sessionStem.json")
        frameArchiveFile = File(logDir, "$sessionStem.basalt.frames")
        frameArchiveSnapshot = null
        frameArchiveStartError = null
        frameArchiveWriter = try {
            BasaltFrameArchiveWriter(frameArchiveFile!!)
        } catch (error: Exception) {
            frameArchiveStartError = error.message ?: error.javaClass.simpleName
            null
        }
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        loggerHandler?.post {
            try {
                sessionWriter = FileWriter(sessionLogFile!!, false).buffered()
                sessionWriter?.apply {
                    write("record_type,timestamp_ns,frame_width,frame_height,features_detected,features_tracked,mean_flow_x_px,mean_flow_y_px,sensor_type,vector_x,vector_y,vector_z,delta_rotation_x_rad,delta_rotation_y_rad,delta_rotation_z_rad,interval_ms,imu_samples,camera_timestamp_source,delta_velocity_x_mps,delta_velocity_y_mps,delta_velocity_z_mps,delta_position_x_m,delta_position_y_m,delta_position_z_m,gravity_x_mps2,gravity_y_mps2,gravity_z_mps2")
                    newLine()
                    flush()
                }
            } catch (_: Exception) { }
            writeSessionMetadata()
        }

        val gyroRegistered = gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        } ?: false
        val accelRegistered = accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        } ?: false
        if (!gyroRegistered || !accelRegistered) {
            publish("Capture started — missing " + listOfNotNull(
                if (!gyroRegistered) "gyroscope" else null,
                if (!accelRegistered) "accelerometer" else null
            ).joinToString(" and "))
        }

        publish("Starting camera and motion sensors")
        openCameraIfReady()
    }

    fun stop() {
        if (!running) return
        running = false
        NativeFeatureTracker.reset()
        imuPreintegrator.reset()
        previousFrameTimestampNs = 0L
        sensorManager.unregisterListener(this)
        val sensorWorker = sensorThread
        sensorWorker?.quitSafely()
        if (sensorWorker != null && Thread.currentThread() !== sensorWorker) sensorWorker.join()
        sensorThread = null
        sensorHandler = null

        closeCamera()
        val cameraWorker = cameraThread
        cameraWorker?.quitSafely()
        if (cameraWorker != null && Thread.currentThread() !== cameraWorker) cameraWorker.join()
        cameraThread = null
        cameraHandler = null

        // All camera callbacks have drained before the archive is finalized.
        frameArchiveSnapshot = frameArchiveWriter?.finish()
        frameArchiveWriter = null
        enqueueSessionMetadataWrite()

        val completedCsv = sessionLogFile
        val replayReport = completedCsv?.let { File(it.parentFile, it.nameWithoutExtension + ".replay.json") }
        loggerHandler?.post {
            try {
                sessionWriter?.flush()
                sessionWriter?.close()
                sessionWriter = null
                if (completedCsv != null && completedCsv.isFile && replayReport != null) {
                    val report = completedCsv.bufferedReader().use { reader ->
                        SessionReplayAnalyzer.analyze(reader.lineSequence())
                    }
                    SessionReplayReportWriter.writeAtomically(replayReport, report)
                }
            } catch (error: Exception) {
                // The original CSV and metadata remain available even if report generation fails.
            }
        }
        loggerThread?.quitSafely()
        loggerThread = null
        loggerHandler = null
        publish("Capture stopped — session data saved; replay report queued")
    }

    private fun openCameraIfReady() {
        val view = textureView ?: return
        if (!running || !view.isAvailable || cameraDevice != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publish("Camera permission required")
            return
        }

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                publish("No camera is available")
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            activeCameraId = cameraId
            activeCameraCharacteristics = characteristics
            cameraTimestampSource = when (
                characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                else -> "UNKNOWN"
            }
            enqueueSessionMetadataWrite()
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                publish("Camera does not expose stream configuration")
                return
            }

            val previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java))
            val analysisSize = chooseAnalysisSize(map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888))
            view.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

            imageReader = ImageReader.newInstance(
                analysisSize.width,
                analysisSize.height,
                android.graphics.ImageFormat.YUV_420_888,
                2
            ).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    var image: Image? = null
                    try {
                        image = source.acquireLatestImage()
                        if (image != null && running) {
                            val frameTimestamp = image.timestamp
                            if (!frameDiagnostics.record(frameTimestamp)) {
                                throw IllegalStateException("Camera frame timestamp is invalid or out of order")
                            }
                            frameCount = frameDiagnostics.snapshot().acceptedFrames
                            val plane = image.planes[0]
                            val crop = image.cropRect
                            val packed = LumaFramePacker.pack(
                                buffer = plane.buffer,
                                imageWidth = image.width,
                                imageHeight = image.height,
                                rowStride = plane.rowStride,
                                pixelStride = plane.pixelStride,
                                cropLeft = crop.left,
                                cropTop = crop.top,
                                cropRight = crop.right,
                                cropBottom = crop.bottom
                            )
                            if (analysisStreamMetadata == null) {
                                analysisStreamMetadata = CameraAnalysisStreamMetadata(
                                    image.width, image.height, image.format,
                                    crop.left, crop.top, crop.right, crop.bottom,
                                    plane.rowStride, plane.pixelStride
                                )
                                enqueueSessionMetadataWrite()
                            }
                            // A bounded asynchronous writer never stalls Camera2 or IMU sampling.
                            frameArchiveWriter?.offer(
                                frameTimestamp, packed.width, packed.height, packed.pixels
                            )
                            val result = NativeFeatureTracker.processFrame(
                                packed.pixels, packed.width, packed.height, packed.width, 1
                            )
                            latestFeatures = result.getOrElse(0) { 0L }.toInt()
                            latestTracked = result.getOrElse(1) { 0L }.toInt()
                            latestMotionX = result.getOrElse(2) { 0L } / 1000.0
                            latestMotionY = result.getOrElse(3) { 0L } / 1000.0

                            if (cameraTimestampSource == "REALTIME" && previousFrameTimestampNs > 0L) {
                                latestGyroDelta = imuPreintegrator.integrate(previousFrameTimestampNs, frameTimestamp)
                                latestAccelDelta = imuPreintegrator.integrateAcceleration(previousFrameTimestampNs, frameTimestamp)
                                if (latestGyroDelta.valid) {
                                    logCsvFields("IMU_DELTA", frameTimestamp, null, null, null, null, null, null, null, null, null, null, latestGyroDelta.rotationXRad, latestGyroDelta.rotationYRad, latestGyroDelta.rotationZRad, latestGyroDelta.intervalSeconds * 1000.0, latestGyroDelta.sampleCount, cameraTimestampSource)
                                }
                                if (latestAccelDelta.valid) {
                                    val gravity = imuPreintegrator.gravityEstimate()
                                    logCsvFields("ACCEL_DELTA", frameTimestamp, null, null, null, null, null, null, null, null, null, null, null, latestAccelDelta.intervalSeconds * 1000.0, latestAccelDelta.sampleCount, cameraTimestampSource, latestAccelDelta.velocityX, latestAccelDelta.velocityY, latestAccelDelta.velocityZ, latestAccelDelta.positionX, latestAccelDelta.positionY, latestAccelDelta.positionZ, gravity.x, gravity.y, gravity.z)
                                }
                            } else {
                                latestGyroDelta = ImuPreintegrator.Delta(false)
                                latestAccelDelta = ImuPreintegrator.AccelerationDelta(false)
                            }
                            previousFrameTimestampNs = frameTimestamp

                            val gravity = imuPreintegrator.gravityEstimate()
                            logCsvFields("FRAME", frameTimestamp, packed.width, packed.height, latestFeatures, latestTracked, latestMotionX, latestMotionY, null, null, null, null, null, null, null, null, null, cameraTimestampSource, null, null, null, null, null, null, gravity.x.takeIf { gravity.valid }, gravity.y.takeIf { gravity.valid }, gravity.z.takeIf { gravity.valid })
                            publishThrottled(
                                if (cameraTimestampSource == "REALTIME") "Camera + IMU timing active"
                                else "Camera active — IMU fusion unavailable (unknown timestamp source)"
                            )
                        }
                    } catch (_: RuntimeException) {
                        // Drop malformed or stale frames without terminating the camera callback thread.
                    } catch (_: LinkageError) {
                        // A native image-processing failure must not take down the app process.
                        NativeFeatureTracker.reset()
                    } finally {
                        image?.close()
                    }
                }, cameraHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(view)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    publish("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    publish("Camera error: $error")
                }
            }, cameraHandler)
        } catch (_: SecurityException) {
            publish("Camera permission is missing")
        } catch (error: Exception) {
            publish("Camera setup failed: ${error.javaClass.simpleName}")
        }
    }

    private fun createSession(view: TextureView) {
        val camera = cameraDevice ?: return
        val texture = view.surfaceTexture ?: return
        val reader = imageReader ?: return
        val previewSurface = Surface(texture)
        try {
            camera.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running) {
                            session.close()
                            previewSurface.release()
                            return
                        }
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            publish("Camera + IMU capture running")
                        } catch (error: Exception) {
                            publish("Capture request failed: ${error.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        previewSurface.release()
                        publish("Camera session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (error: Exception) {
            previewSurface.release()
            publish("Could not create camera session: ${error.javaClass.simpleName}")
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) { }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        val x = values.getOrElse(0) { 0f }
        val y = values.getOrElse(1) { 0f }
        val z = values.getOrElse(2) { 0f }
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (gyroDiagnostics.record(event.timestamp, x, y, z)) {
                    imuPreintegrator.addGyroscope(event.timestamp, x, y, z)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (accelDiagnostics.record(event.timestamp, x, y, z)) {
                    imuPreintegrator.addAccelerometer(event.timestamp, x, y, z)
                }
            }
        }
        logCsvFields("IMU", event.timestamp, null, null, null, null, null, null, event.sensor.type, x, y, z, null, null, null, null, null, cameraTimestampSource)
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> lastGyro = values.copyOf()
            Sensor.TYPE_ACCELEROMETER -> lastAccel = values.copyOf()
            else -> return
        }
        publishThrottled("Camera + IMU capture running")
    }

    private fun logCsvFields(vararg fields: Any?) {
        logCsv(fields.joinToString(",") { it?.toString() ?: "" })
    }

    private fun logCsv(line: String) {
        loggerHandler?.post {
            try {
                val writer = sessionWriter ?: return@post
                writer.append(line)
                writer.newLine()
            } catch (_: Exception) { }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishThrottled(status: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastUiUpdateNs < 200_000_000L) return
        lastUiUpdateNs = now
        publish(status)
    }

    private fun publish(status: String) {
        val delta = latestGyroDelta
        val accelDelta = latestAccelDelta
        val gravity = imuPreintegrator.gravityEstimate()
        val gyroHealth = gyroDiagnostics.snapshot()
        val accelHealth = accelDiagnostics.snapshot()
        val cameraCalibrationStatus = when {
            activeCameraCharacteristics == null -> "camera metadata unavailable"
            activeCameraCharacteristics?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null -> "intrinsics available"
            else -> "intrinsics not reported by device"
        }
        val snapshot = CaptureStats(
            status = status,
            frameCount = frameCount,
            featureCount = latestFeatures,
            trackedFeatureCount = latestTracked,
            meanMotionX = String.format(Locale.US, "%.3f", latestMotionX),
            meanMotionY = String.format(Locale.US, "%.3f", latestMotionY),
            gyroX = format(lastGyro.getOrElse(0) { 0f }),
            gyroY = format(lastGyro.getOrElse(1) { 0f }),
            gyroZ = format(lastGyro.getOrElse(2) { 0f }),
            accelX = format(lastAccel.getOrElse(0) { 0f }),
            accelY = format(lastAccel.getOrElse(1) { 0f }),
            accelZ = format(lastAccel.getOrElse(2) { 0f }),
            gyroDeltaX = format(delta.rotationXRad.toFloat()),
            gyroDeltaY = format(delta.rotationYRad.toFloat()),
            gyroDeltaZ = format(delta.rotationZRad.toFloat()),
            imuIntervalMs = String.format(Locale.US, "%.1f", delta.intervalSeconds * 1000.0),
            imuSampleCount = delta.sampleCount,
            imuDeltaValid = delta.valid,
            cameraTimestampSource = cameraTimestampSource,
            accelDeltaVelocity = vector(accelDelta.velocityX, accelDelta.velocityY, accelDelta.velocityZ),
            accelDeltaPosition = vector(accelDelta.positionX, accelDelta.positionY, accelDelta.positionZ),
            accelDeltaValid = accelDelta.valid,
            gravityEstimate = if (gravity.valid) vector(gravity.x, gravity.y, gravity.z) else "unavailable",
            gyroRateHz = String.format(Locale.US, "%.1f", gyroHealth.estimatedRateHz),
            accelRateHz = String.format(Locale.US, "%.1f", accelHealth.estimatedRateHz),
            gyroMaxGapMs = String.format(Locale.US, "%.1f", gyroHealth.maxIntervalMs),
            accelMaxGapMs = String.format(Locale.US, "%.1f", accelHealth.maxIntervalMs),
            cameraCalibrationStatus = cameraCalibrationStatus,
            cameraRateHz = String.format(Locale.US, "%.1f", frameDiagnostics.snapshot().estimatedRateHz),
            cameraMaxGapMs = String.format(Locale.US, "%.1f", frameDiagnostics.snapshot().maximumIntervalMs),
            cameraTimestampErrors = frameDiagnostics.snapshot().invalidTimestamps +
                frameDiagnostics.snapshot().outOfOrderTimestamps,
            analysisStreamSummary = analysisStreamMetadata?.let {
                "${it.width}x${it.height} crop ${it.cropRight - it.cropLeft}x${it.cropBottom - it.cropTop}"
            } ?: "not available"
        )
        mainHandler.post { onStats(snapshot) }
    }

    private fun enqueueSessionMetadataWrite() {
        val file = sessionMetadataFile ?: return
        val json = SessionMetadataWriter.create(
            context = context,
            startedAtUnixMs = sessionStartedAtUnixMs,
            cameraId = activeCameraId,
            characteristics = activeCameraCharacteristics,
            gyro = gyroSensor,
            accelerometer = accelerometerSensor,
            gyroDiagnostics = gyroDiagnostics.snapshot(),
            accelDiagnostics = accelDiagnostics.snapshot(),
            frameDiagnostics = frameDiagnostics.snapshot(),
            analysisStream = analysisStreamMetadata,
            frameArchiveFile = frameArchiveFile,
            frameArchiveSnapshot = frameArchiveSnapshot,
            frameArchiveRecording = frameArchiveWriter != null,
            frameArchiveStartError = frameArchiveStartError
        )
        loggerHandler?.post {
            try {
                SessionMetadataWriter.writeAtomically(file, json)
            } catch (_: Exception) { }
        }
    }

    private fun writeSessionMetadata() = enqueueSessionMetadataWrite()

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun vector(x: Double, y: Double, z: Double): String =
        String.format(Locale.US, "%.3f, %.3f, %.3f", x, y, z)

    private fun choosePreviewSize(sizes: Array<Size>): Size {
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { abs((it.width.toFloat() / it.height) - (16f / 9f)) * 1000 +
                abs(it.width - 1280) }
            ?: sizes.first()
    }

    private fun chooseAnalysisSize(sizes: Array<Size>): Size {
        return sizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: Size(640, 480)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCameraIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
