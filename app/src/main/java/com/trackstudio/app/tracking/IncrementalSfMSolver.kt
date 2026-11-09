package com.trackstudio.app.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.KeyPoint
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.core.Point3
import com.trackstudio.app.tracking.glomap.GlomapGlobalPositioningProblemBuilder
import com.trackstudio.app.tracking.glomap.GlomapNativeSolver
import kotlin.math.max

/**
 * Incremental, map-based camera solve.
 *
 * Pass one evaluates a bounded set of temporal pairs and selects a geometrically
 * well-conditioned initialization. Pass two seeds persistent tracks from that
 * pair, registers later frames with PnP against the same 3D map, and periodically
 * adds triangulated landmarks. No per-frame recoverPose translation is accumulated.
 *
 * The sparse map uses the arbitrary unit baseline of the initial pair. It is
 * internally consistent, but has no metric scale until a user supplies scale.
 */
internal class IncrementalSfMSolver(
    private val maxFeatures: Int = 1800,
    private val ratioThreshold: Float = 0.78f
) {
    private data class CachedFrame(
        val keypoints: Array<KeyPoint>,
        val descriptors: Mat,
        val width: Int,
        val height: Int
    )

    private data class PairGeometry(
        val matches: List<Pair<Int, Int>>,
        val rotation: DoubleArray,
        val translation: DoubleArray,
        val inlierCount: Int
    )

    suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        require(frameCount >= 3) { "Camera tracking needs at least three frames for initialization." }
        require(!cameraMatrix.empty() && cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3) {
            "A valid 3x3 camera intrinsic matrix is required."
        }
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV could not be initialized on this device." }

        val pairCandidates = InitialPairSelector.temporalCandidates(
            frameCount = frameCount,
            anchorCount = 6,
            offsets = listOf(2, 4, 6, 9, 13, 18, 24, 32),
            maximumPairs = 36
        )
        require(pairCandidates.isNotEmpty()) { "No initialization frame pairs are available." }
        val candidateFrames = pairCandidates.flatMap { listOf(it.first, it.second) }.toSet()
        val processedFrameFlags = BooleanArray(frameCount)
        var processedFrameCount = 0
        fun markFrameProcessed(index: Int) {
            if (index in processedFrameFlags.indices && !processedFrameFlags[index]) {
                processedFrameFlags[index] = true
                processedFrameCount++
            }
        }
        val cache = linkedMapOf<Int, CachedFrame>()
        val orb = ORB.create(maxFeatures.coerceIn(400, 5000))
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

        try {
            // Bounded first pass: retain descriptors only for frames participating
            // in the coarse pair schedule, not for every frame in a long sequence.
            val orderedCandidateFrames = candidateFrames.sorted()
            for ((sampleIndex, index) in orderedCandidateFrames.withIndex()) {
                currentCoroutineContext().ensureActive()
                val source = frameProvider(index)
                    ?: throw IllegalStateException("Unable to decode frame ${index + 1}.")
                try {
                    if (index in candidateFrames) {
                        val gray = toGray(source)
                        val keypoints = org.opencv.core.MatOfKeyPoint()
                        val descriptors = Mat()
                        try {
                            val detectionMask = Mat()
                            try {
                                orb.detectAndCompute(gray, detectionMask, keypoints, descriptors)
                            } finally {
                                detectionMask.release()
                            }
                            cache[index] = CachedFrame(
                                keypoints.toArray(), descriptors,
                                source.cols(), source.rows()
                            )
                        } catch (t: Throwable) {
                            descriptors.release()
                            throw t
                        } finally {
                            gray.release()
                            keypoints.release()
                        }
                    }
                } finally {
                    source.release()
                }
                markFrameProcessed(index)
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = index + 1,
                        frameCount = frameCount,
                        phase = "Analyzing candidate frames",
                        overallFraction = 0.25f * (sampleIndex + 1) / orderedCandidateFrames.size,
                        completedFrames = processedFrameCount,
                        totalFrames = frameCount
                    )
                )
            }

            val evaluations = mutableListOf<InitialPairEvaluation>()
            pairCandidates.forEachIndexed { candidateIndex, pair ->
                currentCoroutineContext().ensureActive()
                val first = cache[pair.first]
                val second = cache[pair.second]
                if (first != null && second != null) {
                    evaluations += OpenCvInitialPairEvaluator.evaluate(
                        firstFrame = pair.first,
                        secondFrame = pair.second,
                        firstKeypoints = first.keypoints,
                        firstDescriptors = first.descriptors,
                        secondKeypoints = second.keypoints,
                        secondDescriptors = second.descriptors,
                        cameraMatrix = cameraMatrix,
                        frameWidth = minOf(first.width, second.width),
                        frameHeight = minOf(first.height, second.height)
                    )
                }
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = frameCount,
                        frameCount = frameCount,
                        phase = "Evaluating camera-pair geometry (${candidateIndex + 1}/${pairCandidates.size})",
                        overallFraction = 0.25f + 0.10f * (candidateIndex + 1) / pairCandidates.size,
                        completedFrames = processedFrameCount,
                        totalFrames = frameCount
                    )
                )
            }

            // GLOMAP-style global rotation averaging over the verified temporal view graph.
            // Keep this global solution separate from incremental PnP poses until global
            // positioning can jointly estimate translations in the same coordinate gauge.
            val viewGraphRotation = GlobalViewGraphSolver.solve(
                evaluations = evaluations,
                policy = InitialPairPolicy()
            )
            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Global rotation averaging · view graph",
                    overallFraction = 0.355f,
                    completedFrames = processedFrameCount,
                    totalFrames = frameCount
                )
            )

            val selection = InitialPairSelector.select(evaluations.map { it.candidate })
            val selected = selection.selected
                ?: throw IllegalStateException(
                    "No reliable 3D initialization pair: ${selection.reason}. Try footage with visible texture and sideways camera movement."
                )
            val firstFrame = cache[selected.firstFrame]
                ?: error("Selected first frame was not retained.")
            val secondFrame = cache[selected.secondFrame]
                ?: error("Selected second frame was not retained.")

            onProgress(
                CameraTrackingProgress(
                    selected.secondFrame + 1,
                    frameCount,
                    "Initializing 3D camera map",
                    overallFraction = 0.36f,
                    completedFrames = processedFrameCount,
                    totalFrames = frameCount
                )
            )
            val geometry = estimatePairGeometry(
                firstFrame, secondFrame, cameraMatrix, matcher
            ) ?: throw IllegalStateException(
                "The selected pair passed evaluation but could not be reconstructed."
            )
            if (geometry.inlierCount < 30) {
                throw IllegalStateException("Initial pair has too few cheirality-verified points.")
            }

            val trackBuilder = FeatureTrackBuilder()
            trackBuilder.seedPair(
                selected.firstFrame,
                selected.secondFrame,
                firstFrame.keypoints,
                secondFrame.keypoints,
                geometry.matches
            )

            val poseByFrame = arrayOfNulls<CameraPose>(frameCount)
            val referencePose = CameraPose(
                selected.firstFrame, identity3(), doubleArrayOf(0.0, 0.0, 0.0),
                CameraPoseStatus.REFERENCE
            )
            val secondPose = CameraPose(
                selected.secondFrame, geometry.rotation, geometry.translation,
                CameraPoseStatus.RELATIVE_ESTIMATE
            )
            poseByFrame[selected.firstFrame] = referencePose
            poseByFrame[selected.secondFrame] = secondPose

            var tracks = trackBuilder.build()
            val initialReconstruction = SfMTriangulator.triangulate(
                tracks, listOf(referencePose, secondPose), cameraMatrix
            )
            require(initialReconstruction.landmarks.size >= 30) {
                "Initial 3D map is too small (${initialReconstruction.landmarks.size} landmarks). " +
                    "Use footage with stronger camera translation and more static scene texture."
            }
            val landmarksByTrack = linkedMapOf<Int, CameraLandmark>()
            initialReconstruction.landmarks.forEach { landmarksByTrack[it.trackId] = it }

            var previousDescriptors = secondFrame.descriptors.clone()
            var previousKeypoints = secondFrame.keypoints
            var totalMatches = geometry.matches.size
            var totalCorrespondences = geometry.matches.size
            var totalInliers = geometry.inlierCount
            var registeredFrames = 2
            var acceptedRegistrationsSinceMapUpdate = 0
            val diagnostics = MutableList(frameCount) { index ->
                FrameTrackingDiagnostic(index, 0, 0, 0, "Not registered")
            }
            diagnostics[selected.firstFrame] = FrameTrackingDiagnostic(
                selected.firstFrame, firstFrame.keypoints.size, 0, 0, "Initialization reference"
            )
            diagnostics[selected.secondFrame] = FrameTrackingDiagnostic(
                selected.secondFrame, secondFrame.keypoints.size,
                geometry.matches.size, geometry.inlierCount, "Initial pair reconstructed"
            )

            try {
                for (frameIndex in selected.secondFrame + 1 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex,
                            frameCount,
                            "Registering camera frame",
                            overallFraction = 0.36f + 0.32f *
                                (frameIndex - selected.secondFrame).toFloat() /
                                (frameCount - selected.secondFrame).coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                    val source = frameProvider(frameIndex)
                        ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                    val gray = toGray(source)
                    val keypointsMat = org.opencv.core.MatOfKeyPoint()
                    val descriptors = Mat()
                    try {
                        val detectionMask = Mat()
                        try {
                            orb.detectAndCompute(gray, detectionMask, keypointsMat, descriptors)
                        } finally {
                            detectionMask.release()
                        }
                        val currentKeypoints = keypointsMat.toArray()
                        val matches = if (!previousDescriptors.empty() && !descriptors.empty()) {
                            verifiedMatches(
                                previousDescriptors, previousKeypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                        } else emptyList()

                        totalMatches += matches.size
                        if (matches.isEmpty()) {
                            trackBuilder.breakContinuity()
                        } else {
                            trackBuilder.addInlierMatches(
                                frameIndex, previousKeypoints, currentKeypoints, matches
                            )
                        }
                        
                        var registration = IncrementalCameraRegistrar.register(
                            frameIndex = frameIndex,
                            frameWidth = source.cols(),
                            frameHeight = source.rows(),
                            featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                            landmarks = landmarksByTrack.values.toList(),
                            cameraMatrix = cameraMatrix
                        )
                        // If adjacent-frame tracking lost the seed-map features, try
                        // re-localizing against the initialization keyframe before rejecting
                        // this camera. This is a recovery attempt, not a relaxed PnP gate.
                        if (!registration.accepted &&
                            !secondFrame.descriptors.empty() && !descriptors.empty()
                        ) {
                            val anchorMatches = verifiedMatches(
                                secondFrame.descriptors, secondFrame.keypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                            if (anchorMatches.isNotEmpty()) {
                                trackBuilder.addInlierMatchesFromFrame(
                                    selected.secondFrame, frameIndex,
                                    secondFrame.keypoints, currentKeypoints, anchorMatches
                                )
                                                                val recovered = IncrementalCameraRegistrar.register(
                                    frameIndex = frameIndex,
                                    frameWidth = source.cols(),
                                    frameHeight = source.rows(),
                                    featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                                    landmarks = landmarksByTrack.values.toList(),
                                    cameraMatrix = cameraMatrix
                                )
                                if (recovered.accepted ||
                                    recovered.correspondenceCount > registration.correspondenceCount
                                ) registration = recovered
                            }
                        }
                        // Second-keyframe recovery can fail when a frame is much
                        // closer to the first seed. Try that seed only after the
                        // adjacent-frame and second-seed attempts have failed.
                        if (!registration.accepted &&
                            !firstFrame.descriptors.empty() && !descriptors.empty()
                        ) {
                            val firstAnchorMatches = verifiedMatches(
                                firstFrame.descriptors, firstFrame.keypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                            if (firstAnchorMatches.isNotEmpty()) {
                                trackBuilder.addInlierMatchesFromFrame(
                                    selected.firstFrame, frameIndex,
                                    firstFrame.keypoints, currentKeypoints, firstAnchorMatches
                                )
                                                                val recoveredFromFirst = IncrementalCameraRegistrar.register(
                                    frameIndex = frameIndex,
                                    frameWidth = source.cols(),
                                    frameHeight = source.rows(),
                                    featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                                    landmarks = landmarksByTrack.values.toList(),
                                    cameraMatrix = cameraMatrix
                                )
                                if (recoveredFromFirst.accepted ||
                                    recoveredFromFirst.correspondenceCount > registration.correspondenceCount
                                ) registration = recoveredFromFirst
                            }
                        }
                        totalCorrespondences += registration.correspondenceCount
                        if (registration.accepted && registration.pose != null) {
                            poseByFrame[frameIndex] = registration.pose
                            totalInliers += registration.inlierCount
                            registeredFrames++
                            acceptedRegistrationsSinceMapUpdate++
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                registration.inlierCount,
                                "PnP registered (${registration.inlierCount} inliers, " +
                                    "median ${"%.2f".format(registration.medianReprojectionErrorPx)} px)"
                            )

                            // Triangulate periodically, not after every frame. This bounds
                            // CPU cost while allowing the map to grow during the solve.
                            if (acceptedRegistrationsSinceMapUpdate >= 2) {
                                tracks = trackBuilder.build()
                                val validPoses = poseByFrame.filterNotNull()
                                // Only triangulate tracks not already in the sparse map;
                                // repeated full-map passes waste CPU on longer clips.
                                val newTracks = tracks.filter { it.id !in landmarksByTrack }
                                if (newTracks.isNotEmpty()) {
                                    val update = SfMTriangulator.triangulate(
                                        newTracks, validPoses, cameraMatrix
                                    )
                                    update.landmarks.forEach { landmark ->
                                        landmarksByTrack[landmark.trackId] = landmark
                                    }
                                }
                                acceptedRegistrationsSinceMapUpdate = 0
                            }
                        } else {
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                registration.inlierCount, registration.reason
                            )
                        }

                        previousDescriptors.release()
                        previousDescriptors = descriptors.clone()
                        previousKeypoints = currentKeypoints
                    } finally {
                        source.release()
                        gray.release()
                        keypointsMat.release()
                        descriptors.release()
                    }
                    markFrameProcessed(frameIndex)
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex + 1,
                            frameCount,
                            "Registered ${registeredFrames} cameras",
                            overallFraction = 0.36f + 0.32f *
                                (frameIndex + 1 - selected.secondFrame).toFloat() /
                                (frameCount - selected.secondFrame).coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                }
            } finally {
                previousDescriptors.release()
            }

            // Run the same map-based registration backwards from the second
            // initialization camera. This fills frames before the selected pair
            // and the interval between the pair without inventing translations.
            trackBuilder.resetToFrame(selected.secondFrame)
            var reverseDescriptors = secondFrame.descriptors.clone()
            var reverseKeypoints = secondFrame.keypoints
            try {
                for (frameIndex in selected.secondFrame - 1 downTo 0) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex + 1,
                            frameCount,
                            "Registering earlier frames",
                            overallFraction = 0.68f + 0.22f *
                                (selected.secondFrame - frameIndex).toFloat() /
                                selected.secondFrame.coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                    val source = frameProvider(frameIndex)
                        ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                    val gray = toGray(source)
                    val keypointsMat = org.opencv.core.MatOfKeyPoint()
                    val descriptors = Mat()
                    try {
                        val detectionMask = Mat()
                        try {
                            orb.detectAndCompute(gray, detectionMask, keypointsMat, descriptors)
                        } finally {
                            detectionMask.release()
                        }
                        val currentKeypoints = keypointsMat.toArray()
                        val matches = if (!reverseDescriptors.empty() && !descriptors.empty()) {
                            verifiedMatches(
                                reverseDescriptors, reverseKeypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                        } else emptyList()
                        totalMatches += matches.size
                        if (matches.isEmpty()) {
                            trackBuilder.breakContinuity()
                        } else {
                            trackBuilder.addInlierMatchesBackward(
                                frameIndex, reverseKeypoints, currentKeypoints, matches
                            )
                        }
                        
                        if (frameIndex == selected.firstFrame) {
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                matches.size, "Initialization reference"
                            )
                        } else {
                            var registration = IncrementalCameraRegistrar.register(
                                frameIndex = frameIndex,
                                frameWidth = source.cols(),
                                frameHeight = source.rows(),
                                featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                                landmarks = landmarksByTrack.values.toList(),
                                cameraMatrix = cameraMatrix
                            )
                            // Backward recovery uses the same initialization keyframe,
                            // so a missed adjacent match does not permanently orphan IDs.
                            if (!registration.accepted &&
                                !secondFrame.descriptors.empty() && !descriptors.empty()
                            ) {
                                val anchorMatches = verifiedMatches(
                                    secondFrame.descriptors, secondFrame.keypoints,
                                    descriptors, currentKeypoints, cameraMatrix, matcher
                                )
                                if (anchorMatches.isNotEmpty()) {
                                    trackBuilder.addInlierMatchesFromFrame(
                                        selected.secondFrame, frameIndex,
                                        secondFrame.keypoints, currentKeypoints, anchorMatches
                                    )
                                                                        val recovered = IncrementalCameraRegistrar.register(
                                        frameIndex = frameIndex,
                                        frameWidth = source.cols(),
                                        frameHeight = source.rows(),
                                        featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                                        landmarks = landmarksByTrack.values.toList(),
                                        cameraMatrix = cameraMatrix
                                    )
                                    if (recovered.accepted ||
                                        recovered.correspondenceCount > registration.correspondenceCount
                                    ) registration = recovered
                                }
                            }
                            // Second-keyframe recovery can fail when a frame is much
                            // closer to the first seed. Try that seed only after the
                            // adjacent-frame and second-seed attempts have failed.
                            if (!registration.accepted &&
                                !firstFrame.descriptors.empty() && !descriptors.empty()
                            ) {
                                val firstAnchorMatches = verifiedMatches(
                                    firstFrame.descriptors, firstFrame.keypoints,
                                    descriptors, currentKeypoints, cameraMatrix, matcher
                                )
                                if (firstAnchorMatches.isNotEmpty()) {
                                    trackBuilder.addInlierMatchesFromFrame(
                                        selected.firstFrame, frameIndex,
                                        firstFrame.keypoints, currentKeypoints, firstAnchorMatches
                                    )
                                                                        val recoveredFromFirst = IncrementalCameraRegistrar.register(
                                        frameIndex = frameIndex,
                                        frameWidth = source.cols(),
                                        frameHeight = source.rows(),
                                        featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                                        landmarks = landmarksByTrack.values.toList(),
                                        cameraMatrix = cameraMatrix
                                    )
                                    if (recoveredFromFirst.accepted ||
                                        recoveredFromFirst.correspondenceCount > registration.correspondenceCount
                                    ) registration = recoveredFromFirst
                                }
                            }
                                totalCorrespondences += registration.correspondenceCount
                            if (registration.accepted && registration.pose != null) {
                                poseByFrame[frameIndex] = registration.pose
                                totalInliers += registration.inlierCount
                                registeredFrames++
                                acceptedRegistrationsSinceMapUpdate++
                                diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                    frameIndex, currentKeypoints.size, matches.size,
                                    registration.inlierCount,
                                    "PnP registered backwards (${registration.inlierCount} inliers)"
                                )
                                if (acceptedRegistrationsSinceMapUpdate >= 2) {
                                    tracks = trackBuilder.build()
                                    val newTracks = tracks.filter { it.id !in landmarksByTrack }
                                    val update = SfMTriangulator.triangulate(
                                        newTracks, poseByFrame.filterNotNull(), cameraMatrix
                                    )
                                    update.landmarks.forEach { landmark ->
                                        val old = landmarksByTrack[landmark.trackId]
                                        if (old == null ||
                                            landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx
                                        ) landmarksByTrack[landmark.trackId] = landmark
                                    }
                                    acceptedRegistrationsSinceMapUpdate = 0
                                }
                            } else {
                                diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                    frameIndex, currentKeypoints.size, matches.size,
                                    registration.inlierCount, registration.reason
                                )
                            }
                        }

                        reverseDescriptors.release()
                        reverseDescriptors = descriptors.clone()
                        reverseKeypoints = currentKeypoints
                        markFrameProcessed(frameIndex)
                        onProgress(
                            CameraTrackingProgress(
                                frameIndex + 1,
                                frameCount,
                                "Registered earlier frames",
                                overallFraction = 0.68f + 0.22f *
                                    (selected.secondFrame - frameIndex).toFloat() /
                                    selected.secondFrame.coerceAtLeast(1),
                                completedFrames = processedFrameCount,
                                totalFrames = frameCount
                            )
                        )
                    } finally {
                        source.release()
                        gray.release()
                        keypointsMat.release()
                        descriptors.release()
                    }
                }
            } finally {
                reverseDescriptors.release()
            }

            // Refresh the complete immutable snapshot once for final triangulation and refinement.
            tracks = trackBuilder.build()
            // Flush newly observed tracks once more after the final registered pose.
            SfMTriangulator.triangulate(
                tracks, poseByFrame.filterNotNull(), cameraMatrix
            ).landmarks.forEach { landmark ->
                val old = landmarksByTrack[landmark.trackId]
                if (old == null || landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx) {
                    landmarksByTrack[landmark.trackId] = landmark
                }
            }

            // Two bounded block-coordinate BA rounds alternate robust 3D
            // landmark optimization with registered-camera PnP refinement.
            // This reduces map/pose inconsistency without a large sparse solver.
            val refinementFrames = poseByFrame.indices.filter { index ->
                index != selected.firstFrame && poseByFrame[index] != null
            }
            repeat(2) { refinementRound ->
                currentCoroutineContext().ensureActive()
                val adjustedLandmarks = MobileBundleAdjuster.optimizeLandmarks(
                    featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                    landmarks = landmarksByTrack.values.toList(),
                    poses = poseByFrame.filterNotNull(),
                    cameraMatrix = cameraMatrix
                )
                adjustedLandmarks.forEach { landmark ->
                    val old = landmarksByTrack[landmark.trackId]
                    if (old == null || landmark.meanReprojectionErrorPx <= old.meanReprojectionErrorPx) {
                        landmarksByTrack[landmark.trackId] = landmark
                    }
                }
                for ((position, frameIndex) in refinementFrames.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val registration = IncrementalCameraRegistrar.register(
                        frameIndex = frameIndex,
                        frameWidth = firstFrame.width,
                        frameHeight = firstFrame.height,
                        featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                        landmarks = landmarksByTrack.values.toList(),
                        cameraMatrix = cameraMatrix
                    )
                    if (registration.accepted && registration.pose != null) {
                        poseByFrame[frameIndex] = registration.pose
                        diagnostics[frameIndex] = FrameTrackingDiagnostic(
                            frameIndex,
                            diagnostics[frameIndex].detectedFeatures,
                            diagnostics[frameIndex].matchedFeatures,
                            registration.inlierCount,
                            "Refined PnP (${registration.inlierCount} inliers, " +
                                "median ${"%.2f".format(registration.medianReprojectionErrorPx)} px)"
                        )
                    }
                    if (position % 8 == 0 || position == refinementFrames.lastIndex) {
                        onProgress(
                            CameraTrackingProgress(
                                frameIndex + 1,
                                frameCount,
                                "Bundle adjustment · refining cameras",
                                overallFraction = 0.90f + 0.09f *
                                    (refinementRound +
                                        (position + 1).toFloat() /
                                            refinementFrames.size.coerceAtLeast(1)) / 2f,
                                completedFrames = frameCount,
                                totalFrames = frameCount
                            )
                        )
                    }
                }

                val postPoseLandmarks = MobileBundleAdjuster.optimizeLandmarks(
                    featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                    landmarks = landmarksByTrack.values.toList(),
                    poses = poseByFrame.filterNotNull(),
                    cameraMatrix = cameraMatrix
                )
                postPoseLandmarks.forEach { landmark ->
                    val old = landmarksByTrack[landmark.trackId]
                    if (old == null || landmark.meanReprojectionErrorPx <= old.meanReprojectionErrorPx) {
                        landmarksByTrack[landmark.trackId] = landmark
                    }
                }
                val refinedMap = SfMTriangulator.triangulate(
                    tracks, poseByFrame.filterNotNull(), cameraMatrix
                )
                refinedMap.landmarks.forEach { landmark ->
                    val old = landmarksByTrack[landmark.trackId]
                    if (old == null ||
                        landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx
                    ) {
                        landmarksByTrack[landmark.trackId] = landmark
                    }
                }
            }

            // Refine the incremental camera centers and sparse points with the
            // pinned GLOMAP ONLY_POINTS formulation. The current PnP/triangulation
            // map supplies a stable initialization; only registered observations
            // with an existing 3D point enter the Ceres problem.
            var globalPositioningApplied = false
            var globalPositioningInitialCost = Double.NaN
            var globalPositioningFinalCost = Double.NaN
            var globalPositioningIterations = 0
            var globalPositioningStatus = "Skipped: insufficient registered tracks"

            val fx = cameraMatrix.get(0, 0)[0]
            val skew = cameraMatrix.get(0, 1)[0]
            val cx = cameraMatrix.get(0, 2)[0]
            val fy = cameraMatrix.get(1, 1)[0]
            val cy = cameraMatrix.get(1, 2)[0]
            val validPoseByFrame = poseByFrame.filterNotNull().filter { pose ->
                pose.status != CameraPoseStatus.HELD_LAST_VALID &&
                    pose.status != CameraPoseStatus.UNREGISTERED
            }.associateBy { it.frameIndex }

            fun cameraCenter(pose: CameraPose): DoubleArray {
                val r = pose.rotation
                val t = pose.translation
                return doubleArrayOf(
                    -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]),
                    -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]),
                    -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2])
                )
            }

            val initialCameraCenters = validPoseByFrame.mapValues { (_, pose) ->
                cameraCenter(pose)
            }
            val initialPointsByTrack = landmarksByTrack.mapValues { (_, landmark) ->
                doubleArrayOf(landmark.position.x, landmark.position.y, landmark.position.z)
            }

            val globalTracks = tracks.mapNotNull { track ->
                val point = initialPointsByTrack[track.id] ?: return@mapNotNull null
                val observations = track.observations.mapNotNull { observation ->
                    val pose = validPoseByFrame[observation.frameIndex] ?: return@mapNotNull null
                    if (!fx.isFinite() || !fy.isFinite() ||
                        kotlin.math.abs(fx) < 1e-9 || kotlin.math.abs(fy) < 1e-9 ||
                        !observation.x.isFinite() || !observation.y.isFinite()
                    ) return@mapNotNull null

                    val normalizedY = (observation.y - cy) / fy
                    val normalizedX = (observation.x - cx - skew * normalizedY) / fx
                    GlomapGlobalPositioningProblemBuilder.CameraObservation(
                        frameIndex = observation.frameIndex,
                        rotation = pose.rotation,
                        cameraCenter = initialCameraCenters.getValue(observation.frameIndex),
                        undistortedFeatureDirection = doubleArrayOf(normalizedX, normalizedY, 1.0),
                        // The current focal length is estimated, not user-calibrated.
                        isCalibrated = false
                    )
                }
                if (observations.size < GlomapGlobalPositioningProblemBuilder.DEFAULT_MINIMUM_TRACK_VIEWS) {
                    null
                } else {
                    GlomapGlobalPositioningProblemBuilder.Track(
                        trackId = track.id,
                        observations = observations,
                        initializedPoint = point
                    )
                }
            }

            if (globalTracks.size >= 10) {
                val globalProblem = GlomapGlobalPositioningProblemBuilder.build(globalTracks)
                if (globalProblem.constraints.size >= 30) {
                    onProgress(
                        CameraTrackingProgress(
                            frameCount, frameCount, "Global positioning · Ceres",
                            overallFraction = 0.985f,
                            completedFrames = frameCount,
                            totalFrames = frameCount
                        )
                    )
                    try {
                        val solution = GlomapNativeSolver().solve(
                            problem = globalProblem,
                            frameCentersById = initialCameraCenters,
                            pointsByTrackId = initialPointsByTrack,
                            maxIterations = 100,
                            numThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                        )
                        if (solution == null) {
                            globalPositioningStatus = "Ceres did not return a usable solution"
                        } else {
                            globalPositioningInitialCost = solution.initialCost
                            globalPositioningFinalCost = solution.finalCost
                            globalPositioningIterations = solution.iterations
                            if (solution.initialCost.isFinite() && solution.finalCost.isFinite() &&
                                solution.finalCost < solution.initialCost
                            ) {
                                for ((frameId, center) in solution.frameCentersById) {
                                    val oldPose = poseByFrame.getOrNull(frameId) ?: continue
                                    val rotation = oldPose.rotation
                                    val translation = doubleArrayOf(
                                        -(rotation[0] * center[0] + rotation[1] * center[1] + rotation[2] * center[2]),
                                        -(rotation[3] * center[0] + rotation[4] * center[1] + rotation[5] * center[2]),
                                        -(rotation[6] * center[0] + rotation[7] * center[1] + rotation[8] * center[2])
                                    )
                                    poseByFrame[frameId] = oldPose.copy(translation = translation)
                                }
                                for ((trackId, point) in solution.pointsByTrackId) {
                                    val old = landmarksByTrack[trackId] ?: continue
                                    landmarksByTrack[trackId] = old.copy(
                                        position = Point3(point[0], point[1], point[2])
                                    )
                                }
                                globalPositioningApplied = true
                                globalPositioningStatus = "Applied"
                            } else {
                                globalPositioningStatus = "No cost improvement; kept incremental solution"
                            }
                        }
                    } catch (failure: LinkageError) {
                        globalPositioningStatus = "Native solver unavailable: ${failure.message ?: "library load failed"}"
                    } catch (failure: RuntimeException) {
                        globalPositioningStatus = "Ceres solve skipped: ${failure.message ?: "runtime error"}"
                    }
                } else {
                    globalPositioningStatus = "Skipped: fewer than 30 point-to-camera constraints"
                }
            } else {
                globalPositioningStatus = "Skipped: fewer than 10 tracks with 3 registered views"
            }

            onProgress(
                CameraTrackingProgress(
                    frameCount, frameCount, "Global positioning complete",
                    overallFraction = 0.99f,
                    completedFrames = frameCount,
                    totalFrames = frameCount
                )
            )

            val denseTimeline = ArrayList<CameraPose>(frameCount)
            var lastValid: CameraPose? = null
            for (index in 0 until frameCount) {
                val actual = poseByFrame[index]
                if (actual != null) {
                    denseTimeline += actual
                    lastValid = actual
                } else {
                    val beforeInitialization = index < selected.firstFrame
                    val held = lastValid ?: referencePose
                    denseTimeline += CameraPose(
                        index,
                        if (beforeInitialization) identity3() else held.rotation.copyOf(),
                        if (beforeInitialization) doubleArrayOf(0.0, 0.0, 0.0)
                        else held.translation.copyOf(),
                        if (beforeInitialization) CameraPoseStatus.UNREGISTERED
                        else CameraPoseStatus.HELD_LAST_VALID
                    )
                }
            }

            val allCorrespondences = max(1, totalCorrespondences)
            val finalStats = SfMTriangulator.triangulate(
                tracks, poseByFrame.filterNotNull(), cameraMatrix
            ).stats
            onProgress(CameraTrackingProgress(
                frameCount, frameCount, "Camera solve complete",
                overallFraction = 1f,
                completedFrames = frameCount,
                totalFrames = frameCount
            ))
            CameraTrackingResult(
                poses = denseTimeline,
                diagnostics = diagnostics,
                processedFrames = frameCount,
                solvedFramePairs = registeredFrames - 1,
                totalMatches = totalMatches,
                totalInliers = totalInliers,
                inlierRatio = (totalInliers.toDouble() / allCorrespondences).coerceIn(0.0, 1.0),
                isCameraMotionEstimated = registeredFrames >= 2,
                featureTracks = trackBuilder.build(trackIds = landmarksByTrack.keys),
                landmarks = landmarksByTrack.values.toList(),
                triangulationStats = finalStats,
                initialPairFrames = selected.firstFrame to selected.secondFrame,
                registeredCameraCount = registeredFrames,
                globalRotationsByFrame = viewGraphRotation?.rotationsByFrame.orEmpty(),
                viewGraphEdgeCount = viewGraphRotation?.inputEdgeCount ?: 0,
                viewGraphAcceptedEdgeCount = viewGraphRotation?.acceptedEdgeCount ?: 0,
                globalRotationIterations = viewGraphRotation?.iterations ?: 0,
                globalPositioningApplied = globalPositioningApplied,
                globalPositioningInitialCost = globalPositioningInitialCost,
                globalPositioningFinalCost = globalPositioningFinalCost,
                globalPositioningIterations = globalPositioningIterations,
                globalPositioningStatus = globalPositioningStatus
            )
        } finally {
            cache.values.forEach { it.descriptors.release() }
            matcher.clear()
            orb.clear()
        }
    }

    private fun estimatePairGeometry(
        first: CachedFrame,
        second: CachedFrame,
        cameraMatrix: Mat,
        matcher: BFMatcher
    ): PairGeometry? {
        val raw = ratioMatches(
            first.descriptors, second.descriptors,
            first.keypoints, second.keypoints, matcher
        )
        if (raw.size < 8) return null
        return verifyGeometry(raw, first.keypoints, second.keypoints, cameraMatrix, true)
    }

    private fun verifiedMatches(
        firstDescriptors: Mat,
        firstKeypoints: Array<KeyPoint>,
        secondDescriptors: Mat,
        secondKeypoints: Array<KeyPoint>,
        cameraMatrix: Mat,
        matcher: BFMatcher
    ): List<Pair<Int, Int>> {
        val raw = ratioMatches(
            firstDescriptors, secondDescriptors, firstKeypoints, secondKeypoints, matcher
        )
        if (raw.size < 8) return emptyList()
        return verifyTrackGeometry(raw, firstKeypoints, secondKeypoints)
    }

    /**
     * Verifies 2D feature continuity without requiring recoverPose/cheirality.
     *
     * recoverPose is appropriate for initializing a 3D camera pair, but it is
     * too restrictive for ordinary frame-to-frame tracking: small-baseline,
     * near-rotation and mostly-planar motion can still have valid 2D tracks.
     * Use robust epipolar and planar models here; PnP remains the gate for
     * accepting an actual 3D camera pose.
     */
    private fun verifyTrackGeometry(
        matches: List<Pair<Int, Int>>,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>
    ): List<Pair<Int, Int>> {
        if (matches.size < 8) return emptyList()
        val points1 = MatOfPoint2f()
        val points2 = MatOfPoint2f()
        val fundamentalMask = Mat()
        val homographyMask = Mat()
        var fundamental: Mat? = null
        var homography: Mat? = null
        try {
            points1.fromArray(*matches.map { firstKeypoints[it.first].pt }.toTypedArray())
            points2.fromArray(*matches.map { secondKeypoints[it.second].pt }.toTypedArray())

            fundamental = Calib3d.findFundamentalMat(
                points1, points2, Calib3d.FM_RANSAC, 1.5, 0.995, fundamentalMask
            )
            fun maskInlierCount(mask: Mat): Int {
                if (mask.empty() || mask.total().toInt() != matches.size) return 0
                return (0 until matches.size).count { index ->
                    val row = if (mask.rows() == matches.size) index else 0
                    val column = if (mask.rows() == matches.size) 0 else index
                    mask.get(row, column)?.firstOrNull()?.let { it != 0.0 } == true
                }
            }

            val fundamentalInliers = maskInlierCount(fundamentalMask)
            // Fundamental RANSAC is the normal fast path. Only pay for a second
            // homography RANSAC when epipolar verification is weak; this retains
            // planar/near-planar recovery without doubling the cost on most frames.
            val strongFundamentalModel = fundamentalInliers >=
                max(16, (matches.size * 0.45f).toInt())
            if (!strongFundamentalModel) {
                homography = Calib3d.findHomography(
                    points1, points2, Calib3d.RANSAC, 3.0, homographyMask
                )
            }
            val homographyInliers = maskInlierCount(homographyMask)
            val selectedMask = if (homographyInliers > fundamentalInliers) {
                homographyMask
            } else {
                fundamentalMask
            }
            if (max(fundamentalInliers, homographyInliers) < 8) return emptyList()

            return matches.filterIndexed { index, _ ->
                val row = if (selectedMask.rows() == matches.size) index else 0
                val column = if (selectedMask.rows() == matches.size) 0 else index
                selectedMask.get(row, column)?.firstOrNull()?.let { it != 0.0 } == true
            }
        } finally {
            points1.release()
            points2.release()
            fundamentalMask.release()
            homographyMask.release()
            fundamental?.release()
            homography?.release()
        }
    }

    private fun ratioMatches(
        firstDescriptors: Mat,
        secondDescriptors: Mat,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>,
        matcher: BFMatcher
    ): List<Pair<Int, Int>> {
        if (firstDescriptors.empty() || secondDescriptors.empty() ||
            firstDescriptors.rows() == 0 || secondDescriptors.rows() == 0
        ) return emptyList()
        val rows = mutableListOf<MatOfDMatch>()
        val result = mutableListOf<Pair<Int, Int>>()
        val usedTrain = HashSet<Int>()
        try {
            matcher.knnMatch(firstDescriptors, secondDescriptors, rows, 2)
            for (row in rows) {
                val pair = row.toArray()
                if (pair.size < 2) continue
                val best = pair[0]
                if (best.distance >= ratioThreshold * pair[1].distance) continue
                if (best.queryIdx !in firstKeypoints.indices ||
                    best.trainIdx !in secondKeypoints.indices ||
                    !usedTrain.add(best.trainIdx)
                ) continue
                result += best.queryIdx to best.trainIdx
            }
        } finally {
            rows.forEach { it.release() }
        }
        return result
    }

    private fun verifyGeometry(
        matches: List<Pair<Int, Int>>,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>,
        cameraMatrix: Mat,
        includePose: Boolean
    ): PairGeometry? {
        val points1 = MatOfPoint2f()
        val points2 = MatOfPoint2f()
        val mask = Mat()
        val rotation = Mat()
        val translation = Mat()
        try {
            points1.fromArray(*matches.map { firstKeypoints[it.first].pt }.toTypedArray())
            points2.fromArray(*matches.map { secondKeypoints[it.second].pt }.toTypedArray())
            val essential = Calib3d.findEssentialMat(
                points1, points2, cameraMatrix, Calib3d.RANSAC,
                0.999, 1.25, 1200, mask
            )
            try {
                if (essential.empty() || essential.cols() != 3 ||
                    essential.rows() < 3 || essential.rows() % 3 != 0
                ) return null

                var bestCount = 0
                var bestMask: Mat? = null
                var bestR: Mat? = null
                var bestT: Mat? = null
                try {
                    for (index in 0 until essential.rows() / 3) {
                        val candidate = essential.rowRange(index * 3, index * 3 + 3).clone()
                        val candidateMask = mask.clone()
                        val r = Mat()
                        val t = Mat()
                        try {
                            val count = Calib3d.recoverPose(
                                candidate, points1, points2, cameraMatrix, r, t, candidateMask
                            )
                            if (count > bestCount) {
                                bestCount = count
                                bestMask?.release()
                                bestR?.release()
                                bestT?.release()
                                bestMask = candidateMask.clone()
                                bestR = r.clone()
                                bestT = t.clone()
                            }
                        } finally {
                            candidate.release()
                            candidateMask.release()
                            r.release()
                            t.release()
                        }
                    }
                    val chosenMask = bestMask ?: return null
                    val inlierPairs = matches.filterIndexed { index, _ ->
                        chosenMask.get(index, 0)?.firstOrNull()?.let { it != 0.0 } == true
                    }
                    if (inlierPairs.size < 8) return null
                    if (!includePose) {
                        return PairGeometry(inlierPairs, identity3(), doubleArrayOf(0.0, 0.0, 0.0), bestCount)
                    }
                    val chosenR = bestR ?: return null
                    val chosenT = bestT ?: return null
                    val rValues = DoubleArray(9)
                    val tValues = DoubleArray(3)
                    chosenR.get(0, 0, rValues)
                    chosenT.get(0, 0, tValues)
                    return PairGeometry(inlierPairs, rValues, tValues, bestCount)
                } finally {
                    bestMask?.release()
                    bestR?.release()
                    bestT?.release()
                }
            } finally {
                essential.release()
            }
        } finally {
            points1.release()
            points2.release()
            mask.release()
            rotation.release()
            translation.release()
        }
    }

    private fun toGray(source: Mat): Mat {
        val gray = Mat()
        when (source.channels()) {
            1 -> source.copyTo(gray)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> {
                gray.release()
                throw IllegalArgumentException("Unsupported image channel count: ${source.channels()}")
            }
        }
        return gray
    }

    private fun identity3() = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )
}
