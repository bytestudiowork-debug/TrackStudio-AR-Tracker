package com.trackstudio.artracker

import kotlin.math.abs

/**
 * Explicit calibration contract required before a Basalt session can be replayed.
 *
 * Values are expressed in the camera analysis-stream coordinate system and Android
 * sensor units. This is a validated TrackStudio model; it is not yet serialized to
 * or loaded from Basalt's native calibration file format.
 */
internal data class BasaltCalibrationProfile(
    val cameraModel: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val distortion: List<Double>,
    /** Row-major rotation taking IMU-frame vectors into the camera frame. */
    val cameraFromImuRotation: List<Double>,
    /** Translation of the IMU origin expressed in camera-frame metres. */
    val cameraFromImuTranslationMeters: List<Double>,
    val gyroNoiseDensityRadPerSecSqrtHz: Double,
    val gyroRandomWalkRadPerSec2SqrtHz: Double,
    val gyroBiasRadPerSec: List<Double>,
    val accelNoiseDensityMps2SqrtHz: Double,
    val accelRandomWalkMps3SqrtHz: Double,
    val accelBiasMps2: List<Double>
) {
    fun validationErrors(): List<String> = buildList {
        if (cameraModel.isBlank()) add("Camera model is missing.")
        if (imageWidth < 16 || imageHeight < 16) add("Analysis image dimensions are invalid.")
        if (!fx.isFinite() || fx <= 0.0 || !fy.isFinite() || fy <= 0.0) {
            add("Camera focal lengths must be finite and positive.")
        }
        if (!cx.isFinite() || !cy.isFinite() || cx < 0.0 || cy < 0.0 ||
            cx >= imageWidth || cy >= imageHeight
        ) add("Camera principal point must lie inside the calibrated analysis image.")
        if (distortion.isEmpty() || distortion.any { !it.isFinite() }) {
            add("Camera distortion coefficients are missing or non-finite.")
        }
        if (cameraFromImuRotation.size != 9 ||
            cameraFromImuRotation.any { !it.isFinite() } ||
            !isRotationMatrix(cameraFromImuRotation)
        ) add("Camera-from-IMU rotation must be a finite 3x3 proper rotation matrix.")
        if (cameraFromImuTranslationMeters.size != 3 ||
            cameraFromImuTranslationMeters.any { !it.isFinite() }
        ) add("Camera-from-IMU translation must contain three finite metre values.")
        if (!positiveFinite(gyroNoiseDensityRadPerSecSqrtHz) ||
            !positiveFinite(gyroRandomWalkRadPerSec2SqrtHz)
        ) add("Gyroscope noise density and random walk must be finite and positive.")
        if (gyroBiasRadPerSec.size != 3 || gyroBiasRadPerSec.any { !it.isFinite() }) {
            add("Gyroscope bias must contain three finite rad/s values.")
        }
        if (!positiveFinite(accelNoiseDensityMps2SqrtHz) ||
            !positiveFinite(accelRandomWalkMps3SqrtHz)
        ) add("Accelerometer noise density and random walk must be finite and positive.")
        if (accelBiasMps2.size != 3 || accelBiasMps2.any { !it.isFinite() }) {
            add("Accelerometer bias must contain three finite m/s² values.")
        }
    }

    val cameraModelValid: Boolean
        get() = cameraModel.isNotBlank() && imageWidth >= 16 && imageHeight >= 16 &&
            fx.isFinite() && fx > 0.0 && fy.isFinite() && fy > 0.0 &&
            cx.isFinite() && cy.isFinite() && cx >= 0.0 && cy >= 0.0 &&
            cx < imageWidth && cy < imageHeight &&
            distortion.isNotEmpty() && distortion.all { it.isFinite() }

    val extrinsicsValid: Boolean
        get() = cameraFromImuRotation.size == 9 &&
            cameraFromImuRotation.all { it.isFinite() } &&
            isRotationMatrix(cameraFromImuRotation) &&
            cameraFromImuTranslationMeters.size == 3 &&
            cameraFromImuTranslationMeters.all { it.isFinite() }

    val gyroCalibrationValid: Boolean
        get() = positiveFinite(gyroNoiseDensityRadPerSecSqrtHz) &&
            positiveFinite(gyroRandomWalkRadPerSec2SqrtHz) &&
            gyroBiasRadPerSec.size == 3 && gyroBiasRadPerSec.all { it.isFinite() }

    val accelerometerCalibrationValid: Boolean
        get() = positiveFinite(accelNoiseDensityMps2SqrtHz) &&
            positiveFinite(accelRandomWalkMps3SqrtHz) &&
            accelBiasMps2.size == 3 && accelBiasMps2.all { it.isFinite() }

    val isValid: Boolean
        get() = cameraModelValid && extrinsicsValid &&
            gyroCalibrationValid && accelerometerCalibrationValid

    private fun positiveFinite(value: Double) = value.isFinite() && value > 0.0

    private fun isRotationMatrix(m: List<Double>): Boolean {
        // Rows must be orthonormal and determinant must be +1 (not a reflection).
        for (row in 0..2) for (other in 0..2) {
            var dot = 0.0
            for (column in 0..2) dot += m[row * 3 + column] * m[other * 3 + column]
            val expected = if (row == other) 1.0 else 0.0
            if (abs(dot - expected) > 0.02) return false
        }
        val determinant =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        return determinant.isFinite() && abs(determinant - 1.0) <= 0.02
    }
}
