package com.trackstudio.app.tracking

import org.opencv.core.Mat

/**
 * UI-independent reconstruction contract shared by the custom and GLOMAP engines.
 *
 * The frame provider owns decoding; an engine must release every Mat it obtains
 * from the provider after processing it. The camera matrix is borrowed and must
 * not be released or mutated by an implementation.
 */
interface ReconstructionEngine {
    suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit = {}
    ): CameraTrackingResult
}
