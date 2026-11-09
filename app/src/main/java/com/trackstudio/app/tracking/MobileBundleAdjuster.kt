package com.trackstudio.app.tracking

import org.opencv.core.Mat
import org.opencv.core.Point3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight mobile bundle-adjustment block optimizer.
 *
 * It performs robust per-landmark Gauss-Newton updates while holding camera
 * poses fixed. The solver alternates this landmark block with its existing
 * robust PnP camera-pose block. This is a bounded block-coordinate BA stage,
 * not a full sparse joint Schur-complement optimizer.
 */
internal object MobileBundleAdjuster {
    private const val HUBER_DELTA_PX = 3.0
    private const val MAX_OBSERVATION_ERROR_PX = 30.0
    private const val MAX_ITERATIONS = 5

    fun optimizeLandmarks(
        featureTracks: List<CameraFeatureTrack>,
        landmarks: List<CameraLandmark>,
        poses: List<CameraPose>,
        cameraMatrix: Mat
    ): List<CameraLandmark> {
        if (landmarks.isEmpty() || cameraMatrix.empty()) return landmarks
        val fx = cameraMatrix.get(0, 0)?.firstOrNull() ?: return landmarks
        val fy = cameraMatrix.get(1, 1)?.firstOrNull() ?: return landmarks
        val cx = cameraMatrix.get(0, 2)?.firstOrNull() ?: return landmarks
        val cy = cameraMatrix.get(1, 2)?.firstOrNull() ?: return landmarks
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0.0 || fy <= 0.0) return landmarks

        val poseByFrame = poses.asSequence()
            .filter { it.status != CameraPoseStatus.HELD_LAST_VALID &&
                it.status != CameraPoseStatus.UNREGISTERED }
            .associateBy { it.frameIndex }
        val observationsByTrack = featureTracks.associate { track ->
            track.id to track.observations
                .mapNotNull { observation ->
                    poseByFrame[observation.frameIndex]?.let { observation to it }
                }
        }

