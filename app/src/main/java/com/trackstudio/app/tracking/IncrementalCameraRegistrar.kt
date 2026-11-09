package com.trackstudio.app.tracking

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import kotlin.math.sqrt

/**
 * Registers one frame against an existing sparse 3D map.
 *
 * This is the absolute-pose step used after a valid two-view reconstruction.
 * Unlike chaining recoverPose translations, PnP estimates each camera in the
 * same world coordinate system and therefore preserves the map's scale.
 *
 * This class is deliberately independent of frame decoding and UI state.
 */
internal object IncrementalCameraRegistrar {

    fun register(
        frameIndex: Int,
        frameWidth: Int,
        frameHeight: Int,
        featureTracks: List<CameraFeatureTrack>,
        landmarks: Collection<CameraLandmark>,
        cameraMatrix: Mat,
        minimumCorrespondences: Int = 30,
        minimumInlierRatio: Double = 0.25,
        maximumReprojectionErrorPx: Float = 4.0f,
        minimumOccupiedGridCells: Int = 4
    ): CameraRegistrationResult {
        require(frameWidth > 0 && frameHeight > 0)
        require(cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3 && !cameraMatrix.empty())

        val observationsByTrack = featureTracks.associate { track ->
            track.id to track.observations.firstOrNull { it.frameIndex == frameIndex }
        }
        val points3d = mutableListOf<Point3>()
        val points2d = mutableListOf<Point>()

        // A landmark is usable only when its source track has an observation
        // in this exact frame. Track IDs provide the 2D-3D association.
        for (landmark in landmarks) {
            val observation = observationsByTrack[landmark.trackId] ?: continue
            val p = landmark.position
            if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite() ||
                !observation.x.isFinite() || !observation.y.isFinite()
            ) continue
            points3d += Point3(p.x, p.y, p.z)
            points2d += Point(observation.x, observation.y)
        }

        val correspondenceCount = points3d.size
        if (correspondenceCount < minimumCorrespondences) {
            return CameraRegistrationResult.rejected(
                frameIndex, correspondenceCount, "Not enough 2D-3D correspondences"
            )
        }

        val objectPoints = MatOfPoint3f()
        val imagePoints = MatOfPoint2f()
        val distortion = MatOfDouble()
        val rotationVector = Mat()
        val translationVector = Mat()
        val inlierIndices = Mat()
        val inlierObjectPoints = MatOfPoint3f()
        val inlierImagePoints = MatOfPoint2f()
        val projectedPoints = MatOfPoint2f()
        val rotationMatrix = Mat()

