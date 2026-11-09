package com.trackstudio.app.tracking

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Core
import org.opencv.core.MatOfPoint2f
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Provisional sparse triangulation for the current pairwise-motion stage.
 *
 * Candidate views are selected by triangulation/parallax angle, not merely
 * camera-centre distance. Each candidate point is checked with multi-view
 * cheirality and robust reprojection filtering.
 *
 * IMPORTANT: the current pairwise recoverPose trajectory still has arbitrary
 * per-step translation scale. This class therefore produces diagnostic
 * landmarks only; it is not the final incremental-SfM reconstruction.
 */
internal object SfMTriangulator {

    fun triangulate(
        featureTracks: List<CameraFeatureTrack>,
        poses: List<CameraPose>,
        cameraMatrix: Mat,
        maximumReprojectionErrorPx: Double = 4.0,
        minimumParallaxDegrees: Double = 1.0,
        minimumObservationInlierRatio: Double = 0.70
    ): TriangulationResult {
        require(cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3 && !cameraMatrix.empty()) {
            "Camera matrix must be a non-empty 3x3 matrix."
        }
        val poseByFrame = poses.associateBy { it.frameIndex }
        val landmarks = mutableListOf<CameraLandmark>()
        var rejectedShort = 0
        var rejectedMissingPose = 0
        var rejectedGeometry = 0
        var rejectedReprojection = 0

        for (track in featureTracks) {
            if (track.observations.size < 2) {
                rejectedShort++
                continue
            }
            // A held pose preserves frame-index alignment but is not a real
            // geometric observation. Never use it to triangulate landmarks.
            val validObservations = track.observations.mapNotNull { observation ->
                poseByFrame[observation.frameIndex]
                    ?.takeIf {
                        it.status != CameraPoseStatus.HELD_LAST_VALID &&
                            it.status != CameraPoseStatus.UNREGISTERED
                    }
                    ?.let { observation to it }
            }
            if (validObservations.size < 2) {
                rejectedMissingPose++
                continue
            }

            // Very long tracks are sampled evenly for pair selection to keep
            // the per-track search bounded on long mobile clips.
            val candidates = sampleObservations(validObservations, 32)
            var selectedPair: Pair<
                Pair<CameraFeatureObservation, CameraPose>,
                Pair<CameraFeatureObservation, CameraPose>
            >? = null
            var bestParallax = -1.0

            for (firstIndex in 0 until candidates.lastIndex) {
                for (secondIndex in firstIndex + 1 until candidates.size) {
                    val first = candidates[firstIndex]
                    val second = candidates[secondIndex]
                    if (cameraCenterDistanceSquared(first.second, second.second) < 1e-10) {
                        continue
                    }
                    val parallax = viewingRayAngleDegrees(
                        first.first, first.second, second.first, second.second, cameraMatrix
                    )
                    if (parallax > bestParallax) {
                        bestParallax = parallax
                        selectedPair = first to second
                    }
                }
            }

            val pair = selectedPair
            if (pair == null || bestParallax < minimumParallaxDegrees ||
                bestParallax >= 179.0
            ) {
                rejectedGeometry++
                continue
            }

            val (firstObservation, firstPose) = pair.first
            val (lastObservation, lastPose) = pair.second
            val projection1 = projectionMatrix(cameraMatrix, firstPose)
            val projection2 = projectionMatrix(cameraMatrix, lastPose)
            val image1 = MatOfPoint2f(Point(firstObservation.x, firstObservation.y))
            val image2 = MatOfPoint2f(Point(lastObservation.x, lastObservation.y))
            val homogeneous = Mat()

            try {
                Calib3d.triangulatePoints(projection1, projection2, image1, image2, homogeneous)
                if (homogeneous.rows() != 4 || homogeneous.cols() != 1) {
                    rejectedGeometry++
                    continue
                }
                val wData = homogeneous.get(3, 0)
                if (wData == null || wData.isEmpty()) {
                    rejectedGeometry++
                    continue
                }
                val w = wData[0]
                if (!w.isFinite() || kotlin.math.abs(w) < 1e-9) {
                    rejectedGeometry++
                    continue
                }
                val x = (homogeneous.get(0, 0)?.get(0) ?: Double.NaN) / w
                val y = (homogeneous.get(1, 0)?.get(0) ?: Double.NaN) / w
                val z = (homogeneous.get(2, 0)?.get(0) ?: Double.NaN) / w
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                    rejectedGeometry++
                    continue
                }

                val point = doubleArrayOf(x, y, z)
                val reprojectionErrors = mutableListOf<Double>()
                for ((observation, pose) in validObservations) {
                    val cameraPoint = transform(pose, point)
                    if (cameraPoint[2] <= 1e-6) continue
                    val error = reprojectionError(cameraMatrix, cameraPoint, observation)
                    if (error.isFinite()) reprojectionErrors += error
                }

                if (reprojectionErrors.size < 2) {
                    rejectedGeometry++
                    continue
                }
                val inlierErrors = reprojectionErrors.filter { it <= maximumReprojectionErrorPx }
                val inlierRatio = inlierErrors.size.toDouble() / validObservations.size
                if (inlierErrors.size < 2 || inlierRatio < minimumObservationInlierRatio) {
                    rejectedReprojection++
                    continue
                }

                // Median is robust to a small number of bad observations;
                // mean inlier error remains useful as a familiar diagnostic.
                val medianError = median(inlierErrors)
                if (!medianError.isFinite() || medianError > maximumReprojectionErrorPx) {
                    rejectedReprojection++
                    continue
                }
                val meanInlierError = inlierErrors.average()

                landmarks += CameraLandmark(
                    trackId = track.id,
                    position = Point3(x, y, z),
                    observationCount = inlierErrors.size,
                    meanReprojectionErrorPx = meanInlierError,
                    triangulationAngleDegrees = bestParallax
                )
            } finally {
                projection1.release()
                projection2.release()
                image1.release()
                image2.release()
                homogeneous.release()
            }
        }