        return landmarks.map { landmark ->
            val observations = observationsByTrack[landmark.trackId].orEmpty()
                .filter { (observation, _) ->
                    observation.x.isFinite() && observation.y.isFinite()
                }
            if (observations.size < 2) return@map landmark

            var point = doubleArrayOf(
                landmark.position.x,
                landmark.position.y,
                landmark.position.z
            )
            if (point.any { !it.isFinite() }) return@map landmark

            var currentCost = robustCost(point, observations, fx, fy, cx, cy)
            if (!currentCost.isFinite()) return@map landmark

            repeat(MAX_ITERATIONS) {
                val normal = Array(3) { DoubleArray(3) }
                val gradient = DoubleArray(3)
                var used = 0

                for ((observation, pose) in observations) {
                    val camera = worldToCamera(point, pose) ?: continue
                    val z = camera[2]
                    if (z <= 1e-5) continue
                    val predictedX = fx * camera[0] / z + cx
                    val predictedY = fy * camera[1] / z + cy
                    val residualX = observation.x - predictedX
                    val residualY = observation.y - predictedY
                    val error = hypot(residualX, residualY)
                    if (!error.isFinite() || error > MAX_OBSERVATION_ERROR_PX) continue
                    val weight = if (error <= HUBER_DELTA_PX || error < 1e-9) {
                        1.0
                    } else {
                        HUBER_DELTA_PX / error
                    }

                    val invZ = 1.0 / z
                    val invZ2 = invZ * invZ
                    val jCamera = arrayOf(
                        doubleArrayOf(fx * invZ, 0.0, -fx * camera[0] * invZ2),
                        doubleArrayOf(0.0, fy * invZ, -fy * camera[1] * invZ2)
                    )
                    val jWorld = Array(2) { DoubleArray(3) }
                    for (row in 0..1) {
                        for (col in 0..2) {
                            jWorld[row][col] =
                                jCamera[row][0] * pose.rotation[col] +
                                jCamera[row][1] * pose.rotation[3 + col] +
                                jCamera[row][2] * pose.rotation[6 + col]
                        }
                    }

                    val residuals = doubleArrayOf(residualX, residualY)
                    for (row in 0..2) {
                        gradient[row] += weight *
                            (jWorld[0][row] * residuals[0] + jWorld[1][row] * residuals[1])
                        for (col in 0..2) {
                            normal[row][col] += weight *
                                (jWorld[0][row] * jWorld[0][col] +
                                    jWorld[1][row] * jWorld[1][col])
                        }
                    }
                    used++
                }

                if (used < 2) return@repeat
                for (i in 0..2) normal[i][i] += 1e-6
                val delta = solve3x3(normal, gradient) ?: return@repeat
                val magnitude = sqrt(delta.sumOf { it * it })
                if (!magnitude.isFinite() || magnitude < 1e-8) return@repeat
                val scale = if (magnitude > 1.0) 1.0 / magnitude else 1.0
                val candidate = DoubleArray(3) { point[it] + delta[it] * scale }
                val candidateCost = robustCost(candidate, observations, fx, fy, cx, cy)
                if (candidateCost.isFinite() && candidateCost < currentCost) {
                    point = candidate
                    currentCost = candidateCost
                } else {
                    return@repeat
                }
            }

            val finalErrors = observations.mapNotNull { (observation, pose) ->
                val camera = worldToCamera(point, pose) ?: return@mapNotNull null
                if (camera[2] <= 1e-5) return@mapNotNull null
                val dx = fx * camera[0] / camera[2] + cx - observation.x
                val dy = fy * camera[1] / camera[2] + cy - observation.y
                hypot(dx, dy).takeIf { it.isFinite() && it <= MAX_OBSERVATION_ERROR_PX }
            }
            if (finalErrors.size < 2) landmark else landmark.copy(
                position = Point3(point[0], point[1], point[2]),
                observationCount = finalErrors.size,
                meanReprojectionErrorPx = finalErrors.average()
            )
        }
    }

    private fun robustCost(
        point: DoubleArray,
        observations: List<Pair<CameraFeatureObservation, CameraPose>>,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double
    ): Double {
        var cost = 0.0
        var count = 0
        for ((observation, pose) in observations) {
            val camera = worldToCamera(point, pose) ?: continue
            if (camera[2] <= 1e-5) continue
            val dx = fx * camera[0] / camera[2] + cx - observation.x
            val dy = fy * camera[1] / camera[2] + cy - observation.y
            val error = hypot(dx, dy)
            if (!error.isFinite() || error > MAX_OBSERVATION_ERROR_PX) continue
            cost += if (error <= HUBER_DELTA_PX) {
                0.5 * error * error
            } else {
                HUBER_DELTA_PX * (error - 0.5 * HUBER_DELTA_PX)
            }
            count++
        }
        return if (count >= 2) cost / count else Double.POSITIVE_INFINITY
    }

    private fun worldToCamera(point: DoubleArray, pose: CameraPose): DoubleArray? {
        if (pose.rotation.size != 9 || pose.translation.size != 3) return null
        val r = pose.rotation
        val t = pose.translation
        val x = r[0] * point[0] + r[1] * point[1] + r[2] * point[2] + t[0]
        val y = r[3] * point[0] + r[4] * point[1] + r[5] * point[2] + t[1]
        val z = r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + t[2]
        return doubleArrayOf(x, y, z).takeIf { it.all(Double::isFinite) }
    }

    private fun solve3x3(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val a = Array(3) { row -> DoubleArray(4) { col ->
            if (col == 3) rhs[row] else matrix[row][col]
        } }
        for (pivot in 0..2) {
            var best = pivot
            for (row in pivot + 1..2) {
                if (abs(a[row][pivot]) > abs(a[best][pivot])) best = row
            }
            if (abs(a[best][pivot]) < 1e-12) return null
            if (best != pivot) {
                val temp = a[pivot]
                a[pivot] = a[best]
                a[best] = temp
            }
            val divisor = a[pivot][pivot]
            for (col in pivot..3) a[pivot][col] /= divisor
            for (row in 0..2) {
                if (row == pivot) continue
                val factor = a[row][pivot]
                for (col in pivot..3) a[row][col] -= factor * a[pivot][col]
            }
        }
        return DoubleArray(3) { a[it][3] }.takeIf { it.all(Double::isFinite) }
    }
}
