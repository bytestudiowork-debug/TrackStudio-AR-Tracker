package com.trackstudio.app.tracking.glomap

/**
 * Ports GlobalPositioner::InitializeRandomPositions and the random-point
 * initialization in AddPointToCameraConstraints for GLOMAP ONLY_POINTS.
 *
 * Inputs are ordered lists so the caller controls traversal order. GLOMAP's
 * upstream containers are unordered maps; matching the exact assignment of
 * seeded samples therefore requires supplying the same iteration order as
 * the source containers. The MT19937 stream and per-vector sampling are kept
 * compatible; this class does not invent an alternative initializer.
 */
internal object GlomapGlobalPositioningInitializer {
    data class Options(
        val seed: Int = 1,
        val minimumViewsPerTrack: Int = 3,
        val generateRandomPositions: Boolean = true,
        val generateRandomPoints: Boolean = true,
        val optimizePositions: Boolean = true,
        val optimizePoints: Boolean = true
    )

    data class FrameInput(
        val frameId: Int,
        /** CenterFromPose(RigFromWorld()) evaluated before initialization. */
        val cameraCenterFromPose: DoubleArray,
        /** Existing translation storage, retained for unconstrained frames in the non-random path. */
        val existingTranslation: DoubleArray
    )

    data class ImageInput(
        val imageId: Int,
        val frameId: Int,
        val isRegistered: Boolean
    )

    data class ImagePairInput(
        val firstImageId: Int,
        val secondImageId: Int,
        val isValid: Boolean
    )

    data class TrackInput(
        val trackId: Int,
        val observationImageIds: List<Int>,
        val initializedPoint: DoubleArray? = null
    )

    data class Result(
        val frameTranslationsById: Map<Int, DoubleArray>,
        val pointsByTrack: Map<Int, DoubleArray?>,
        val initializedPointTrackIds: Set<Int>,
        val constrainedFrameIds: Set<Int>,
        val randomEngineSamplesConsumed: Int
    )

    fun initialize(
        frames: List<FrameInput>,
        images: List<ImageInput>,
        imagePairs: List<ImagePairInput>,
        tracks: List<TrackInput>,
        options: Options = Options()
    ): Result {
        require(options.minimumViewsPerTrack >= 2)
        require(frames.map { it.frameId }.distinct().size == frames.size)
        require(images.map { it.imageId }.distinct().size == images.size)
        frames.forEach {
            requireVector(it.cameraCenterFromPose, "frame camera center")
            requireVector(it.existingTranslation, "frame translation")
        }
        tracks.forEach { track ->
            track.initializedPoint?.let { requireVector(it, "track point") }
        }

        val imageById = images.associateBy { it.imageId }
        val constrained = LinkedHashSet<Int>()

        // Upstream first inserts frame IDs for every valid image pair.
        for (pair in imagePairs) {
            if (!pair.isValid) continue
            imageById[pair.firstImageId]?.let { constrained += it.frameId }
            imageById[pair.secondImageId]?.let { constrained += it.frameId }
        }

        // Then inserts registered-image frames for tracks with enough views.
        for (track in tracks) {
            if (track.observationImageIds.size < options.minimumViewsPerTrack) continue
            for (imageId in track.observationImageIds) {
                val image = imageById[imageId] ?: continue
                if (image.isRegistered) constrained += image.frameId
            }
        }

        val random = GlomapMt19937(options.seed)
        val cameraCenters = LinkedHashMap<Int, DoubleArray>(frames.size)
        if (!options.generateRandomPositions || !options.optimizePositions) {
            for (frame in frames) {
                cameraCenters[frame.frameId] = if (frame.frameId in constrained) {
                    frame.cameraCenterFromPose.copyOf()
                } else {
                    frame.existingTranslation.copyOf()
                }
            }
        } else {
            // Upstream iterates all frames: constrained get random centers;
            // unconstrained frames are converted from pose to camera centers.
            for (frame in frames) {
                cameraCenters[frame.frameId] = if (frame.frameId in constrained) {
                    random.randomVector3d(-1.0, 1.0, 100.0)
                } else {
                    frame.cameraCenterFromPose.copyOf()
                }
            }
        }

        val points = LinkedHashMap<Int, DoubleArray?>(tracks.size)
        val initialized = LinkedHashSet<Int>()
        for (track in tracks) {
            var point = track.initializedPoint?.copyOf()
            if (track.observationImageIds.size >= options.minimumViewsPerTrack &&
                options.optimizePoints && options.generateRandomPoints
            ) {
                point = random.randomVector3d(-1.0, 1.0, 100.0)
                initialized += track.trackId
            }
            points[track.trackId] = point
        }

        return Result(
            frameTranslationsById = cameraCenters,
            pointsByTrack = points,
            initializedPointTrackIds = initialized,
            constrainedFrameIds = constrained,
            randomEngineSamplesConsumed = random.samplesConsumed
        )
    }

    private fun requireVector(value: DoubleArray, label: String) {
        require(value.size == 3 && value.all { it.isFinite() }) {
            "$label must contain three finite values."
        }
    }
}
