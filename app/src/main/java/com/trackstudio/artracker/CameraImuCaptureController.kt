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
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

data class CaptureStats(
    val status: String = "Waiting for camera permission",
    val frameCount: Long = 0,
    val gyroX: String = "0.000",
    val gyroY: String = "0.000",
    val gyroZ: String = "0.000",
    val accelX: String = "0.000",
    val accelY: String = "0.000",
    val accelZ: String = "0.000"
)

/**
 * First milestone only: acquire camera frames and timestamped IMU samples.
 * No image copies, recording, SLAM, or reconstruction are performed here.
 */
class CameraImuCaptureController(
    private val context: Context,
    private val onStats: (CaptureStats) -> Unit
) : TextureView.SurfaceTextureListener, SensorEventListener {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(context.mainLooper)

    private var textureView: TextureView? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var running = false
    private var frameCount = 0L
    private var lastUiUpdateNs = 0L
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var latestFrameTimestampNs = 0L

    fun attach(view: TextureView) {
        if (textureView === view) return
        textureView = view
        view.surfaceTextureListener = this
        if (running && view.isAvailable) openCameraIfReady()
    }

    fun start() {
        if (running) return
        running = true
        cameraThread = HandlerThread("AR-Camera-IMU").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, cameraHandler)
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, cameraHandler)

        publish("Starting camera and motion sensors")
        openCameraIfReady()
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        publish("Capture stopped")
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
                        if (image != null) {
                            latestFrameTimestampNs = image.timestamp
                            frameCount++
                            publishThrottled("Capturing camera frames")
                        }
                    } catch (_: IllegalStateException) {
                        // A frame can be discarded if the consumer falls behind.
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
        } catch (security: SecurityException) {
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
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> lastGyro = values.copyOf()
            Sensor.TYPE_ACCELEROMETER -> lastAccel = values.copyOf()
            else -> return
        }
        publishThrottled("Camera + IMU capture running")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishThrottled(status: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastUiUpdateNs < 200_000_000L) return
        lastUiUpdateNs = now
        publish(status)
    }

    private fun publish(status: String) {
        val snapshot = CaptureStats(
            status = status,
            frameCount = frameCount,
            gyroX = format(lastGyro.getOrElse(0) { 0f }),
            gyroY = format(lastGyro.getOrElse(1) { 0f }),
            gyroZ = format(lastGyro.getOrElse(2) { 0f }),
            accelX = format(lastAccel.getOrElse(0) { 0f }),
            accelY = format(lastAccel.getOrElse(1) { 0f }),
            accelZ = format(lastAccel.getOrElse(2) { 0f })
        )
        mainHandler.post { onStats(snapshot) }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

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
