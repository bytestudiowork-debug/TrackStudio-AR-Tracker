# Basalt upstream integration gate

## Upstream source pin

Basalt is included as an unmodified Git submodule:

- Repository: https://github.com/VladyslavUsenko/basalt
- Upstream branch: `master`
- Pinned commit: `0f3b2b52c807f70ff4e2973ce253c73329eea7bc`
- Upstream commit date: 2026-03-22
- Basalt's nested vcpkg submodule pin: `1e199d32ad53aab1defda61ce41c380302e3f95c`

The Git submodule entry is the complete source integration at this stage. Do not copy Basalt files into the app, edit Basalt source, carry local patches, or silently advance the pin. All TrackStudio-specific code belongs outside `third_party/basalt`. The source-integrity workflow checks the exact pin and a clean submodule worktree.

## Why the source is isolated

The Android app currently builds with CMake 3.22.1. The pinned Basalt upstream requires CMake 3.24 or newer and documents a vcpkg/Ninja source build. Its root CMake configuration resolves a substantial dependency graph, including Eigen, TBB, OpenCV, Boost, LZ4, fmt, CLI11, magic_enum, nlohmann-json, opengv, rosbag, Pangolin, basalt-headers and GTest. The project also vendors ROS message support and AprilTag code.

Basalt's upstream CMake defaults include `CXX_MARCH=native` and fully static executable linking on Unix. Those defaults must not be allowed to leak into an Android cross-build. The Android wrapper must pass an explicit Android architecture and appropriate shared-library/link options without changing upstream files.

The current upstream CMake project is not yet demonstrated to build as an Android arm64 library with this app's NDK. A desktop Basalt build is not proof of an Android build. Do not add Basalt to the app's existing CMake target or raise the app's global CMake version until a separate arm64 Android configure/build is reproducible.

## Current TrackStudio input readiness

Already available:
- Camera2 analysis frames with a packed luma crop and frame timestamps.
- A lossless `TSBF` grayscale frame archive for each capture. Records contain timestamp, dimensions, GRAY8 bytes compressed with DEFLATE and CRC32; the footer records written/dropped counts. The writer uses a bounded queue and never blocks Camera2 for disk I/O.
- Raw Android gyroscope values (rad/s) and accelerometer values (m/s²) with SensorEvent timestamps.
- Camera/IMU fusion is conservatively allowed only when Camera2 reports the REALTIME timestamp source.
- Camera metadata, stream crop/stride information and sensor timing diagnostics are written to session metadata.

Replay preflight now implemented:
- The strict TSBF/CSV preflight verifies archive integrity, frame count, exact frame timestamp alignment, stable frame dimensions, raw IMU numeric values, and the existing CSV timing screen before a native adapter may consume a session.

Still missing before a real Basalt run:
- The native replay adapter that converts GRAY8 frames to Basalt's uint16 image convention and submits timestamped raw IMU samples to Basalt's public queues is still not connected.
- A validated camera model transformed into the exact cropped/resized analysis-frame coordinate system.
- Measured camera-to-IMU spatial extrinsics.
- Gyroscope/accelerometer bias and noise-density/random-walk calibration.
- An offline dataset adapter that feeds raw image and IMU measurements to Basalt in timestamp order.

Do not pass TrackStudio's prototype gravity-subtracted acceleration or approximate per-frame delta-position values into Basalt. Basalt must receive raw calibrated measurements and perform its own estimator-side preintegration.

## Integration sequence

1. Keep the upstream submodule pristine and pinned; verify its nested dependency pins and license inventory.
2. Connect the TSBF frame reader and raw IMU CSV stream to an offline replay adapter outside the Basalt source tree. Validate timestamp order, camera model, sensor units and calibration before starting the estimator.
3. Build Basalt as an isolated Android arm64 native dependency with a separate CMake toolchain/wrapper. Keep the app's existing CMake 3.22.1 path and armeabi-v7a fallback independent until supported.
4. Connect the adapter to Basalt's public API without editing Basalt source. Add deterministic dataset tests and verify the exact upstream commit in CI.
5. Only then enable live Camera2/SensorManager streaming into Basalt and benchmark on physical entry-, mid- and flagship devices.

