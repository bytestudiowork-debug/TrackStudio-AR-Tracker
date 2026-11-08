package com.trackstudio.artracker

/**
 * Optional JNI entry point for the separately built Basalt ARM64 runtime.
 *
 * The smoke call proves that the APK contains both TrackStudio's JNI adapter and
 * the matching libbasalt.so dependency. It does not start or claim live VIO.
 */
internal object BasaltNativeBridge {
    private val loadError: String?

    init {
        loadError = try {
            System.loadLibrary("trackstudio_basalt_jni")
            null
        } catch (error: UnsatisfiedLinkError) {
            error.message ?: "Native library could not be loaded"
        } catch (error: SecurityException) {
            error.message ?: "Native library loading was blocked"
        }
    }

    fun status(): String {
        val error = loadError
        if (error != null) return "not packaged for this ABI ($error)"
        return try {
            nativeBuildInfo()
        } catch (error: UnsatisfiedLinkError) {
            "JNI symbol unavailable (${error.message ?: "unknown error"})"
        }
    }

    @JvmStatic
    private external fun nativeBuildInfo(): String
}
