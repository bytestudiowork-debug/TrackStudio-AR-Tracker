package com.trackstudio.artracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.os.Build
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Minimal Camera2 preview: no ImageReader, OpenCV, JNI, sensors, or file writers. */
internal class CameraPreviewDiagnosticController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : TextureView.SurfaceTextureListener {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val generation = AtomicLong(0)
    @Volatile private var running = false
    @Volatile private var textureView: TextureView? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var recordingFile: File? = null
    private var recordingDirectory: File? = null
    @Volatile private var recording = false
    private var recordingStarting = false
    @Volatile private var torchEnabled = false
    @Volatile private var activeCameraId: String? = null
    private var opening = false

    fun startRecording(project: TrackStudioProject, onState: (Boolean, String) -> Unit) {
        cameraHandler?.post {
            val camera = cameraDevice
            val preview = previewSurface
            if (!running || camera == null || preview == null || recording || recordingStarting) {
                onState(recording, "Camera is not ready to record")
                return@post
            }
            recordingStarting = true
            try {
                val cameraId = activeCameraId ?: throw IllegalStateException("Camera is not ready")
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("Recording sizes are unavailable")
                val supported = map.getOutputSizes(MediaRecorder::class.java)
                    ?: throw IllegalStateException("Video recording is not supported")
                val size = supported.filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: supported.minByOrNull { it.width.toLong() * it.height }
                    ?: throw IllegalStateException("No compatible video size")
                val directory = File(project.directory, "Recordings/recording-${System.currentTimeMillis()}")
                if (!directory.mkdirs()) throw IllegalStateException("Could not create recording folder")
                val output = File(directory, "video.mp4")
                val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                mediaRecorder = recorder
                recordingDirectory = directory
                recordingFile = output
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setOutputFile(output.absolutePath)
                    setOrientationHint(90)
                    prepare()
                }
                val inputSurface = recorder.surface
                recorderSurface = inputSurface
                captureSession?.close()
                captureSession = null
                camera.createCaptureSession(listOf(preview, inputSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running) {
                            session.close()
                            releaseRecorder()
                            recordingStarting = false
                            onState(false, "Recording cancelled")
                            return
                        }
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(inputSurface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                if (torchEnabled) {
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                }
                            }.build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            recorder.start()
                            recording = true
                            recordingStarting = false
                            File(directory, "recording.json").writeText(
                                JSONObject().put("file", "video.mp4")
                                    .put("startedAtUnixMs", System.currentTimeMillis())
                                    .put("width", size.width).put("height", size.height)
                                    .put("videoCodec", "H.264").put("audio", false).toString(2),
                                Charsets.UTF_8
                            )
                            publish("Recording")
                            onState(true, "Recording")
                        } catch (error: Exception) {
                            recordingStarting = false
                            stopRecordingOnCameraThread()
                            onState(false, "Could not start recording: ${error.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        recordingStarting = false
                        releaseRecorder()
                        configurePreviewSession(camera, preview)
                        onState(false, "Could not configure video recording")
                    }
                }, cameraHandler)
            } catch (error: Exception) {
                recordingStarting = false
                releaseRecorder()
                onState(false, "Recording setup failed: ${error.message}")
            }
        } ?: onState(false, "Camera is not running")
    }

    fun stopRecording(onState: (Boolean, String) -> Unit = { _, _ -> }) {
        cameraHandler?.post {
            val wasRecording = recording
            stopRecordingOnCameraThread()
            onState(false, if (wasRecording) "Recording saved" else "Recording stopped")
        } ?: onState(false, "Camera is not running")
    }

    fun toggleTorch(onState: (Boolean) -> Unit) {
        cameraHandler?.post {
            val camera = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val preview = previewSurface ?: return@post
            val cameraId = activeCameraId ?: return@post
            val hasFlash = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) {
                publish("Flashlight is not available on this camera")
                return@post
            }
            torchEnabled = !torchEnabled
            try {
                val request = camera.createCaptureRequest(
                    if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                ).apply {
                    addTarget(preview)
                    if (recording) recorderSurface?.let(::addTarget)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    if (torchEnabled) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    } else {
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }.build()
                session.setRepeatingRequest(request, null, cameraHandler)
                onState(torchEnabled)
            } catch (error: Exception) {
                torchEnabled = false
                publish("Could not change flashlight: ${error.message}")
                onState(false)
            }
        }
    }

    private fun stopRecordingOnCameraThread() {
        val camera = cameraDevice
        val preview = previewSurface
        if (recording) {
            try { mediaRecorder?.stop() } catch (_: RuntimeException) { recordingFile?.delete() }
        }
        recording = false
        recordingStarting = false
        releaseRecorder()
        if (camera != null && preview != null && running) configurePreviewSession(camera, preview)
        publish("Recording stopped")
    }

    private fun releaseRecorder() {
        try { recorderSurface?.release() } catch (_: Exception) { }
        recorderSurface = null
        try { mediaRecorder?.reset() } catch (_: Exception) { }
        try { mediaRecorder?.release() } catch (_: Exception) { }
        mediaRecorder = null
        recordingFile = null
        recordingDirectory = null
    }

    private fun configurePreviewSession(camera: CameraDevice, preview: Surface) {
        try {
            camera.createCaptureSession(listOf(preview), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running) { session.close(); return }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            if (torchEnabled) {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                    } catch (error: Exception) {
                        publish("Could not resume preview: ${error.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    publish("Could not restore camera preview")
                }
            }, cameraHandler)
        } catch (error: Exception) {
            publish("Could not restore camera preview: ${error.message}")
        }
    }

    /** Trigger one-shot autofocus when the user taps the focus control. */
    fun focus() {
        cameraHandler?.post {
            val camera = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val surface = previewSurface ?: return@post
            try {
                val cameraId = activeCameraId ?: return@post
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (modes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) != true) {
                    publish("Autofocus is not supported by this camera")
                    return@post
                }
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        try {
                            val idleRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                            }.build()
                            session.setRepeatingRequest(idleRequest, null, cameraHandler)
                        } catch (error: Exception) {
                            publish("Could not resume preview after focus: ${error.message}")
                        }
                    }
                }, cameraHandler)
            } catch (error: Exception) {
                publish("Autofocus failed: ${error.message}")
            }
        }
    }

    fun attach(view: TextureView) {
        if (textureView !== view) {
            textureView = view
            view.surfaceTextureListener = this
        }
        if (running && view.isAvailable) {
            val token = generation.get()
            cameraHandler?.post { openIfReady(token) }
        }
    }

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publish("Camera permission is required")
            return
        }
        running = true
        val token = generation.incrementAndGet()
        val thread = HandlerThread("TrackStudio-Preview").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
        publish("Starting camera-only compatibility test")
        cameraHandler?.post { openIfReady(token) }
    }

    fun stop() {
        if (!running && cameraThread == null) return
        running = false
        generation.incrementAndGet()
        val handler = cameraHandler
        val thread = cameraThread
        if (handler != null) {
            handler.post { closeCamera() }
            thread?.quitSafely()
        }
        cameraHandler = null
        cameraThread = null
        opening = false
        publish("Camera preview stopped")
    }

    private fun openIfReady(token: Long) {
        if (!running || token != generation.get() || opening || cameraDevice != null) return
        val view = textureView ?: return
        if (!view.isAvailable) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publish("Camera permission is missing")
            return
        }
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                publish("No camera was reported by this device")
                return
            }
            activeCameraId = cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    publish("Camera has no SurfaceTexture output configuration")
                    return
                }
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            if (sizes.isNullOrEmpty()) {
                publish("Camera has no preview sizes")
                return
            }
            val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
                .minByOrNull { kotlin.math.abs(it.width * 9 - it.height * 16) * 1000L +
                    kotlin.math.abs(it.width - 1280) }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }
                ?: sizes[0]
            val texture = view.surfaceTexture ?: return
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            previewSurface = surface
            opening = true
            publish("Opening camera $cameraId (${size.width}x${size.height})")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (!running || token != generation.get()) {
                        camera.close()
                        surface.release()
                        if (previewSurface === surface) previewSurface = null
                        return
                    }
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (!running || token != generation.get()) {
                                    session.close()
                                    return
                                }
                                captureSession = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    session.setRepeatingRequest(request, null, cameraHandler)
                                    publish("PREVIEW OK — camera-only test is running")
                                } catch (error: Exception) {
                                    publish("Preview request failed: ${error.javaClass.simpleName}: ${error.message}")
                                    closeCamera()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close()
                                publish("Camera preview session configuration failed")
                                closeCamera()
                            }
                        }, cameraHandler)
                    } catch (error: Exception) {
                        publish("Could not create preview session: ${error.javaClass.simpleName}: ${error.message}")
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    publish("Camera disconnected by Android")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    publish("Camera2 open error code: $error")
                }
            }, cameraHandler)
        } catch (error: SecurityException) {
            opening = false
            publish("Camera permission/security error: ${error.message}")
        } catch (error: Exception) {
            opening = false
            publish("Camera2 setup error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun closeCamera() {
        if (recording) try { mediaRecorder?.stop() } catch (_: RuntimeException) { recordingFile?.delete() }
        recording = false
        recordingStarting = false
        releaseRecorder()
        try { captureSession?.stopRepeating() } catch (_: Exception) { }
        try { captureSession?.close() } catch (_: Exception) { }
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) { }
        cameraDevice = null
        activeCameraId = null
        torchEnabled = false
        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null
        opening = false
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val token = generation.get()
        cameraHandler?.post { openIfReady(token) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraHandler?.post { closeCamera() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun publish(message: String) {
        Handler(context.mainLooper).post { onStatus(message) }
    }
}
