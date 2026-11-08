package com.trackstudio.artracker

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a self-contained replay bundle from the newest completed capture.
 * A replay report is written only after capture shutdown, so an active CSV/archive
 * is never mistaken for a complete dataset.
 */
internal object SessionBundleExporter {
    private const val TAG = "SessionBundleExporter"

    fun createLatest(sessionsDirectory: File, outputDirectory: File): File {
        require(sessionsDirectory.isDirectory) { "No completed capture directory found" }
        val report = sessionsDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("capture-") && it.name.endsWith(".replay.json") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("No completed capture yet. Stop a capture by leaving the app, then return.")

        val stem = report.name.removeSuffix(".replay.json")
        val csv = File(sessionsDirectory, "$stem.csv")
        val metadata = File(sessionsDirectory, "$stem.json")
        val frames = File(sessionsDirectory, "$stem.basalt.frames")
        val required = listOf(csv, metadata, frames, report)
        val missing = required.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Capture bundle is incomplete: ${missing.joinToString { it.name }}")
        }

        val csvReport = csv.bufferedReader().use { SessionReplayAnalyzer.analyze(it.lineSequence()) }
        if (!csvReport.validation.headerValid || !csvReport.validation.isValid) {
            throw IllegalStateException("Capture CSV failed integrity validation; export the session for manual diagnosis is unsafe.")
        }
        val archiveSummary = BasaltFrameArchiveReader.read(frames) { }
        if (archiveSummary.frameCount == 0L) {
            throw IllegalStateException("Capture has no archived camera frames.")
        }

        outputDirectory.mkdirs()
        val output = File(outputDirectory, "$stem-basalt-replay.zip")
        val temporary = File(outputDirectory, output.name + ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                required.forEach { source ->
                    zip.putNextEntry(ZipEntry(source.name))
                    source.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val readme = """
                    TrackStudio AR Tracker capture bundle
                    Session: $stem
                    Contents: raw sensor/camera CSV, Camera2 metadata, lossless GRAY8 frame archive, replay screening report.
                    This bundle contains recorded inputs and diagnostics, not a Basalt pose result.
                    Do not publish this bundle publicly; it may contain sensor and camera recordings.
                """.trimIndent()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(readme.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (output.exists() && !output.delete()) {
                throw IllegalStateException("Could not replace previous export")
            }
            if (!temporary.renameTo(output)) {
                throw IllegalStateException("Could not finalize capture export")
            }
            return output
        } catch (error: Exception) {
            temporary.delete()
            Log.e(TAG, "Capture bundle export failed", error)
            throw error
        }
    }
}
