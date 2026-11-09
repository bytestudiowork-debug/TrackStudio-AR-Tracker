package com.trackstudio.artracker

import com.trackstudio.app.tracking.CameraTrackingEngine
import com.trackstudio.app.tracking.CameraTrackingProgress
import com.trackstudio.app.tracking.CameraTrackingResult
import com.trackstudio.app.tracking.OpenCvRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File

/**
 * App-facing adapter from a completed camera frame archive to the offline
 * multi-view reconstruction engine. Camera intrinsics must match the archived
 * analysis stream dimensions.
 */
internal class TrackStudioReconstructionService {
    suspend fun reconstruct(
        frameArchive: File,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        skew: Double = 0.0,
        onProgress: suspend (CameraTrackingProgress) -> Unit = {}
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        require(frameArchive.isFile) { "The recording frame archive does not exist." }
        require(listOf(fx, fy, cx, cy, skew).all { it.isFinite() } && fx > 0.0 && fy > 0.0) {
            "Valid camera intrinsics are required before reconstruction."
        }
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV could not be initialized on this device." }

        BasaltFrameArchiveRandomAccess(frameArchive).use { archive ->
            require(archive.frameCount >= 3) {
                "At least three archived camera frames are required for 3D reconstruction."
            }
            val cameraMatrix = Mat(3, 3, CvType.CV_64F)
            try {
                cameraMatrix.put(
                    0, 0,
                    *doubleArrayOf(
                        fx, skew, cx,
                        0.0, fy, cy,
                        0.0, 0.0, 1.0
                    )
                )
                CameraTrackingEngine().solve(
                    frameCount = archive.frameCount,
                    cameraMatrix = cameraMatrix,
                    frameProvider = { frameIndex -> archive.readFrame(frameIndex) },
                    onProgress = onProgress
                )
            } finally {
                cameraMatrix.release()
            }
        }
    }
}
