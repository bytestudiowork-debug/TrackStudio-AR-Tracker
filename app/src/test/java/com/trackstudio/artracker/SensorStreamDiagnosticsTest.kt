package com.trackstudio.artracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorStreamDiagnosticsTest {
    private fun ms(value: Long) = value * 1_000_000L

    @Test
    fun computesObservedRateAndIntervals() {
        val monitor = SensorStreamDiagnostics()
        assertTrue(monitor.record(ms(10), 0f, 0f, 9.8f))
        assertTrue(monitor.record(ms(20), 0f, 0f, 9.8f))
        assertTrue(monitor.record(ms(30), 0f, 0f, 9.8f))
        val snapshot = monitor.snapshot()
        assertEquals(3L, snapshot.acceptedSamples)
        assertEquals(10.0, snapshot.meanIntervalMs, 1e-9)
        assertEquals(100.0, snapshot.estimatedRateHz, 1e-9)
        assertEquals(10.0, snapshot.maxIntervalMs, 1e-9)
    }

    @Test
    fun countsOutOfOrderSamplesWithoutChangingLastTimestamp() {
        val monitor = SensorStreamDiagnostics()
        assertTrue(monitor.record(ms(10), 0f, 0f, 0f))
        assertTrue(monitor.record(ms(20), 0f, 0f, 0f))
        assertFalse(monitor.record(ms(15), 0f, 0f, 0f))
        val snapshot = monitor.snapshot()
        assertEquals(1L, snapshot.outOfOrderSamples)
        assertEquals(ms(20), snapshot.lastTimestampNs)
        assertEquals(2L, snapshot.acceptedSamples)
    }

    @Test
    fun rejectsNonFiniteAndNonPositiveSamples() {
        val monitor = SensorStreamDiagnostics()
        assertFalse(monitor.record(0L, 0f, 0f, 0f))
        assertFalse(monitor.record(ms(10), Float.NaN, 0f, 0f))
        assertEquals(2L, monitor.snapshot().invalidSamples)
    }

    @Test
    fun countsLongSensorGaps() {
        val monitor = SensorStreamDiagnostics()
        monitor.record(ms(10), 0f, 0f, 0f)
        monitor.record(ms(20), 0f, 0f, 0f)
        monitor.record(ms(100), 0f, 0f, 0f)
        val snapshot = monitor.snapshot()
        assertEquals(80.0, snapshot.maxIntervalMs, 1e-9)
        assertEquals(1L, snapshot.longGapCount)
    }

    @Test
    fun resetClearsAllCounters() {
        val monitor = SensorStreamDiagnostics()
        monitor.record(ms(10), 0f, 0f, 0f)
        monitor.record(ms(100), 0f, 0f, 0f)
        monitor.reset()
        assertEquals(0L, monitor.snapshot().acceptedSamples)
        assertEquals(0L, monitor.snapshot().longGapCount)
    }
}
