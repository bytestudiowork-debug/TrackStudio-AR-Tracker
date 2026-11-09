package com.trackstudio.app.tracking.glomap

/**
 * JNI bridge for the GLOMAP ONLY_POINTS global-positioning solve.
 * Input order is stable and caller-controlled, matching the Kotlin problem builder.
 */
internal class GlomapNativeSolver {
    data class Result(
        val frameCentersById: Map<Int, DoubleArray>,
        val pointsByTrackId: Map<Int, DoubleArray>,
        val scales: DoubleArray,
        val initialCost: Double,
        val finalCost: Double,
        val iterations: Int
    )

    fun solve(
        problem: GlomapGlobalPositioningProblemBuilder.Problem,
        frameCentersById: Map<Int, DoubleArray>,
        pointsByTrackId: Map<Int, DoubleArray>,
        maxIterations: Int = 100,
        numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): Result? {
        require(maxIterations > 0 && numThreads > 0)
        if (problem.constraints.isEmpty()) return null

        val frameIds = problem.constraints.map { it.frameIndex }.distinct()
            .filter { frameCentersById.containsKey(it) }
        val trackIds = problem.constraints.map { it.trackId }.distinct()
            .filter { pointsByTrackId.containsKey(it) }
        if (frameIds.isEmpty() || trackIds.isEmpty()) return null

        val frameIndexById = frameIds.withIndex().associate { it.value to it.index }
        val trackIndexById = trackIds.withIndex().associate { it.value to it.index }
        val constraints = problem.constraints.filter {
            it.frameIndex in frameIndexById && it.trackId in trackIndexById
        }
        if (constraints.isEmpty()) return null

        val initialFrames = frameIds.flatMap { id ->
            frameCentersById.getValue(id).also { requireVector3(it, "frame center") }.toList()
        }.toDoubleArray()
        val initialPoints = trackIds.flatMap { id ->
            pointsByTrackId.getValue(id).also { requireVector3(it, "track point") }.toList()
        }.toDoubleArray()
        val frameIndices = IntArray(constraints.size) { frameIndexById.getValue(constraints[it].frameIndex) }
        val trackIndices = IntArray(constraints.size) { trackIndexById.getValue(constraints[it].trackId) }
        val directions = constraints.flatMap { it.translationObservation.toList() }.toDoubleArray()
        val scales = DoubleArray(constraints.size) { constraints[it].initialScale }
        val lossScales = DoubleArray(constraints.size) { constraints[it].lossScale }

        val packed = nativeSolve(
            initialFrames, initialPoints, frameIndices, trackIndices,
            directions, scales, lossScales, maxIterations, numThreads
        )
        val expectedSize = 4 + initialFrames.size + initialPoints.size + scales.size
        if (packed.size != expectedSize || packed[0] != 1.0) return null

        var offset = 4
        val solvedFrames = LinkedHashMap<Int, DoubleArray>(frameIds.size)
        for (id in frameIds) {
            solvedFrames[id] = packed.copyOfRange(offset, offset + 3)
            offset += 3
        }
        val solvedPoints = LinkedHashMap<Int, DoubleArray>(trackIds.size)
        for (id in trackIds) {
            solvedPoints[id] = packed.copyOfRange(offset, offset + 3)
            offset += 3
        }
        val solvedScales = packed.copyOfRange(offset, packed.size)
        if (solvedScales.any { !it.isFinite() || it < 1e-5 }) return null

        return Result(
            frameCentersById = solvedFrames,
            pointsByTrackId = solvedPoints,
            scales = solvedScales,
            initialCost = packed[1],
            finalCost = packed[2],
            iterations = packed[3].toInt()
        )
    }

    private external fun nativeSolve(
        frameCenters: DoubleArray,
        points: DoubleArray,
        frameIndices: IntArray,
        trackIndices: IntArray,
        directions: DoubleArray,
        initialScales: DoubleArray,
        lossScales: DoubleArray,
        maxIterations: Int,
        numThreads: Int
    ): DoubleArray

    private fun requireVector3(value: DoubleArray, label: String) {
        require(value.size == 3 && value.all { it.isFinite() }) {
            "$label must contain three finite values"
        }
    }

    companion object {
        init {
            System.loadLibrary("trackstudio_glomap")
        }
    }
}
