package com.trackstudio.app.tracking.glomap

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Builds the point-to-camera residual blocks used by GLOMAP GlobalPositioner
 * in its recommended ONLY_POINTS mode.
 *
 * This is a problem-assembly stage, not an optimizer. The Ceres solve,
 * parameter ordering, gauge handling and conversion back to poses remain
 * separate porting work and are deliberately not approximated here.
 *
 * Source: glomap/estimators/global_positioning.cc and
 * glomap/estimators/global_positioning.h at
 * 99806d0869f802fad218516a2e027793e7ca687d.
 */
internal object GlomapGlobalPositioningProblemBuilder {
    const val DEFAULT_MINIMUM_TRACK_VIEWS = 3
    const val HUBER_DELTA = 1e-1
    const val SCALE_LOWER_BOUND = 1e-5

    data class CameraObservation(
        val frameIndex: Int,
        /** Camera-from-world rotation, row-major 3x3. */
        val rotation: DoubleArray,
        /** Camera center in world coordinates (not world-to-camera t). */
        val cameraCenter: DoubleArray,
        /** Undistorted normalized feature direction, matching COLMAP features_undist. */
        val undistortedFeatureDirection: DoubleArray,
        val isCalibrated: Boolean
    )

    data class Track(
        val trackId: Int,
        val observations: List<CameraObservation>,
        /** Existing initialized 3D point, if available. */
        val initializedPoint: DoubleArray? = null
    )

    data class Constraint(
        val trackId: Int,
        val frameIndex: Int,
        val cameraCenter: DoubleArray,
        val point: DoubleArray?,
        /** R(camera-from-world)^T * undistorted feature direction. */
        val translationObservation: DoubleArray,
        val initialScale: Double,
        val scaleLowerBound: Double = SCALE_LOWER_BOUND,
        val huberDelta: Double,
        /** Upstream uncalibrated-camera residual weight. */
        val lossScale: Double
    )

    data class Problem(
        val constraints: List<Constraint>,
        val acceptedTrackCount: Int,
        val skippedShortTracks: Int,
        val skippedInvalidObservations: Int
    )

    /**
     * Mirrors GlobalPositioner::AddPointToCameraConstraints and
     * AddTrackToProblem for trivial frames and ONLY_POINTS.
     */
    fun build(
        tracks: List<Track>,
        minimumViews: Int = DEFAULT_MINIMUM_TRACK_VIEWS,
        generateScales: Boolean = true
    ): Problem {
        require(minimumViews >= 2)
        val constraints = ArrayList<Constraint>()
        var shortTracks = 0
        var invalidObservations = 0
        var acceptedTracks = 0

        for (track in tracks) {
            if (track.observations.size < minimumViews) {
                shortTracks++
                continue
            }
            val point = track.initializedPoint?.takeIf(::isVector3)
            var trackAdded = false

            for (observation in track.observations) {
                if (!isRotation(observation.rotation) ||
                    !isVector3(observation.cameraCenter) ||
                    !isVector3(observation.undistortedFeatureDirection)
                ) {
                    invalidObservations++
                    continue
                }

                // Upstream: translation = CamFromWorld().rotation.inverse()
                //            * image.features_undist[observation.second]
                val translation = multiplyTranspose(
                    observation.rotation,
                    observation.undistortedFeatureDirection
                )
                val scale = if (!generateScales && point != null) {
                    initializeScale(
                        translation,
                        point,
                        observation.cameraCenter
                    )
                } else {
                    1.0 // GLOMAP initializes every auxiliary scale to 1.
                }

                constraints += Constraint(
                    trackId = track.trackId,
                    frameIndex = observation.frameIndex,
                    cameraCenter = observation.cameraCenter.copyOf(),
                    point = point?.copyOf(),
                    translationObservation = translation,
                    initialScale = scale,
                    huberDelta = HUBER_DELTA,
                    lossScale = if (observation.isCalibrated) 1.0 else 0.5,
                )
                trackAdded = true
            }
            if (trackAdded) acceptedTracks++
        }

        return Problem(
            constraints = constraints,
            acceptedTrackCount = acceptedTracks,
            skippedShortTracks = shortTracks,
            skippedInvalidObservations = invalidObservations
        )
    }

    /** Exact scale initialization used when generate_scales is disabled. */
    fun initializeScale(
        translation: DoubleArray,
        point: DoubleArray,
        cameraCenter: DoubleArray
    ): Double {
        require(isVector3(translation) && isVector3(point) && isVector3(cameraCenter))
        val direction = DoubleArray(3) { point[it] - cameraCenter[it] }
        val squaredNorm = direction.sumOf { it * it }
        require(squaredNorm > 0.0 && squaredNorm.isFinite())
        val dot = translation.indices.sumOf { translation[it] * direction[it] }
        return max(SCALE_LOWER_BOUND, dot / squaredNorm)
    }

    private fun multiplyTranspose(rotation: DoubleArray, vector: DoubleArray): DoubleArray =
        DoubleArray(3) { column ->
            rotation[column] * vector[0] +
                rotation[3 + column] * vector[1] +
                rotation[6 + column] * vector[2]
        }

    private fun isVector3(value: DoubleArray): Boolean =
        value.size == 3 && value.all { it.isFinite() }

    private fun isRotation(value: DoubleArray): Boolean {
        if (value.size != 9 || value.any { !it.isFinite() }) return false
        for (row in 0..2) for (column in 0..2) {
            var dot = 0.0
            for (k in 0..2) dot += value[row * 3 + k] * value[column * 3 + k]
            if (kotlin.math.abs(dot - if (row == column) 1.0 else 0.0) > 1e-3) return false
        }
        val det = value[0] * (value[4] * value[8] - value[5] * value[7]) -
            value[1] * (value[3] * value[8] - value[5] * value[6]) +
            value[2] * (value[3] * value[7] - value[4] * value[6])
        return kotlin.math.abs(det - 1.0) <= 1e-3
    }
}
