package com.trackstudio.artracker

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Bounded, lossless grayscale frame archive for deterministic Basalt dataset replay.
 * offer() never waits for disk I/O. On success, ownership of the pixel array transfers
 * to this writer; callers must not mutate it afterwards.
 */
internal class BasaltFrameArchiveWriter(
    private val file: File,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : Closeable {
    data class Snapshot(
        val framesWritten: Long,
        val framesDropped: Long,
        val complete: Boolean,
        val error: String?,
        val archiveBytes: Long
    )

    private data class Frame(val timestampNs: Long, val width: Int, val height: Int, val pixels: ByteArray)

    private val queue = ArrayBlockingQueue<Frame>(queueCapacity)
    private val accepting = AtomicBoolean(true)
    private val framesWritten = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val workerFinished = AtomicBoolean(false)
    private val workerFailure = AtomicReference<String?>(null)
    private var lastAcceptedTimestampNs = 0L
    private val worker: Thread

    init {
        require(queueCapacity > 0) { "Frame archive queue capacity must be positive" }
        file.parentFile?.mkdirs()
        val output = DataOutputStream(BufferedOutputStream(FileOutputStream(file, false)))
        output.write(MAGIC)
        output.writeInt(FORMAT_VERSION)
        output.flush()
        worker = Thread({ writeLoop(output) }, "AR-Basalt-Frame-Archive").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun offer(timestampNs: Long, width: Int, height: Int, pixels: ByteArray): Boolean {
        if (!accepting.get()) {
            framesDropped.incrementAndGet()
            return false
        }
        val expectedBytes = width.toLong() * height.toLong()
        if (timestampNs <= lastAcceptedTimestampNs || width <= 0 || height <= 0 ||
            expectedBytes > MAX_FRAME_BYTES || expectedBytes != pixels.size.toLong()
        ) {
            framesDropped.incrementAndGet()
            return false
        }
        if (!queue.offer(Frame(timestampNs, width, height, pixels))) {
            framesDropped.incrementAndGet()
            return false
        }
        lastAcceptedTimestampNs = timestampNs
        return true
    }

    @Synchronized
    fun finish(): Snapshot {
        if (accepting.compareAndSet(true, false)) worker.interrupt()
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val result = snapshot()
        return if (interrupted && result.error == null) {
            result.copy(complete = false, error = "Interrupted while finalizing frame archive")
        } else result
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        framesWritten.get(),
        framesDropped.get(),
        workerFinished.get() && workerFailure.get() == null,
        workerFailure.get(),
        if (file.exists()) file.length() else 0L
    )

    override fun close() {
        finish()
    }

    private fun writeLoop(output: DataOutputStream) {
        try {
            while (accepting.get() || queue.isNotEmpty()) {
                val frame = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                writeFrame(output, frame)
                framesWritten.incrementAndGet()
            }
            output.writeLong(END_OF_ARCHIVE)
            output.writeLong(framesWritten.get())
            output.writeLong(framesDropped.get())
            output.flush()
        } catch (error: Exception) {
            workerFailure.compareAndSet(null, error.message ?: error.javaClass.simpleName)
            accepting.set(false)
            framesDropped.addAndGet(queue.size.toLong())
            queue.clear()
        } finally {
            try {
                output.close()
            } catch (error: Exception) {
                workerFailure.compareAndSet(null, error.message ?: error.javaClass.simpleName)
            }
            workerFinished.set(true)
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: Frame) {
        val compressed = ByteArrayOutputStream(frame.pixels.size)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(compressed, deflater).use { it.write(frame.pixels) }
        } finally {
            deflater.end()
        }
        val payload = compressed.toByteArray()
        val crc = CRC32().apply { update(frame.pixels) }.value.toInt()
        output.writeLong(frame.timestampNs)
        output.writeInt(frame.width)
        output.writeInt(frame.height)
        output.writeInt(frame.pixels.size)
        output.writeInt(payload.size)
        output.writeInt(crc)
        output.write(payload)
    }

    companion object {
        internal const val FORMAT_VERSION = 1
        internal const val DEFAULT_QUEUE_CAPACITY = 4
        internal const val MAX_FRAME_BYTES = 64 * 1024 * 1024
        internal const val END_OF_ARCHIVE = Long.MIN_VALUE
        internal val MAGIC = byteArrayOf(0x54, 0x53, 0x42, 0x46) // TSBF
    }
}

internal object BasaltFrameArchiveReader {
    data class Frame(val timestampNs: Long, val width: Int, val height: Int, val pixels: ByteArray)
    data class Summary(val frameCount: Long, val framesDropped: Long, val formatVersion: Int)

    fun read(file: File, onFrame: (Frame) -> Unit): Summary {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(BasaltFrameArchiveWriter.MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(BasaltFrameArchiveWriter.MAGIC)) {
                throw IOException("Invalid Basalt frame archive signature")
            }
            val version = input.readInt()
            if (version != BasaltFrameArchiveWriter.FORMAT_VERSION) {
                throw IOException("Unsupported Basalt frame archive version: $version")
            }
            var count = 0L
            var previousTimestampNs = 0L
            while (true) {
                val timestampNs = try {
                    input.readLong()
                } catch (error: EOFException) {
                    throw IOException("Frame archive is truncated (footer missing)", error)
                }
                if (timestampNs == BasaltFrameArchiveWriter.END_OF_ARCHIVE) {
                    val written = input.readLong()
                    val dropped = input.readLong()
                    if (written != count) throw IOException("Frame archive footer count mismatch")
                    if (input.read() != -1) throw IOException("Unexpected bytes after archive footer")
                    return Summary(count, dropped, version)
                }
                if (timestampNs <= previousTimestampNs) {
                    throw IOException("Frame archive timestamps are not strictly increasing")
                }
                val width = input.readInt()
                val height = input.readInt()
                val rawBytes = input.readInt()
                val compressedBytes = input.readInt()
                val expectedCrc = input.readInt()
                val expectedRawBytes = width.toLong() * height.toLong()
                if (width <= 0 || height <= 0 ||
                    expectedRawBytes > BasaltFrameArchiveWriter.MAX_FRAME_BYTES ||
                    rawBytes.toLong() != expectedRawBytes ||
                    compressedBytes <= 0 ||
                    compressedBytes > BasaltFrameArchiveWriter.MAX_FRAME_BYTES + 1_048_576
                ) throw IOException("Invalid frame dimensions or payload size")

                val compressed = ByteArray(compressedBytes)
                input.readFully(compressed)
                val pixels = ByteArray(rawBytes)
                InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
                    var offset = 0
                    while (offset < pixels.size) {
                        val read = inflater.read(pixels, offset, pixels.size - offset)
                        if (read < 0) throw IOException("Compressed frame ended early")
                        offset += read
                    }
                    if (inflater.read() != -1) throw IOException("Compressed frame exceeds declared size")
                }
                val actualCrc = CRC32().apply { update(pixels) }.value.toInt()
                if (actualCrc != expectedCrc) throw IOException("Frame checksum mismatch")
                onFrame(Frame(timestampNs, width, height, pixels))
                previousTimestampNs = timestampNs
                count++
            }
        }
    }
}
