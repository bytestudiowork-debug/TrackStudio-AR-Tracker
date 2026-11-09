package com.trackstudio.app.tracking

import kotlin.math.ln

/**
 * Quality gate and ranking policy for two-view SfM initialization.
 *
 * Metrics are produced by the OpenCV pair evaluator. Keeping ranking pure Kotlin
 * makes the policy deterministic, inexpensive, and independently testable.
 * This selector deliberately rejects weak geometry instead of returning the
 * least-bad pair and pretending that it is a valid reconstruction.
 */
internal object InitialPairSelector {

    fun select(
        candidates: List<InitialPairCandidate>,
        policy: InitialPairPolicy = InitialPairPolicy()
    ): InitialPairSelection {
        if (candidates.isEmpty()) {
            return InitialPairSelection(null, emptyList(), "No candidate frame pairs were evaluated")
        }

        val evaluations = candidates.map { candidate ->
            evaluate(candidate, policy)
        }
        val accepted = evaluations
            .filter { it.accepted }
            .sortedWith(
                compareByDescending<InitialPairEvaluation> { it.score }
                    .thenByDescending { it.candidate.inlierCount }
                    .thenBy { it.candidate.frameGap }
                    .thenBy { it.candidate.firstFrame }
            )
        val best = accepted.firstOrNull()
        return if (best == null) {
            InitialPairSelection(
                selected = null,
                evaluations = evaluations,
                reason = "No pair passed the geometric initialization quality gates"
            )
        } else {
            InitialPairSelection(
                selected = best.candidate,
                evaluations = evaluations,
                reason = "Selected the best-conditioned pair from ${accepted.size} valid candidates"
            )
        }
    }

    fun evaluate(
        candidate: InitialPairCandidate,
        policy: InitialPairPolicy = InitialPairPolicy()
    ): InitialPairEvaluation {
        val rejection = when {
            candidate.firstFrame < 0 || candidate.secondFrame <= candidate.firstFrame ->
                "Invalid frame order"
            candidate.frameGap < policy.minimumFrameGap ->
                "Frames are too close in time"
            candidate.matchedCount <= 0 || candidate.inlierCount < policy.minimumInliers ->
                "Insufficient verified correspondences"
            candidate.inlierCount > candidate.matchedCount ->
                "Inlier count exceeds match count"
            candidate.inlierRatio < policy.minimumInlierRatio ->
                "Low geometric inlier ratio"
            candidate.coveredGridCells < policy.minimumCoveredGridCells ->
                "Features are concentrated in a small image region"
            candidate.coveredGridCells > policy.gridCellCount ->
                "Invalid image coverage metric"
            !candidate.cheiralityRatio.isFinite() ||
                candidate.cheiralityRatio !in 0.0..1.0 ->
                "Invalid cheirality metric"
            !candidate.homographyInlierRatio.isFinite() ||
                candidate.homographyInlierRatio !in 0.0..1.0 ->
                "Invalid homography metric"
            !candidate.forwardMotionRatio.isFinite() ||
                candidate.forwardMotionRatio !in 0.0..1.0 ->
                "Invalid forward-motion metric"
            !candidate.medianParallaxDegrees.isFinite() ||
                candidate.medianParallaxDegrees < policy.minimumMedianParallaxDegrees ->
                "Insufficient median triangulation parallax"
            !candidate.upperQuartileParallaxDegrees.isFinite() ||
                candidate.upperQuartileParallaxDegrees < policy.minimumUpperQuartileParallaxDegrees ->
                "Too few points have useful triangulation parallax"
            candidate.cheiralityRatio < policy.minimumCheiralityRatio ->
                "Too few points satisfy positive-depth cheirality"
            candidate.forwardMotionRatio > policy.maximumForwardMotionRatio ->
                "Pair is dominated by degenerate forward motion"
            candidate.homographyInlierRatio >= policy.planarDominanceRatio &&
                candidate.upperQuartileParallaxDegrees < policy.planarParallaxEscapeDegrees ->
                "Homography-dominated pair has insufficient non-planar parallax"
            else -> null
        }

        if (rejection != null) {
            return InitialPairEvaluation(candidate, accepted = false, score = 0.0, reason = rejection)
        }

        val countScore = (ln(1.0 + candidate.inlierCount) /
            ln(1.0 + policy.inlierCountSaturation)).coerceIn(0.0, 1.0)
        val ratioScore = ((candidate.inlierRatio - policy.minimumInlierRatio) /
            (policy.targetInlierRatio - policy.minimumInlierRatio).coerceAtLeast(1e-6))
            .coerceIn(0.0, 1.0)
        val coverageScore = (candidate.coveredGridCells.toDouble() /
            policy.gridCellCount).coerceIn(0.0, 1.0)
        val medianParallaxScore = (candidate.medianParallaxDegrees /
            policy.targetMedianParallaxDegrees).coerceIn(0.0, 1.0)
        val upperParallaxScore = (candidate.upperQuartileParallaxDegrees /
            policy.targetUpperQuartileParallaxDegrees).coerceIn(0.0, 1.0)
        val parallaxScore = 0.65 * medianParallaxScore + 0.35 * upperParallaxScore
        val cheiralityScore = candidate.cheiralityRatio.coerceIn(0.0, 1.0)
        val homographyPenalty = if (
            candidate.homographyInlierRatio >= policy.planarDominanceRatio
        ) 0.65 else 1.0

        // Balanced scoring avoids selecting a pair on raw match count alone.
        val score = (
            0.18 * countScore +
                0.17 * ratioScore +
                0.18 * coverageScore +
                0.27 * parallaxScore +
                0.20 * cheiralityScore
            ) * homographyPenalty

        return InitialPairEvaluation(
            candidate = candidate,
            accepted = true,
            score = score,
            reason = "Passed correspondence, coverage, parallax and cheirality gates"
        )
    }

