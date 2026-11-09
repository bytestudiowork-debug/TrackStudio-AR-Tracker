package com.trackstudio.app.tracking

import org.opencv.core.Mat
import org.opencv.core.Point3

data class TrackingVerificationSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val colorArgb: Long
)

/**
 * Builds a small static wireframe object in the reconstructed world and
 * projects it through each genuinely solved camera pose. This is a visual
 * diagnostic, not a claim that the arbitrary cube represents a real object.
 */
internal object TrackingVerificationProjector {
    fun project(
        landmarks: List<CameraLandmark>,
        poses: List<CameraPose>,
        cameraMatrix: Mat,
        imageWidth: Int,
        imageHeight: Int
    ): Map<Int, List<TrackingVerificationSegment>> {
        if (landmarks.size < 8 || cameraMatrix.empty() ||
            cameraMatrix.rows() != 3 || cameraMatrix.cols() != 3 ||
            imageWidth <= 0 || imageHeight <= 0
        ) return emptyMap()

        val fx = cameraMatrix.get(0, 0)?.firstOrNull() ?: return emptyMap()
        val skew = cameraMatrix.get(0, 1)?.firstOrNull() ?: return emptyMap()
        val cx = cameraMatrix.get(0, 2)?.firstOrNull() ?: return emptyMap()
        val fy = cameraMatrix.get(1, 1)?.firstOrNull() ?: return emptyMap()
        val cy = cameraMatrix.get(1, 2)?.firstOrNull() ?: return emptyMap()
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0.0 || fy <= 0.0) return emptyMap()

        val stable = landmarks.filter {
            it.observationCount >= 3 &&
                it.meanReprojectionErrorPx.isFinite() &&
                it.meanReprojectionErrorPx <= 5.0 &&
                it.position.x.isFinite() && it.position.y.isFinite() && it.position.z.isFinite()
        }
        if (stable.size < 8) return emptyMap()

        val reference = poses.firstOrNull {
            it.status == CameraPoseStatus.REFERENCE ||
                it.status == CameraPoseStatus.PNP_REGISTERED
        } ?: return emptyMap()

        fun project(point: Point3, pose: CameraPose): Pair<Float, Float>? {
            val r = pose.rotation
            val t = pose.translation
            if (r.size < 9 || t.size < 3) return null
            val x = r[0] * point.x + r[1] * point.y + r[2] * point.z + t[0]
            val y = r[3] * point.x + r[4] * point.y + r[5] * point.z + t[1]
            val z = r[6] * point.x + r[7] * point.y + r[8] * point.z + t[2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 1e-5) return null
            val u = (fx * x + skew * y) / z + cx
            val v = fy * y / z + cy
            if (!u.isFinite() || !v.isFinite()) return null
            return u.toFloat() to v.toFloat()
        }

        // Anchor to a high-confidence reconstructed landmark visible in the
        // reference camera so the test object starts inside the image.
        val anchor = stable.asSequence()
            .mapNotNull { landmark ->
                val p = project(landmark.position, reference) ?: return@mapNotNull null
                if (p.first !in 0f..imageWidth.toFloat() ||
                    p.second !in 0f..imageHeight.toFloat()
                ) return@mapNotNull null
                val dx = (p.first - imageWidth * 0.5f) / imageWidth
                val dy = (p.second - imageHeight * 0.5f) / imageHeight
                landmark to (dx * dx + dy * dy +
                    landmark.meanReprojectionErrorPx * 0.0005 -
                    landmark.observationCount.coerceAtMost(20) * 0.00002)
            }.minByOrNull { it.second }?.first?.position ?: return emptyMap()

        val xs = stable.map { it.position.x }.sorted()
        val ys = stable.map { it.position.y }.sorted()
        val zs = stable.map { it.position.z }.sorted()
        fun span(values: List<Double>) = values[(values.size * 9 / 10).coerceAtMost(values.lastIndex)] -
            values[(values.size / 10).coerceAtMost(values.lastIndex)]
        val extent = maxOf(span(xs), span(ys), span(zs))
        if (!extent.isFinite() || extent <= 1e-6) return emptyMap()
        val size = (extent * 0.06).coerceIn(0.03, 0.3)
        val h = size * 0.5
        val vertices = listOf(
            Point3(anchor.x-h, anchor.y-h, anchor.z-h),
            Point3(anchor.x+h, anchor.y-h, anchor.z-h),
            Point3(anchor.x+h, anchor.y+h, anchor.z-h),
            Point3(anchor.x-h, anchor.y+h, anchor.z-h),
            Point3(anchor.x-h, anchor.y-h, anchor.z+h),
            Point3(anchor.x+h, anchor.y-h, anchor.z+h),
            Point3(anchor.x+h, anchor.y+h, anchor.z+h),
            Point3(anchor.x-h, anchor.y+h, anchor.z+h)
        )
        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )
        val output = linkedMapOf<Int, List<TrackingVerificationSegment>>()
        poses.asSequence()
            .filter { it.status == CameraPoseStatus.REFERENCE ||
                it.status == CameraPoseStatus.PNP_REGISTERED ||
                it.status == CameraPoseStatus.RELATIVE_ESTIMATE }
            .sortedBy { it.frameIndex }
            .forEach { pose ->
                val projected = vertices.map { project(it, pose) }
                val lines = ArrayList<TrackingVerificationSegment>(12)
                edges.forEach { (a, b) ->
                    val p1 = projected[a]
                    val p2 = projected[b]
                    if (p1 != null && p2 != null) lines += TrackingVerificationSegment(
                        p1.first, p1.second, p2.first, p2.second, 0xFF00E5FFL
                    )
                }
                if (lines.size >= 3) output[pose.frameIndex] = lines
            }
        return output
    }
}
