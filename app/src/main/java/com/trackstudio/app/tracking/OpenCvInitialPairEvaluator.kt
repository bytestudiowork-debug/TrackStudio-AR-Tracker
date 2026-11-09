package com.trackstudio.app.tracking

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.KeyPoint
import org.opencv.features2d.BFMatcher
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * OpenCV-backed two-view geometry evaluator for initialization candidates.
 *
 * Descriptor matching proposes correspondences; Essential-matrix RANSAC
 * verifies calibrated epipolar geometry; recoverPose applies cheirality.
 * A competing homography estimates how much of the pair can be explained
 * by a single plane / pure-rotation model. The pure Kotlin selector then
 * decides whether the measurements are suitable for 3D initialization.
 *
 * Inputs remain caller-owned. All OpenCV objects created here are released
 * before returning, so candidate evaluation does not retain native memory.
 */
internal object OpenCvInitialPairEvaluator {

    fun evaluate(
        firstFrame: Int,
        secondFrame: Int,
        firstKeypoints: Array<KeyPoint>,
        firstDescriptors: Mat,
        secondKeypoints: Array<KeyPoint>,
        secondDescriptors: Mat,
        cameraMatrix: Mat,
        frameWidth: Int,
        frameHeight: Int,
        policy: InitialPairPolicy = InitialPairPolicy(),
        ratioThreshold: Float = 0.78f,
        maximumMatches: Int = 2500,
        ransacThresholdPx: Double = 1.0
    ): InitialPairEvaluation {
        require(firstFrame >= 0 && secondFrame > firstFrame)
        require(frameWidth > 0 && frameHeight > 0)
        require(!cameraMatrix.empty() && cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3)

        if (firstDescriptors.empty() || secondDescriptors.empty() ||
            firstKeypoints.isEmpty() || secondKeypoints.isEmpty()
        ) {
            return rejected(firstFrame, secondFrame, "Missing descriptors or keypoints")
        }

        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val knn = mutableListOf<MatOfDMatch>()
        val uniqueTrainIndices = HashSet<Int>()
        val matchedFirst = mutableListOf<org.opencv.core.Point>()
        val matchedSecond = mutableListOf<org.opencv.core.Point>()

        try {
            matcher.knnMatch(firstDescriptors, secondDescriptors, knn, 2)
            for (row in knn) {
                val pair = row.toArray()
                if (pair.size < 2) continue
                val best = pair[0]
                val second = pair[1]
                if (best.distance >= ratioThreshold * second.distance) continue
                if (best.queryIdx !in firstKeypoints.indices ||
                    best.trainIdx !in secondKeypoints.indices ||
                    !uniqueTrainIndices.add(best.trainIdx)
                ) continue

                matchedFirst += firstKeypoints[best.queryIdx].pt
                matchedSecond += secondKeypoints[best.trainIdx].pt
                if (matchedFirst.size >= maximumMatches.coerceAtLeast(1)) break
            }
        } finally {
            knn.forEach { it.release() }
            matcher.clear()
        }

        val matchCount = matchedFirst.size
        if (matchCount < policy.minimumInliers) {
            return rejected(firstFrame, secondFrame, "Insufficient descriptor matches", matchCount)
        }

        val points1 = MatOfPoint2f()
        val points2 = MatOfPoint2f()
        val essentialMask = Mat()
        val cheiralityMask = Mat()
        val homographyMask = Mat()
        val rotation = Mat()
        val translation = Mat()

        try {
            points1.fromList(matchedFirst)
            points2.fromList(matchedSecond)

            val essentialEstimate = Calib3d.findEssentialMat(
                points1,
                points2,
                cameraMatrix,
                Calib3d.RANSAC,
                0.999,
                ransacThresholdPx,
                1500,
                essentialMask
            )
            try {
                if (essentialEstimate.empty() || essentialEstimate.cols() != 3 ||
                    essentialEstimate.rows() < 3 || essentialEstimate.rows() % 3 != 0
                ) {
                    return rejected(firstFrame, secondFrame, "Essential matrix estimation failed", matchCount)
                }

                val essentialInlierCount = Core.countNonZero(essentialMask)
                if (essentialInlierCount < policy.minimumInliers) {
                    return rejected(firstFrame, secondFrame, "Too few essential-matrix inliers", matchCount)
                }

                val estimatedHomography = Calib3d.findHomography(
                    points1, points2, Calib3d.RANSAC, 3.0, homographyMask, 1000, 0.995
                )
                estimatedHomography.release()
                val homographyInliers = if (homographyMask.empty()) 0
                    else Core.countNonZero(homographyMask)
                val homographyRatio = homographyInliers.toDouble() / matchCount

                var bestPoseInliers = 0
                var bestPoseMask: Mat? = null
                var bestRotation: Mat? = null
                var bestTranslation: Mat? = null
                try {
                    for (candidateIndex in 0 until essentialEstimate.rows() / 3) {
                        val candidate = essentialEstimate
                            .rowRange(candidateIndex * 3, candidateIndex * 3 + 3)
                            .clone()
                        val mask = essentialMask.clone()
                        val candidateRotation = Mat()
                        val candidateTranslation = Mat()
                        try {
                            val inliers = Calib3d.recoverPose(
                                candidate, points1, points2, cameraMatrix,
                                candidateRotation, candidateTranslation, mask
                            )
                            if (inliers > bestPoseInliers) {
                                bestPoseMask?.release()
                                bestRotation?.release()
                                bestTranslation?.release()
                                bestPoseInliers = inliers
                                bestPoseMask = mask.clone()
                                bestRotation = candidateRotation.clone()
                                bestTranslation = candidateTranslation.clone()
                            }
                        } finally {
                            candidate.release()
                            mask.release()
                            candidateRotation.release()
                            candidateTranslation.release()
                        }
                    }

                    if (bestPoseInliers == 0 || bestPoseMask == null ||
                        bestRotation == null || bestTranslation == null
                    ) {
                        return rejected(firstFrame, secondFrame, "Cheirality check failed", matchCount)
                    }
                    bestPoseMask.copyTo(cheiralityMask)
                    bestRotation.copyTo(rotation)
                    bestTranslation.copyTo(translation)
                } finally {
                    bestPoseMask?.release()
                    bestRotation?.release()
                    bestTranslation?.release()
                }

                val inlierIndices = (0 until cheiralityMask.rows()).mapNotNull { index ->
                    val value = cheiralityMask.get(index, 0)?.firstOrNull()
                    if (value != null && value != 0.0) index else null
                }
                val inlierCount = inlierIndices.size
                val coverage = occupiedGridCells(
                    inlierIndices.map { matchedFirst[it] }, frameWidth, frameHeight
                )
                val parallax = inlierIndices.map { index ->
                    rayAngleDegrees(
                        matchedFirst[index], matchedSecond[index],
                        rotation, cameraMatrix
                    )
                }.filter { it.isFinite() }.sorted()

                if (parallax.isEmpty()) {
                    return rejected(firstFrame, secondFrame, "No valid parallax measurements", matchCount)
                }
                val medianParallax = percentile(parallax, 0.50)
                val upperQuartileParallax = percentile(parallax, 0.75)

                val t = DoubleArray(3)
                translation.get(0, 0, t)
                val translationNorm = sqrt(t.sumOf { it * it }).coerceAtLeast(1e-12)
                val forwardMotionRatio = (kotlin.math.abs(t[2]) / translationNorm).coerceIn(0.0, 1.0)

                val relativeRotation = DoubleArray(9)
                val relativeTranslationDirection = DoubleArray(3)
                rotation.get(0, 0, relativeRotation)
                translation.get(0, 0, relativeTranslationDirection)
                val candidate = InitialPairCandidate(
                    firstFrame = firstFrame,
                    secondFrame = secondFrame,
                    matchedCount = matchCount,
                    inlierCount = inlierCount,
                    coveredGridCells = coverage,
                    medianParallaxDegrees = medianParallax,
                    upperQuartileParallaxDegrees = upperQuartileParallax,
                    cheiralityRatio = inlierCount.toDouble() / essentialInlierCount.coerceAtLeast(1),
                    homographyInlierRatio = homographyRatio,
                    forwardMotionRatio = forwardMotionRatio,
                    relativeRotation = relativeRotation,
                    relativeTranslationDirection = relativeTranslationDirection
                )
                return InitialPairSelector.evaluate(candidate, policy)
            } finally {
                essentialEstimate.release()
            }
        } finally {
            points1.release()
            points2.release()
            essentialMask.release()
            cheiralityMask.release()
            homographyMask.release()
            rotation.release()
            translation.release()
        }
    }


