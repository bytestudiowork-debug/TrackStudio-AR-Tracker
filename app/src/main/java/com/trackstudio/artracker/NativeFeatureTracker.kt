package com.trackstudio.artracker

import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.roundToLong

/**
 * OpenCV pyramidal Lucas-Kanade optical-flow tracker.
 * OpenCV is optional at runtime: if its native library cannot load, camera capture
 * continues and feature statistics remain zero instead of crashing the app.
 */
internal object NativeFeatureTracker {
    private const val MAX_CORNERS = 180
    // Keep native OpenCV off during camera bring-up on older Android devices.
    // Re-enable only after the camera pipeline is verified on target hardware.
    private const val ENABLE_OPENCV_TRACKING = false
    private var previousGray: Mat? = null
    private var initialized = false
    private var unavailable = false

    @Synchronized
    fun reset() {
        releasePrevious()
    }

    @Synchronized
    fun processFrame(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): LongArray {
        if (!ENABLE_OPENCV_TRACKING || unavailable) return EMPTY_RESULT
        if (width < 24 || height < 24 || rowStride < width || pixelStride != 1 ||
            luma.size < rowStride * height
        ) return EMPTY_RESULT

        return try {
            if (!initialized) {
                initialized = OpenCVLoader.initLocal()
                if (!initialized) {
                    unavailable = true
                    return EMPTY_RESULT
                }
            }
            processFrameWithOpenCv(luma, width, height)
        } catch (error: LinkageError) {
            // Missing/incompatible OpenCV JNI libraries must not terminate camera capture.
            unavailable = true
            releasePrevious()
            EMPTY_RESULT
        } catch (error: RuntimeException) {
            // Treat a frame-level OpenCV failure as a dropped frame and reset optical flow.
            releasePrevious()
            EMPTY_RESULT
        }
    }

    private fun processFrameWithOpenCv(luma: ByteArray, width: Int, height: Int): LongArray {
        val current = Mat(height, width, CvType.CV_8UC1)
        try {
            current.put(0, 0, luma)
            val previous = previousGray
            if (previous == null || previous.empty() ||
                previous.cols() != width || previous.rows() != height
            ) {
                releasePrevious()
                previousGray = current
                return longArrayOf(detectCorners(current), 0, 0, 0)
            }

            val previousCorners = MatOfPoint()
            val previousPoints = MatOfPoint2f()
            val nextPoints = MatOfPoint2f()
            val status = MatOfByte()
            val errors = MatOfFloat()
            var tracked = 0
            var sumX = 0.0
            var sumY = 0.0
            var detected = 0

            try {
                Imgproc.goodFeaturesToTrack(previous, previousCorners, MAX_CORNERS, 0.01, 8.0)
                val cornerArray = previousCorners.toArray()
                detected = cornerArray.size
                if (cornerArray.isNotEmpty()) {
                    previousPoints.fromArray(*cornerArray)
                    Video.calcOpticalFlowPyrLK(
                        previous, current, previousPoints, nextPoints, status, errors,
                        Size(21.0, 21.0), 3
                    )
                    val from = previousPoints.toArray()
                    val to = nextPoints.toArray()
                    val valid = status.toArray()
                    val err = errors.toArray()
                    val count = minOf(from.size, to.size, valid.size)
                    for (i in 0 until count) {
                        if (valid[i].toInt() == 0 || (i < err.size && err[i] > 35f)) continue
                        val dx = to[i].x - from[i].x
                        val dy = to[i].y - from[i].y
                        if (!dx.isFinite() || !dy.isFinite()) continue
                        tracked++
                        sumX += dx
                        sumY += dy
                    }
                }
            } finally {
                previousCorners.release()
                previousPoints.release()
                nextPoints.release()
                status.release()
                errors.release()
                previous.release()
            }

            previousGray = current
            val motionX = if (tracked > 0) (sumX * 1000.0 / tracked).roundToLong() else 0L
            val motionY = if (tracked > 0) (sumY * 1000.0 / tracked).roundToLong() else 0L
            return longArrayOf(detected.toLong(), tracked.toLong(), motionX, motionY)
        } catch (error: LinkageError) {
            current.release()
            throw error
        } catch (error: RuntimeException) {
            current.release()
            throw error
        }
    }

    private fun detectCorners(gray: Mat): Long {
        val corners = MatOfPoint()
        return try {
            Imgproc.goodFeaturesToTrack(gray, corners, MAX_CORNERS, 0.01, 8.0)
            corners.rows().toLong()
        } finally {
            corners.release()
        }
    }

    private fun releasePrevious() {
        try {
            previousGray?.release()
        } catch (_: LinkageError) {
            // Native cleanup is best-effort when OpenCV itself failed to load.
        } catch (_: RuntimeException) {
            // A partially initialized Mat can fail during release.
        } finally {
            previousGray = null
        }
    }

    private val EMPTY_RESULT = longArrayOf(0, 0, 0, 0)
}
