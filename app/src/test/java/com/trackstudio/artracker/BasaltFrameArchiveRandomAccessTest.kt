package com.trackstudio.artracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BasaltFrameArchiveRandomAccessTest {
    @Test
    fun indexesAndReadsFramesWithoutLoadingWholeArchive() {
        val file = File.createTempFile("trackstudio-frames", ".tsbf")
        try {
            val writer = BasaltFrameArchiveWriter(file)
            val first = byteArrayOf(1, 2, 3, 4, 5, 6)
            val second = byteArrayOf(9, 8, 7, 6, 5, 4)
            assertEquals(true, writer.offer(10L, 3, 2, first))
            assertEquals(true, writer.offer(20L, 3, 2, second))
            val snapshot = writer.finish()
            assertEquals(true, snapshot.complete)

            BasaltFrameArchiveRandomAccess(file).use { reader ->
                assertEquals(2, reader.frameCount)
                val frame = reader.readFrame(1)!!
                try {
                    val actual = ByteArray(6)
                    frame.get(0, 0, actual)
                    assertArrayEquals(second, actual)
                } finally {
                    frame.release()
                }
            }
        } finally {
            file.delete()
        }
    }
}