## License gate

Basalt's top-level source is BSD-3-Clause. That does not automatically license every dependency or bundled component. Audit the exact vcpkg baseline, all resolved package licenses, vendored ROS/AprilTag code and any binary redistribution obligations before shipping an APK containing Basalt or its dependencies. Preserve upstream copyright and license notices.

## Current status

- Upstream source: pinned, unmodified submodule.
- App build: remains independent and unchanged by the Basalt source pin.
- Timestamped camera frame archive: implemented; strict CSV/archive preflight and deterministic tests have been added. The new preflight tests have not yet been run in CI.
- Android Basalt arm64 shared library: successfully built and packaged in workflow run 42461094919 (commit 60587f9); artifact includes the runtime dependency closure.
- JNI/runtime VIO: not connected yet; the app still uses its existing feature-tracking diagnostics, not Basalt.
- Build cache: vcpkg archives, installed packages and the CMake/Ninja build tree are cached. The first cache was saved successfully; a subsequent exact-key restore has not yet been demonstrated.
- Physical-device VIO accuracy: not tested.

This separation is deliberate: it prevents an unverified desktop dependency graph from breaking the working Android release while keeping the exact upstream implementation available for integration.


## Android arm64 build wrapper

The isolated wrapper is in `third_party/basalt-android`; it is outside the upstream submodule. It uses the pinned upstream vcpkg baseline and the upstream overlay ports, but omits the optional RealSense device package because the Android capture source is Camera2. The wrapper sets an explicit `armv8-a` target and disables upstream's Unix fully-static executable-link option. It supplies a tiny Pangolin package-config shim because Pangolin is used by Basalt's desktop tools, not by the `basalt` shared-library target; those desktop executables are configured but never built in this Android spike. This avoids cross-building a desktop GUI stack. It does not edit or patch Basalt source.

The `Basalt Android arm64 build spike` workflow recursively checks out the pinned Basalt and vcpkg submodules, installs the Android NDK 27.0.12077973, resolves the wrapper's dependency manifest, and builds only the upstream `basalt` shared-library target. The successful run verifies the ELF architecture, resolves the shared-library dependency closure, and uploads the isolated Basalt runtime bundle. This is an isolated build artifact, not yet packaged into the app. The workflow caches vcpkg archives, the installed target packages and the CMake/Ninja build directory; it remains manual-only to avoid accidental long builds.

## Upstream runtime API boundary

The pinned upstream live VIO example wires the public components as follows:

- `OpticalFlowFactory::getOpticalFlow(vio_config, calibration)` creates the upstream optical-flow frontend.
- `OpticalFlowBase::input_queue` receives `OpticalFlowInput` records containing `t_ns` and camera `img_data`.
- The frontend's `output_queue` is connected to `VioEstimatorBase::vision_data_queue`.
- `VioEstimatorFactory::getVioEstimator(...)` creates the estimator; raw measurements enter `imu_data_queue` as `ImuData<double>` with timestamp, acceleration and gyro.
- Estimated states are emitted through `out_state_queue`.

Basalt's `ImageData` uses `ManagedImage<uint16_t>`. Its EuRoC loader expands an 8-bit grayscale pixel to the high byte (`uint16 = gray8 << 8`). The future TrackStudio adapter must preserve this upstream intensity convention when converting the TSBF GRAY8 archive; it must not modify Basalt's optical-flow implementation.

## Status of this milestone

- Upstream source and nested vcpkg source are pinned and verified clean.
- TrackStudio records timestamped grayscale frames and raw IMU events.
- Archive reader/writer and integrity tests are implemented.
- Android arm64 Basalt library build completed successfully in run 42461094919.
- Replay preflight now cross-checks the CSV and TSBF archive, including frame timestamps and dimensions; it does not run Basalt.
- JNI queues, calibrated camera model/extrinsics, live VIO output and device benchmarks remain future gates.
