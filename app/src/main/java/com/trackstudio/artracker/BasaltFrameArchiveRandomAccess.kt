package com.trackstudio.artracker

import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/**
 * Indexed, random-access reader for the bounded grayscale camera archive.
 * The index stores only frame metadata/offsets; pixel buffers are inflated one
 * at a time so long recordings do not have to be loaded into RAM.
 */
internal class BasaltFrameArchiveRandomAccess(private val source: java.io.File) : Closeable {
    internal data class FrameData(
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val pixels: ByteArray
    )

    private data class Entry(
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val rawBytes: Int,
        val compressedBytes: Int,
        val checksum: Int,
        val payloadOffset: Long
    )

    private val file = RandomAccessFile(source, "r")
    private val entries: List<Entry>

    val frameCount: Int
        get() = entries.size

    init {
        try {
            entries = buildIndex()
        } catch (error: Throwable) {
            file.close()
            throw error
        }
    }

    /**
     * Pure Kotlin archive read path. Keeping decompression/checksum validation
     * independent from OpenCV lets JVM unit tests verify the real archive format
     * without requiring Android native OpenCV libraries.
     */
    @Synchronized
    fun readFrameBytes(index: Int): FrameData? {
        val entry = entries.getOrNull(index) ?: return null
        file.seek(entry.payloadOffset)
        val compressed = ByteArray(entry.compressedBytes)
        file.readFully(compressed)

        val pixels = ByteArray(entry.rawBytes)
        InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
            var offset = 0
            while (offset < pixels.size) {
                val count = inflater.read(pixels, offset, pixels.size - offset)
                if (count < 0) throw IOException("Frame payload ended before its declared size")
                offset += count
            }
            if (inflater.read() != -1) throw IOException("Frame payload exceeds its declared size")
        }
        val checksum = CRC32().apply { update(pixels) }.value.toInt()
        if (checksum != entry.checksum) throw IOException("Frame checksum mismatch at index $index")
        return FrameData(entry.timestampNs, entry.width, entry.height, pixels)
    }

    @Synchronized
    fun readFrame(index: Int): Mat? {
        val frame = readFrameBytes(index) ?: return null
        return Mat(frame.height, frame.width, CvType.CV_8UC1).also { mat ->
            mat.put(0, 0, frame.pixels)
        }
    }

    private fun buildIndex(): List<Entry> {
        if (!source.isFile || file.length() < 8L) throw IOException("Frame archive is missing or truncated")
        val magic = ByteArray(BasaltFrameArchiveWriter.MAGIC.size)
        file.readFully(magic)
        if (!magic.contentEquals(BasaltFrameArchiveWriter.MAGIC)) {
            throw IOException("Invalid frame archive signature")
        }
        val version = file.readInt()
        if (version != BasaltFrameArchiveWriter.FORMAT_VERSION) {
            throw IOException("Unsupported frame archive version: $version")
        }

        val result = ArrayList<Entry>()
        var previousTimestamp = 0L
        while (file.filePointer < file.length()) {
            val timestamp = file.readLong()
            if (timestamp == BasaltFrameArchiveWriter.END_OF_ARCHIVE) {
                val written = file.readLong()
                file.readLong() // dropped frames are reported by the capture metadata
                if (written != result.size.toLong()) throw IOException("Frame archive footer count mismatch")
                if (file.filePointer != file.length()) throw IOException("Unexpected data after frame archive footer")
                return result
            }
            val width = file.readInt()
            val height = file.readInt()
            val rawBytes = file.readInt()
            val compressedBytes = file.readInt()
            val checksum = file.readInt()
            val expectedRaw = width.toLong() * height.toLong()
            if (timestamp <= previousTimestamp || width <= 0 || height <= 0 ||
                expectedRaw > BasaltFrameArchiveWriter.MAX_FRAME_BYTES ||
                rawBytes.toLong() != expectedRaw || compressedBytes <= 0 ||
                compressedBytes > BasaltFrameArchiveWriter.MAX_FRAME_BYTES + 1_048_576
            ) throw IOException("Invalid frame metadata in archive")
            val offset = file.filePointer
            val next = offset + compressedBytes
            if (next > file.length()) throw IOException("Truncated compressed frame payload")
            result += Entry(timestamp, width, height, rawBytes, compressedBytes, checksum, offset)
            file.seek(next)
            previousTimestamp = timestamp
        }
        throw IOException("Frame archive footer is missing")
    }

    override fun close() {
        synchronized(this) { file.close() }
    }
}
