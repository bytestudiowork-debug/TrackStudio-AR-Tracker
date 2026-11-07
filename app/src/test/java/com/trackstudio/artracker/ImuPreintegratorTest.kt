package com.trackstudio.artracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuPreintegratorTest {
    private fun ms(value: Long) = value * 1_000_000L

    @Test
    fun gyroIntegrationInterpolatesAtCameraFrameBoundaries() {
        val integrator = ImuPreintegrator()
        listOf(10L, 20L, 30L, 40L).forEach {
            integrator.addGyroscope(ms(it), 2f, -1f, 0.5f)
        }

        val delta = integrator.integrate(ms(15), ms(35))

        assertTrue(delta.valid)
        assertEquals(0.020, delta.intervalSeconds, 1e-9)
        assertEquals(0.040, delta.rotationXRad, 1e-9)
        assertEquals(-0.020, delta.rotationYRad, 1e-9)
        assertEquals(0.010, delta.rotationZRad, 1e-9)
        assertEquals(3, delta.sampleCount)
    }

    @Test
    fun stationaryAccelerometerProducesNearZeroLinearDelta() {
        val integrator = ImuPreintegrator()
        listOf(10L, 20L, 30L, 40L).forEach {
            integrator.addAccelerometer(ms(it), 0f, 0f, 9.80665f)
        }

        val delta = integrator.integrateAcceleration(ms(15), ms(35))

        assertTrue(delta.valid)
        assertEquals(0.0, delta.velocityX, 1e-9)
        assertEquals(0.0, delta.velocityY, 1e-9)
        assertEquals(0.0, delta.velocityZ, 1e-9)
        assertEquals(0.0, delta.positionX, 1e-9)
        assertEquals(0.0, delta.positionY, 1e-9)
        assertEquals(0.0, delta.positionZ, 1e-9)
    }

    @Test
    fun rejectsIntervalsContainingLargeSensorGaps() {
        val integrator = ImuPreintegrator()
        integrator.addGyroscope(ms(10), 1f, 0f, 0f)
        integrator.addGyroscope(ms(20), 1f, 0f, 0f)
        integrator.addGyroscope(ms(200), 1f, 0f, 0f)

        assertFalse(integrator.integrate(ms(10), ms(200)).valid)
    }

    @Test
    fun ignoresOutOfOrderGyroscopeSamples() {
        val integrator = ImuPreintegrator()
        integrator.addGyroscope(ms(10), 1f, 0f, 0f)
        integrator.addGyroscope(ms(20), 1f, 0f, 0f)
        integrator.addGyroscope(ms(15), 100f, 0f, 0f)
        integrator.addGyroscope(ms(30), 1f, 0f, 0f)

        val delta = integrator.integrate(ms(10), ms(30))

        assertTrue(delta.valid)
        assertEquals(0.020, delta.rotationXRad, 1e-9)
    }

    @Test
    fun requiresSensorCoverageAtStartOfInterval() {
        val integrator = ImuPreintegrator()
        integrator.addGyroscope(ms(20), 1f, 0f, 0f)
        integrator.addGyroscope(ms(30), 1f, 0f, 0f)

        assertFalse(integrator.integrate(ms(10), ms(25)).valid)
    }
}
