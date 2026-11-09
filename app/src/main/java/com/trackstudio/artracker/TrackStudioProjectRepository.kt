package com.trackstudio.artracker

import android.content.Context
import org.json.JSONObject
import java.io.File

internal data class TrackStudioProject(
    val id: String,
    val name: String,
    val directory: File,
    val createdAt: Long,
    val recordingCount: Int
)

/** Lightweight file-based project index; no database or background indexing service. */
internal class TrackStudioProjectRepository(context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "TrackStudioAR/Projects")

    fun listProjects(): List<TrackStudioProject> {
        if (!root.exists()) return emptyList()
        return root.listFiles()?.filter { it.isDirectory && File(it, "project.json").isFile }
            ?.mapNotNull { readProject(it) }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun createProject(rawName: String): TrackStudioProject {
        val displayName = rawName.trim().replace(Regex("\\s+"), " ")
        require(displayName.isNotEmpty()) { "Project name cannot be empty" }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "")
            .trim().replace(Regex("\\s+"), "_").ifBlank { "Project" }
        root.mkdirs()
        var directory = File(root, safeName)
        if (directory.exists()) directory = File(root, "${safeName}_${System.currentTimeMillis()}")
        val createdAt = System.currentTimeMillis()
        listOf("Footage", "Tracking Data", "Sensors", "Thumbnails", "Recordings").forEach {
            File(directory, it).mkdirs()
        }
        val json = JSONObject().put("schemaVersion", 1).put("id", directory.name)
            .put("projectName", displayName).put("createdAtUnixMs", createdAt).put("recordingCount", 0)
        File(directory, "project.json").writeText(json.toString(2), Charsets.UTF_8)
        return TrackStudioProject(directory.name, displayName, directory, createdAt, 0)
    }

    private fun readProject(directory: File): TrackStudioProject? = try {
        val json = JSONObject(File(directory, "project.json").readText(Charsets.UTF_8))
        val count = File(directory, "Recordings").listFiles()
            ?.count { it.isDirectory && File(it, "recording.json").isFile } ?: 0
        TrackStudioProject(json.optString("id", directory.name),
            json.optString("projectName", directory.name), directory,
            json.optLong("createdAtUnixMs", directory.lastModified()), count)
    } catch (_: Exception) { null }
}