    private fun rejected(
        first: Int,
        second: Int,
        reason: String,
        matches: Int = 0
    ): InitialPairEvaluation {
        val candidate = InitialPairCandidate(
            firstFrame = first,
            secondFrame = second,
            matchedCount = matches,
            inlierCount = 0,
            coveredGridCells = 0,
            medianParallaxDegrees = 0.0,
            upperQuartileParallaxDegrees = 0.0,
            cheiralityRatio = 0.0,
            homographyInlierRatio = 0.0,
            forwardMotionRatio = 1.0
        )
        return InitialPairEvaluation(candidate, false, 0.0, reason)
    }

    private fun occupiedGridCells(
        points: List<org.opencv.core.Point>,
        width: Int,
        height: Int
    ): Int {
        if (width <= 0 || height <= 0) return 0
        val cells = BooleanArray(16)
        for (point in points) {
            val x = (point.x * 4.0 / width).toInt().coerceIn(0, 3)
            val y = (point.y * 4.0 / height).toInt().coerceIn(0, 3)
            cells[y * 4 + x] = true
        }
        return cells.count { it }
    }

    private fun rayAngleDegrees(
        first: org.opencv.core.Point,
        second: org.opencv.core.Point,
        rotation: Mat,
        cameraMatrix: Mat
    ): Double {
        val fx = cameraMatrix.get(0, 0)[0]
        val fy = cameraMatrix.get(1, 1)[0]
        val cx = cameraMatrix.get(0, 2)[0]
        val cy = cameraMatrix.get(1, 2)[0]
        if (kotlin.math.abs(fx) < 1e-12 || kotlin.math.abs(fy) < 1e-12) return 0.0

        fun normalize(point: org.opencv.core.Point): DoubleArray {
            val x = (point.x - cx) / fx
            val y = (point.y - cy) / fy
            val n = sqrt(x * x + y * y + 1.0)
            return doubleArrayOf(x / n, y / n, 1.0 / n)
        }

        val a = normalize(first)
        val bCamera = normalize(second)
        val r = DoubleArray(9)
        rotation.get(0, 0, r)
        // recoverPose returns X2 = R * X1 + t; rotate the second ray
        // back into camera-1 coordinates before comparing viewing rays.
        val b = doubleArrayOf(
            r[0] * bCamera[0] + r[3] * bCamera[1] + r[6] * bCamera[2],
            r[1] * bCamera[0] + r[4] * bCamera[1] + r[7] * bCamera[2],
            r[2] * bCamera[0] + r[5] * bCamera[1] + r[8] * bCamera[2]
        )
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2])
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
