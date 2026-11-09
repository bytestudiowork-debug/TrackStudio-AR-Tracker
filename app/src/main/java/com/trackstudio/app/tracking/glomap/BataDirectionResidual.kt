package com.trackstudio.app.tracking.glomap

import kotlin.math.max

/**
 * Android/Kotlin port of GLOMAP's BATAPairwiseDirectionError.
 *
 * Upstream:
 * glomap/estimators/cost_function.h
 * GLOMAP revision 99806d0869f802fad218516a2e027793e7ca687d
 *
 * Residual: translation_obs - scale * (position2 - position1).
 * This kernel is shared by camera-pair and point-to-camera constraints. It
 * deliberately does not normalize vectors or change the residual/loss.
 *
 * Scale positivity is an optimizer parameter bound in GLOMAP (scale >= 1e-5),
 * not a clamp inside the residual. Keep that distinction when porting the
 * optimizer.
 */
internal class BataDirectionResidual(translationObservation: DoubleArray) {
    private val observation = translationObservation.copyOf()

    init {
        require(observation.size == 3 && observation.all { it.isFinite() }) {
            "BATA translation observation must contain three finite values."
        }
    }

    fun evaluate(
        position1: DoubleArray,
        position2: DoubleArray,
        scale: Double
    ): DoubleArray {
        requireVector(position1, "position1")
        requireVector(position2, "position2")
        require(scale.isFinite()) { "BATA scale must be finite." }

        return DoubleArray(3) { axis ->
            observation[axis] - scale * (position2[axis] - position1[axis])
        }
    }

    /**
     * Exact Jacobians of the upstream residual with respect to position1,
     * position2 and scale. Matrices are row-major 3x3.
     */
    fun jacobians(
        position1: DoubleArray,
        position2: DoubleArray,
        scale: Double
    ): Jacobians {
        requireVector(position1, "position1")
        requireVector(position2, "position2")
        require(scale.isFinite()) { "BATA scale must be finite." }

        val position1Jacobian = DoubleArray(9)
        val position2Jacobian = DoubleArray(9)
        val scaleJacobian = DoubleArray(3)
        for (axis in 0..2) {
            position1Jacobian[axis * 3 + axis] = scale
            position2Jacobian[axis * 3 + axis] = -scale
            scaleJacobian[axis] = -(position2[axis] - position1[axis])
        }
        return Jacobians(position1Jacobian, position2Jacobian, scaleJacobian)
    }

    data class Jacobians(
        val position1: DoubleArray,
        val position2: DoubleArray,
        val scale: DoubleArray
    )

    companion object {
        /**
         * GLOMAP's initialized-track scale guess:
         * max(1e-5, translation.dot(point - cameraCenter) /
         *              squaredNorm(point - cameraCenter)).
         */
        fun initializeScale(
            translation: DoubleArray,
            point: DoubleArray,
            cameraCenter: DoubleArray
        ): Double {
            requireVector(translation, "translation")
            requireVector(point, "point")
            requireVector(cameraCenter, "cameraCenter")
            val direction = DoubleArray(3) { point[it] - cameraCenter[it] }
            val squaredNorm = direction.sumOf { it * it }
            require(squaredNorm > 0.0 && squaredNorm.isFinite()) {
                "Cannot initialize BATA scale from a zero-length point direction."
            }
            val dot = translation.indices.sumOf { translation[it] * direction[it] }
            return max(1e-5, dot / squaredNorm)
        }

        private fun requireVector(value: DoubleArray, name: String) {
            require(value.size == 3 && value.all { it.isFinite() }) {
                "$name must contain three finite values."
            }
        }
    }
}