        return try {
            objectPoints.fromArray(*points3d.toTypedArray())
            imagePoints.fromArray(*points2d.toTypedArray())

            val solved = Calib3d.solvePnPRansac(
                objectPoints,
                imagePoints,
                cameraMatrix,
                distortion,
                rotationVector,
                translationVector,
                false,
                2000,
                maximumReprojectionErrorPx,
                0.999,
                inlierIndices,
                Calib3d.SOLVEPNP_EPNP
            )
            if (!solved || inlierIndices.empty()) {
                return CameraRegistrationResult.rejected(
                    frameIndex, correspondenceCount, "PnP-RANSAC could not estimate a stable pose"
                )
            }

            val inlierIds = (0 until inlierIndices.rows()).mapNotNull { row ->
                inlierIndices.get(row, 0)?.firstOrNull()?.toInt()
            }.filter { it in points3d.indices }.distinct()

            val inlierCount = inlierIds.size
            val inlierRatio = inlierCount.toDouble() / correspondenceCount
            if (inlierCount < minimumCorrespondences || inlierRatio < minimumInlierRatio) {
                return CameraRegistrationResult.rejected(
                    frameIndex,
                    correspondenceCount,
                    "PnP pose failed inlier quality gate",
                    inlierCount,
                    inlierRatio
                )
            }

            val inlierObjectArray = inlierIds.map { points3d[it] }.toTypedArray()
            val inlierImageArray = inlierIds.map { points2d[it] }.toTypedArray()
            inlierObjectPoints.fromArray(*inlierObjectArray)
            inlierImagePoints.fromArray(*inlierImageArray)

            // Refit on RANSAC inliers with iterative PnP. This is a local
            // pose refinement step, not a substitute for bundle adjustment.
            val refined = Calib3d.solvePnP(
                inlierObjectPoints,
                inlierImagePoints,
                cameraMatrix,
                distortion,
                rotationVector,
                translationVector,
                true,
                Calib3d.SOLVEPNP_ITERATIVE
            )
            if (!refined) {
                return CameraRegistrationResult.rejected(
                    frameIndex,
                    correspondenceCount,
                    "Pose refinement failed",
                    inlierCount,
                    inlierRatio
                )
            }

            val occupiedCells = occupiedGridCells(
                inlierImageArray, frameWidth, frameHeight
            )
            if (occupiedCells < minimumOccupiedGridCells) {
                return CameraRegistrationResult.rejected(
                    frameIndex,
                    correspondenceCount,
                    "PnP inliers are too spatially concentrated",
                    inlierCount,
                    inlierRatio,
                    occupiedCells
                )
            }

            Calib3d.projectPoints(
                inlierObjectPoints,
                rotationVector,
                translationVector,
                cameraMatrix,
                distortion,
                projectedPoints
            )
            val projected = projectedPoints.toArray()
            if (projected.size != inlierImageArray.size) {
                return CameraRegistrationResult.rejected(
                    frameIndex, correspondenceCount, "Invalid reprojection result",
                    inlierCount, inlierRatio, occupiedCells
                )
            }

            var squaredError = 0.0
            val errors = DoubleArray(projected.size)
            for (i in projected.indices) {
                val dx = projected[i].x - inlierImageArray[i].x
                val dy = projected[i].y - inlierImageArray[i].y
                val error = sqrt(dx * dx + dy * dy)
                errors[i] = error
                squaredError += error * error
            }
            val rms = sqrt(squaredError / errors.size)
            val median = errors.sorted().let { sorted ->
                val middle = sorted.size / 2
                if (sorted.size % 2 == 0) {
                    (sorted[middle - 1] + sorted[middle]) * 0.5
                } else sorted[middle]
            }
            if (!rms.isFinite() || !median.isFinite() ||
                median > maximumReprojectionErrorPx ||
                rms > maximumReprojectionErrorPx * 1.5
            ) {
                return CameraRegistrationResult.rejected(
                    frameIndex,
                    correspondenceCount,
                    "Reprojection error exceeds pose quality limits",
                    inlierCount,
                    inlierRatio,
                    occupiedCells,
                    median,
                    rms
                )
            }

            Calib3d.Rodrigues(rotationVector, rotationMatrix)
            val rotation = DoubleArray(9)
            val translation = DoubleArray(3)
            rotationMatrix.get(0, 0, rotation)
            translationVector.get(0, 0, translation)

            CameraRegistrationResult(
                frameIndex = frameIndex,
                accepted = true,
                pose = CameraPose(
                    frameIndex,
                    rotation,
                    translation,
                    CameraPoseStatus.PNP_REGISTERED
                ),
                correspondenceCount = correspondenceCount,
                inlierCount = inlierCount,
                inlierRatio = inlierRatio,
                occupiedGridCells = occupiedCells,
                medianReprojectionErrorPx = median,
                rmsReprojectionErrorPx = rms,
                reason = "Registered against existing 3D map"
            )
        } finally {
            objectPoints.release()
            imagePoints.release()
            distortion.release()
            rotationVector.release()
            translationVector.release()
            inlierIndices.release()
            inlierObjectPoints.release()
            inlierImagePoints.release()
            projectedPoints.release()
            rotationMatrix.release()
        }
    }

    private fun occupiedGridCells(
        points: Array<Point>,
        width: Int,
        height: Int
    ): Int {
        val occupied = BooleanArray(16)
        for (point in points) {
            val column = ((point.x / width) * 4.0).toInt().coerceIn(0, 3)
            val row = ((point.y / height) * 4.0).toInt().coerceIn(0, 3)
            occupied[row * 4 + column] = true
        }
        return occupied.count { it }
    }
}

internal data class CameraRegistrationResult(
    val frameIndex: Int,
    val accepted: Boolean,
    val pose: CameraPose?,
    val correspondenceCount: Int,
    val inlierCount: Int,
    val inlierRatio: Double,
    val occupiedGridCells: Int,
    val medianReprojectionErrorPx: Double,
    val rmsReprojectionErrorPx: Double,
    val reason: String
) {
    companion object {
        fun rejected(
            frameIndex: Int,
            correspondenceCount: Int,
            reason: String,
            inlierCount: Int = 0,
            inlierRatio: Double = 0.0,
            occupiedGridCells: Int = 0,
            medianError: Double = Double.POSITIVE_INFINITY,
            rmsError: Double = Double.POSITIVE_INFINITY
        ) = CameraRegistrationResult(
            frameIndex = frameIndex,
            accepted = false,
            pose = null,
            correspondenceCount = correspondenceCount,
            inlierCount = inlierCount,
            inlierRatio = inlierRatio,
            occupiedGridCells = occupiedGridCells,
            medianReprojectionErrorPx = medianError,
            rmsReprojectionErrorPx = rmsError,
            reason = reason
        )
    }
}
