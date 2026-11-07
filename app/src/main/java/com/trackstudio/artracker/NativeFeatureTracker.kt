package com.trackstudio.artracker

/** JNI bridge to the in-app visual feature tracking prototype. */
internal object NativeFeatureTracker {
    init { System.loadLibrary("trackstudio_tracker") }

    external fun processFrame(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): LongArray
}