    /**
     * Creates a bounded, deterministic set of temporal pairs for a coarse
     * initialization search. The caller still evaluates image geometry for
     * every pair; frame distance is only a search schedule, never a quality score.
     */
    fun temporalCandidates(
        frameCount: Int,
        anchorCount: Int = 8,
        offsets: List<Int> = listOf(2, 4, 6, 9, 13, 18, 24, 32),
        maximumPairs: Int = 64
    ): List<Pair<Int, Int>> {
        if (frameCount < 2 || anchorCount <= 0 || maximumPairs <= 0) return emptyList()
        val validOffsets = offsets.filter { it > 0 }.distinct().sorted()
        if (validOffsets.isEmpty()) return emptyList()

        val lastAnchor = (frameCount - 2).coerceAtLeast(0)
        val anchors = if (anchorCount == 1) {
            listOf(0)
        } else {
            (0 until anchorCount).map { index ->
                (index * lastAnchor / (anchorCount - 1)).coerceIn(0, lastAnchor)
            }.distinct()
        }

        val pairs = linkedSetOf<Pair<Int, Int>>()
        for (anchor in anchors) {
            for (offset in validOffsets) {
                val target = anchor + offset
                if (target < frameCount) pairs += anchor to target
                if (pairs.size >= maximumPairs) return pairs.toList()
            }
        }
        return pairs.toList()
    }
}

internal data class InitialPairCandidate(
    val firstFrame: Int,
    val secondFrame: Int,
    val matchedCount: Int,
    val inlierCount: Int,
    val coveredGridCells: Int,
    val medianParallaxDegrees: Double,
    val upperQuartileParallaxDegrees: Double,
    val cheiralityRatio: Double,
    val homographyInlierRatio: Double,
    /** Fraction of translation aligned with the viewing direction, in [0, 1]. */
    val forwardMotionRatio: Double,
    /** Relative rotation R_second_from_first recovered from this image pair, row-major. */
    val relativeRotation: DoubleArray? = null,
    /** Unit translation direction in the second camera coordinate system. */
    val relativeTranslationDirection: DoubleArray? = null
) {
    val frameGap: Int
        get() = secondFrame - firstFrame

    val inlierRatio: Double
        get() = if (matchedCount <= 0) 0.0 else inlierCount.toDouble() / matchedCount
}

internal data class InitialPairPolicy(
    val minimumFrameGap: Int = 2,
    val minimumInliers: Int = 60,
    val minimumInlierRatio: Double = 0.25,
    val targetInlierRatio: Double = 0.55,
    val minimumCoveredGridCells: Int = 5,
    val gridCellCount: Int = 16,
    val minimumMedianParallaxDegrees: Double = 1.0,
    val minimumUpperQuartileParallaxDegrees: Double = 2.5,
    val targetMedianParallaxDegrees: Double = 8.0,
    val targetUpperQuartileParallaxDegrees: Double = 16.0,
    val minimumCheiralityRatio: Double = 0.55,
    val maximumForwardMotionRatio: Double = 0.95,
    val planarDominanceRatio: Double = 0.92,
    val planarParallaxEscapeDegrees: Double = 5.0,
    val inlierCountSaturation: Int = 500
)

internal data class InitialPairEvaluation(
    val candidate: InitialPairCandidate,
    val accepted: Boolean,
    val score: Double,
    val reason: String
)

internal data class InitialPairSelection(
    val selected: InitialPairCandidate?,
    val evaluations: List<InitialPairEvaluation>,
    val reason: String
)
