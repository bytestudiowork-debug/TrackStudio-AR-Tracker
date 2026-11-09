package com.trackstudio.app.tracking

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Loads the OpenCV Android runtime on demand.
 *
 * The tracker calls this before using any OpenCV classes. Keeping the load
 * lazy avoids adding startup work to users who are only browsing projects.
 */
object OpenCvRuntime {

    private const val TAG = "TrackStudioCV"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true

        return try {
            loaded = OpenCVLoader.initLocal()
            if (loaded) {
                Log.i(TAG, "OpenCV runtime loaded")
            } else {
                Log.e(TAG, "OpenCV runtime initialization failed")
            }
            loaded
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to initialize OpenCV", error)
            false
        }
    }
}