        return TriangulationResult(
            landmarks = landmarks,
            stats = TriangulationStats(
                inputTracks = featureTracks.size,
                triangulatedLandmarks = landmarks.size,
                rejectedShortTracks = rejectedShort,
                rejectedMissingPoses = rejectedMissingPose,
                rejectedInvalidGeometry = rejectedGeometry,
                rejectedReprojection = rejectedReprojection,
                meanReprojectionErrorPx = if (landmarks.isEmpty()) 0.0
                    else landmarks.map { it.meanReprojectionErrorPx }.average(),
                meanTriangulationAngleDegrees = if (landmarks.isEmpty()) 0.0
                    else landmarks.map { it.triangulationAngleDegrees }.average()
            )
        )
    }

    private fun sampleObservations(
        observations: List<Pair<CameraFeatureObservation, CameraPose>>,
        maximum: Int
    ): List<Pair<CameraFeatureObservation, CameraPose>> {
        if (observations.size <= maximum) return observations
        return (0 until maximum).map { index ->
            val sourceIndex = index * (observations.size - 1) / (maximum - 1)
            observations[sourceIndex]
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }

    private fun viewingRayAngleDegrees(
        firstObservation: CameraFeatureObservation,
        firstPose: CameraPose,
        secondObservation: CameraFeatureObservation,
        secondPose: CameraPose,
        cameraMatrix: Mat
    ): Double {
        val fx = cameraMatrix.get(0, 0)[0]
        val skew = cameraMatrix.get(0, 1)[0]
        val cx = cameraMatrix.get(0, 2)[0]
        val fy = cameraMatrix.get(1, 1)[0]
        val cy = cameraMatrix.get(1, 2)[0]
        if (kotlin.math.abs(fx) < 1e-12 || kotlin.math.abs(fy) < 1e-12) return 0.0

        fun worldRay(observation: CameraFeatureObservation, pose: CameraPose): DoubleArray {
            val normalizedY = (observation.y - cy) / fy
            val normalizedX = (observation.x - cx - skew * normalizedY) / fx
            val length = sqrt(normalizedX * normalizedX + normalizedY * normalizedY + 1.0)
            val cameraRay = doubleArrayOf(normalizedX / length, normalizedY / length, 1.0 / length)
            val r = pose.rotation
            // Direction in world coordinates is R^T times the camera ray.
            val world = doubleArrayOf(
                r[0] * cameraRay[0] + r[3] * cameraRay[1] + r[6] * cameraRay[2],
                r[1] * cameraRay[0] + r[4] * cameraRay[1] + r[7] * cameraRay[2],
                r[2] * cameraRay[0] + r[5] * cameraRay[1] + r[8] * cameraRay[2]
            )
            val norm = sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2])
            return doubleArrayOf(world[0] / norm, world[1] / norm, world[2] / norm)
        }

        val a = worldRay(firstObservation, firstPose)
        val b = worldRay(secondObservation, secondPose)
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun cameraCenterDistanceSquared(first: CameraPose, second: CameraPose): Double {
        fun center(pose: CameraPose): DoubleArray {
            val r = pose.rotation
            val t = pose.translation
            return doubleArrayOf(
                -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]),
                -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]),
                -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2])
            )
        }
        val a = center(first)
        val b = center(second)
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun projectionMatrix(cameraMatrix: Mat, pose: CameraPose): Mat {
        val extrinsic = Mat(3, 4, CvType.CV_64F)
        val values = DoubleArray(12)
        for (row in 0..2) {
            for (column in 0..2) {
                values[row * 4 + column] = pose.rotation[row * 3 + column]
            }
            values[row * 4 + 3] = pose.translation[row]
        }
        extrinsic.put(0, 0, *values)
        val projection = Mat()
        val zero = Mat.zeros(3, 4, CvType.CV_64F)
        try {
            Core.gemm(cameraMatrix, extrinsic, 1.0, zero, 0.0, projection)
        } finally {
            zero.release()
            extrinsic.release()
        }
        return projection
    }

    private fun transform(pose: CameraPose, point: DoubleArray): DoubleArray {
        val r = pose.rotation
        val t = pose.translation
        return doubleArrayOf(
            r[0] * point[0] + r[1] * point[1] + r[2] * point[2] + t[0],
            r[3] * point[0] + r[4] * point[1] + r[5] * point[2] + t[1],
            r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + t[2]
        )
    }

    private fun reprojectionError(
        cameraMatrix: Mat,
        cameraPoint: DoubleArray,
        observation: CameraFeatureObservation
    ): Double {
        val fx = cameraMatrix.get(0, 0)[0]
        val skew = cameraMatrix.get(0, 1)[0]
        val cx = cameraMatrix.get(0, 2)[0]
        val fy = cameraMatrix.get(1, 1)[0]
        val cy = cameraMatrix.get(1, 2)[0]
        val u = (fx * cameraPoint[0] + skew * cameraPoint[1]) / cameraPoint[2] + cx
        val v = fy * cameraPoint[1] / cameraPoint[2] + cy
        val dx = u - observation.x
        val dy = v - observation.y
        return sqrt(dx * dx + dy * dy)
    }
}

internal data class TriangulationResult(
    val landmarks: List<CameraLandmark>,
    val stats: TriangulationStats
)

data class CameraLandmark(
    val trackId: Int,
    val position: Point3,
    val observationCount: Int,
    val meanReprojectionErrorPx: Double,
    val triangulationAngleDegrees: Double = 0.0
)

data class TriangulationStats(
    val inputTracks: Int = 0,
    val triangulatedLandmarks: Int = 0,
    val rejectedShortTracks: Int = 0,
    val rejectedMissingPoses: Int = 0,
    val rejectedInvalidGeometry: Int = 0,
    val rejectedReprojection: Int = 0,
    val meanReprojectionErrorPx: Double = 0.0,
    val meanTriangulationAngleDegrees: Double = 0.0
)
