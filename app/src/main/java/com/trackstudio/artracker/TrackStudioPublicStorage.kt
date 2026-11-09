package com.trackstudio.artracker

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Copies project footage and diagnostic session files into public Documents. */
internal object TrackStudioPublicStorage {
    fun exportProject(context: Context, project: TrackStudioProject): Int {
        require(project.directory.isDirectory) { "Project folder is missing" }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Storage permission is required to save into Documents")

        val safeProjectName = safeSegment(project.name)
        var copied = 0
        project.directory.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(project.directory).invariantSeparatorsPath
            if (copyFile(context, source, "$safeProjectName/$relative")) copied++
        }

        return copied
    }

    private fun copyFile(context: Context, source: File, relativePath: String): Boolean {
        val cleanRelativePath = relativePath.replace('\\', '/')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativeFolder = Environment.DIRECTORY_DOCUMENTS + "/TrackStudio/" +
                cleanRelativePath.substringBeforeLast('/', "")
            val collection: Uri = MediaStore.Files.getContentUri("external")
            // Replace a previous export with the same path instead of creating duplicate entries.
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(source.name, relativeFolder),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)), null, null)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(source))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not open Documents output")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return true
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val destination = File(File(documents, "TrackStudio"), cleanRelativePath)
        destination.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create " + it.path)
        }
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun safeSegment(value: String): String =
        value.trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .replace(Regex("\\s+"), "_").ifBlank { "Project" }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }
}
