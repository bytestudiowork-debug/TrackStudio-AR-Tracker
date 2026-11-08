package com.trackstudio.artracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasaltCalibrationProfileTest {
    @Test
    fun acceptsFiniteCompleteCalibrationWithProperRotation() {
        val profile = validProfile()
        assertTrue(profile.validationErrors().isEmpty())
        assertTrue(profile.isValid)

        val gate = BasaltReplayPreflight.CalibrationGate.from(profile)
        assertTrue(gate.complete)
    }

    @Test
    fun rejectsReflectionMatrixEvenThoughItsRowsAreOrthonormal() {
        val profile = validProfile().copy(
            cameraFromImuRotation = listOf(
                -1.0, 0.0, 0.0,
                 0.0, 1.0, 0.0,
                 0.0, 0.0, 1.0
            )
        )
        assertFalse(profile.extrinsicsValid)
        assertFalse(BasaltReplayPreflight.CalibrationGate.from(profile).complete)
    }

    @Test
    fun rejectsNonFiniteIntrinsicsAndNonPositiveNoise() {
        val profile = validProfile().copy(
            fx = Double.NaN,
            gyroNoiseDensityRadPerSecSqrtHz = 0.0,
            accelRandomWalkMps3SqrtHz = Double.POSITIVE_INFINITY
        )
        val gate = BasaltReplayPreflight.CalibrationGate.from(profile)
        assertFalse(gate.cameraModelValidatedForAnalysisStream)
        assertFalse(gate.gyroBiasAndNoiseCalibrated)
        assertFalse(gate.accelerometerBiasAndNoiseCalibrated)
        assertFalse(gate.complete)
    }

    @Test
    fun rejectsPrincipalPointOutsideAnalysisImage() {
        val profile = validProfile().copy(cx = 640.0)
        assertFalse(profile.cameraModelValid)
        assertTrue(profile.validationErrors().any { it.contains("principal point") })
    }

    private fun validProfile() = BasaltCalibrationProfile(
        cameraModel = "pinhole-radtan",
        imageWidth = 640,
        imageHeight = 480,
        fx = 420.0,
        fy = 421.0,
        cx = 320.0,
        cy = 240.0,
        distortion = listOf(0.01, -0.02, 0.001, -0.001, 0.0),
        cameraFromImuRotation = listOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        ),
        cameraFromImuTranslationMeters = listOf(0.02, 0.0, 0.01),
        gyroNoiseDensityRadPerSecSqrtHz = 0.001,
        gyroRandomWalkRadPerSec2SqrtHz = 0.0001,
        gyroBiasRadPerSec = listOf(0.0, 0.0, 0.0),
        accelNoiseDensityMps2SqrtHz = 0.01,
        accelRandomWalkMps3SqrtHz = 0.001,
        accelBiasMps2 = listOf(0.0, 0.0, 0.0)
    )
}
