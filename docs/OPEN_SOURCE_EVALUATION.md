# Open-source tracking stack evaluation

Status: candidates reviewed; no third-party SLAM engine is integrated yet.

## Current prototype
The app now has an in-repository C++ FAST-style corner detector and local patch-search tracker fed by Camera2 Y-plane frames. This is a lightweight engineering prototype for validating frame-to-frame visual tracking, not full optical flow, VIO, SLAM, or reconstruction. It has no ARCore dependency.

## Candidates under evaluation

| Project | Intended role | License / caveat | Decision |
|---|---|---|---|
| OpenCV 4.x | Feature detection, pyramidal Lucas-Kanade optical flow, calibration and image processing | Apache-2.0 for 4.5.0+; official Android AAR available from Maven Central | Preferred vision toolkit candidate; benchmark native Android integration and footprint |
| ORB-SLAM3 | Full visual / visual-inertial SLAM reference | GPLv3; upstream describes a separate commercial licensing route | Research benchmark only unless commercial license/legal review resolves copyleft |
| VINS-Fusion | Visual-inertial odometry reference | GPLv3 | Research reference only pending licensing review |
| Basalt | VIO and mapping algorithms | BSD-3-Clause core; audit third-party dependency licenses; desktop-focused build instructions | Strong algorithm reference, but Android porting cost is unknown |
| Kimera-VIO | VIO and optional mesh pipeline | BSD-2-Clause core; substantial dependency stack and third-party license review required | Secondary research candidate |

## Provisional product direction
1. Keep Camera2 and SensorManager as capture backends.
2. Prefer OpenCV for reusable vision primitives if on-device benchmarks confirm acceptable CPU, memory, thermal, and APK costs.
3. Implement or adapt a commercially compatible VIO state-estimation layer after timestamp synchronization and camera/IMU calibration are validated.
4. Select a complete SLAM engine only after measuring drift, tracking loss, relocalization, low-texture/blur performance, thermal throttling, and device coverage.
5. Treat ORB-SLAM3 and VINS-Fusion as research references unless licensing is explicitly approved.

## Validation gates
- Correct Camera2/IMU monotonic timestamp alignment and camera-to-IMU calibration.
- Repeatable datasets, preferably with ground truth.
- Tracking under blur, low texture, exposure shifts, and fast movement.
- Rotation/translation drift and relocalization.
- Sustained performance on entry-, mid-, and flagship Android devices.
- Full license and third-party dependency audit before commercial release.
