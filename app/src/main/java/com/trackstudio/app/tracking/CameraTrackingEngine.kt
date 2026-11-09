package com.trackstudio.app.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * Public, UI-independent entry point for TrackStudio camera solving.
 *
 * The solver selects a well-conditioned initialization pair, builds a sparse
 * map, then registers subsequent cameras with PnP in that shared map. The
 * arbitrary unit baseline is a reconstruction gauge, not a metric distance.
 */
class CameraTrackingEngine(
    private val maxFeatures: Int = 1800,
    private val ratioThreshold: Float = 0.78f
) : ReconstructionEngine {
    override suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        IncrementalSfMSolver(maxFeatures, ratioThreshold).solve(
            frameCount = frameCount,
            cameraMatrix = cameraMatrix,
            frameProvider = frameProvider,
            onProgress = onProgress
        )
    }
}

data class CameraTrackingProgress(
    /** Number of source frames processed in the current pass. */
    val frameIndex: Int,
    val frameCount: Int,
    val phase: String,
    val overallFraction: Float? = null,
    val completedFrames: Int = frameIndex,
    val totalFrames: Int = frameCount
) {
    val frameLabel: String
        get() = "${completedFrames.coerceIn(0, totalFrames)}/${totalFrames.coerceAtLeast(0)} frames"

    val fraction: Float
        get() = overallFraction?.coerceIn(0f, 1f)
            ?: if (frameCount <= 0) 0f
            else (frameIndex.toFloat() / frameCount).coerceIn(0f, 1f)
}

enum class CameraPoseStatus {
    REFERENCE,
    RELATIVE_ESTIMATE,
    PNP_REGISTERED,
    HELD_LAST_VALID,
    UNREGISTERED
}

/** World-to-camera transform: X_camera = R * X_world + t. */
data class CameraPose(
    val frameIndex: Int,
    val rotation: DoubleArray,
    val translation: DoubleArray,
    val status: CameraPoseStatus = CameraPoseStatus.RELATIVE_ESTIMATE
)

data class FrameTrackingDiagnostic(
    val frameIndex: Int,
    val detectedFeatures: Int,
    val matchedFeatures: Int,
    val inlierFeatures: Int,
    val status: String
)

data class CameraTrackingResult(
    val poses: List<CameraPose>,
    val diagnostics: List<FrameTrackingDiagnostic>,
    val processedFrames: Int,
    val solvedFramePairs: Int,
    val totalMatches: Int,
    val totalInliers: Int,
    val inlierRatio: Double,
    val isCameraMotionEstimated: Boolean,
    val featureTracks: List<CameraFeatureTrack> = emptyList(),
    val landmarks: List<CameraLandmark> = emptyList(),
    val triangulationStats: TriangulationStats = TriangulationStats(),
    val initialPairFrames: Pair<Int, Int>? = null,
    val registeredCameraCount: Int = 0,
    /** Global rotations estimated from the verified sparse view graph; not yet fused with PnP translations. */
    val globalRotationsByFrame: Map<Int, DoubleArray> = emptyMap(),
    val viewGraphEdgeCount: Int = 0,
    val viewGraphAcceptedEdgeCount: Int = 0,
    val globalRotationIterations: Int = 0,
    val globalPositioningApplied: Boolean = false,
    val globalPositioningInitialCost: Double = Double.NaN,
    val globalPositioningFinalCost: Double = Double.NaN,
    val globalPositioningIterations: Int = 0,
    val globalPositioningStatus: String = "Not run"
)
