package com.trackstudio.artracker

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CameraFrameDiagnosticsTest {
    @Test fun acceptsIncreasingTimestampsAndEstimatesRate() {
        val d = CameraFrameDiagnostics()
        assertTrue(d.record(1_000_000_000L))
        assertTrue(d.record(1_033_333_333L))
        assertTrue(d.record(1_066_666_666L))
        assertEquals(3L, d.snapshot().acceptedFrames)
        assertEquals(30.0, d.snapshot().estimatedRateHz, 0.1)
    }

    @Test fun rejectsInvalidAndOutOfOrderTimestamps() {
        val d = CameraFrameDiagnostics()
        assertFalse(d.record(0L)); assertTrue(d.record(100L))
        assertFalse(d.record(100L)); assertFalse(d.record(90L))
        assertEquals(1L, d.snapshot().invalidTimestamps)
        assertEquals(2L, d.snapshot().outOfOrderTimestamps)
    }

    @Test fun countsLongFrameGaps() {
        val d = CameraFrameDiagnostics(50L)
        d.record(100L); d.record(151L); d.record(202L)
        assertEquals(2L, d.snapshot().longGapCount)
    }

    @Test fun packsCropWithRowAndPixelStride() {
        val src = ByteBuffer.wrap(byteArrayOf(
            0,10,99,20,99,30,99,88, 0,40,99,50,99,60,99,88, 0,70,99,80,99,90,99,88
        ))
        val p = LumaFramePacker.pack(src, 3, 3, 8, 2, 1, 0, 3, 3)
        assertEquals(2, p.width); assertEquals(3, p.height)
        assertEquals(listOf<Byte>(10,20,40,50,70,80), p.pixels.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCropOutsideImage() {
        LumaFramePacker.pack(ByteBuffer.wrap(ByteArray(16)), 2, 2, 4, 1, 0, 0, 3, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPlaneBuffer() {
        LumaFramePacker.pack(ByteBuffer.wrap(ByteArray(3)), 2, 2, 2, 1, 0, 0, 2, 2)
    }
}
