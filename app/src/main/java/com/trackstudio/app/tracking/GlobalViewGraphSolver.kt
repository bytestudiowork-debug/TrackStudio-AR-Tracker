package com.trackstudio.app.tracking

/**
 * Runs the two rotation-averaging solves used by GLOMAP's global-mapper
 * controller: a first solve for edge filtering, then a final solve on the
 * filtered largest connected component.
 *
 * Important current limitation: the input is still the custom solver's
 * accepted initial-pair evaluations, not GLOMAP's full image-pair view graph.
 * This stage therefore establishes the controller boundary/order only; it is
 * not yet a complete GLOMAP relative-pose or view-graph implementation.
 */
internal object GlobalViewGraphSolver {
    data class Result(
        val rotationsByFrame: Map<Int, DoubleArray>,
        val inputEdgeCount: Int,
        val acceptedEdgeCount: Int,
        val iterations: Int
    )

    fun solve(
        evaluations: List<InitialPairEvaluation>,
        policy: InitialPairPolicy = InitialPairPolicy(),
        rotationOutlierDegrees: Double = 10.0
    ): Result? {
        val edges = evaluations.asSequence()
            .filter { it.accepted }
            .map { it.candidate }
            .filter {
                it.inlierCount >= policy.minimumInliers &&
                    it.inlierRatio >= policy.minimumInlierRatio &&
                    it.relativeRotation?.let { rotation ->
                        rotation.size == 9 && rotation.all { value -> value.isFinite() }
                    } == true
            }
            .map {
                GlobalRotationAverager.Edge(
                    firstFrame = it.firstFrame,
                    secondFrame = it.secondFrame,
                    relativeRotation = it.relativeRotation!!.copyOf(),
                    inlierCount = it.inlierCount
                )
            }
            .toList()
        if (edges.isEmpty()) return null

        // Upstream global_mapper.cc: first solve is for filtering.
        val filteringPass = GlobalRotationAverager.solve(
            frameIndices = edges.flatMap { listOf(it.firstFrame, it.secondFrame) }.distinct(),
            edges = edges,
            rotationOutlierDegrees = rotationOutlierDegrees
        ) ?: return null
        if (filteringPass.acceptedEdges.isEmpty()) return null

        // GlobalRotationAverager applies rotation-residual filtering and
        // largest-component extraction after each solve. The second call is
        // GLOMAP's final-estimation pass; there is no third solve in the
        // executable controller body at the pinned upstream revision.
        val finalPass = GlobalRotationAverager.solve(
            frameIndices = filteringPass.rotationsByFrame.keys.toList(),
            edges = filteringPass.acceptedEdges,
            rotationOutlierDegrees = rotationOutlierDegrees
        ) ?: return null

        return Result(
            rotationsByFrame = finalPass.rotationsByFrame,
            inputEdgeCount = edges.size,
            acceptedEdgeCount = finalPass.acceptedEdges.size,
            iterations = filteringPass.iterations + finalPass.iterations
        )
    }
}
