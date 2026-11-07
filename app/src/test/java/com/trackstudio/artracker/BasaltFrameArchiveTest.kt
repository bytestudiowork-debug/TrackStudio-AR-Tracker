package com.trackstudio.artracker

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class BasaltFrameArchiveTest {
    @Test fun writesAndReadsLosslessTimestampedFrames() {
        val file = File.createTempFile("basalt-frames", ".tsbf")
        try {
            val first = ByteArray(16) { (it * 7).toByte() }
            val second = ByteArray(24) { (it * 11).toByte() }
            val writer = BasaltFrameArchiveWriter(file)
            assertTrue(writer.offer(100L, 4, 4, first))
            assertTrue(writer.offer(200L, 6, 4, second))
            val snapshot = writer.finish()
            assertTrue(snapshot.complete)
            assertEquals(2L, snapshot.framesWritten)
            assertEquals(0L, snapshot.framesDropped)

            val frames = mutableListOf<BasaltFrameArchiveReader.Frame>()
            val summary = BasaltFrameArchiveReader.read(file, frames::add)
            assertEquals(2L, summary.frameCount)
            assertEquals(0L, summary.framesDropped)
            assertEquals(100L, frames[0].timestampNs)
            assertEquals(200L, frames[1].timestampNs)
            assertArrayEquals(first, frames[0].pixels)
            assertArrayEquals(second, frames[1].pixels)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsInvalidAndOutOfOrderFrames() {
        val file = File.createTempFile("basalt-frames", ".tsbf")
        try {
            val writer = BasaltFrameArchiveWriter(file)
            assertFalse(writer.offer(0L, 2, 2, ByteArray(4)))
            assertTrue(writer.offer(100L, 2, 2, ByteArray(4)))
            assertFalse(writer.offer(90L, 2, 2, ByteArray(4)))
            assertFalse(writer.offer(110L, 2, 2, ByteArray(3)))
            val snapshot = writer.finish()
            assertTrue(snapshot.complete)
            assertEquals(1L, snapshot.framesWritten)
            assertEquals(3L, snapshot.framesDropped)
        } finally {
            file.delete()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsArchiveWithMissingFooter() {
        val file = File.createTempFile("basalt-frames", ".tsbf")
        try {
            val writer = BasaltFrameArchiveWriter(file)
            assertTrue(writer.offer(100L, 2, 2, ByteArray(4)))
            writer.finish()
            RandomAccessFile(file, "rw").use { it.setLength(file.length() - 8L) }
            BasaltFrameArchiveReader.read(file) { }
        } finally {
            file.delete()
        }
    }
}
