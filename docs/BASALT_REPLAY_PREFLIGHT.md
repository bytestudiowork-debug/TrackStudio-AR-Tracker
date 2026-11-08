# Basalt replay preflight

BasaltReplayPreflight is a TrackStudio-owned validation gate. It does not modify the pinned Basalt submodule and does not start the VIO estimator.

## Checks performed

1. Runs the existing streaming CSV integrity and timing analyzer.
2. Reads the complete TSBF archive, including its footer, frame checksums and monotonic timestamps.
3. Cross-checks every archived frame against the corresponding CSV FRAME row:
   - exact timestamp equality;
   - exact width and height equality;
   - stable image dimensions throughout the archive.
4. Rejects dropped archive frames and a CSV/archive frame-count mismatch.
5. Requires explicit calibration gates for:
   - camera intrinsics/distortion transformed and validated for the exact analysis stream;
   - camera-to-IMU rotation and translation;
   - gyroscope bias, noise density and random walk;
   - accelerometer bias, noise density and random walk.

The CSV validator also rejects non-finite or missing raw IMU vector components and non-positive frame dimensions.

## Important limitations

- readyForNativeVio means only that the recorded dataset passed these structural, timing and caller-supplied calibration gates. It is not proof of VIO accuracy.
- The current capture metadata does not establish all calibration gates. A caller must not mark them complete merely because Camera2 exposes lens metadata.
- The native adapter is still outstanding. It must convert GRAY8 to Basalt's expected 16-bit intensity convention (gray8 shifted left by 8), submit raw calibrated IMU measurements in timestamp order, initialize Basalt from a valid single-camera calibration, and drain pose output safely.
- No pose, scale, anchor or tracking-quality result is produced by this preflight.

## Tests

BasaltReplayPreflightTest covers a matching archive with complete calibration gates, rejection when extrinsics are missing, and rejection when archive timestamps differ from the CSV. Run the normal Android unit-test task before treating these tests as verified.
