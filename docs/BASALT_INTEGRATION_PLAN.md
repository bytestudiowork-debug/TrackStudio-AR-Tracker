# Basalt integration gate and adapter design

## Decision

Basalt is the candidate primary VIO backend. It is not yet integrated. Camera2 + SensorManager remain the Android acquisition layer, and ARCore remains a possible optional backend for supported devices.

## Input contract

The Basalt adapter must receive:

1. Monochrome or explicitly converted camera frames with stable dimensions and a documented pixel format.
2. Camera frame timestamps in the same monotonic clock domain as IMU timestamps.
3. Raw gyroscope samples in rad/s.
4. Raw accelerometer samples in m/s².
5. Camera intrinsics and distortion model matching the exact analysis stream (including crop/resize).
6. Camera-to-IMU rotation and translation, plus sensor noise and random-walk parameters.

Do not feed the current low-pass gravity-subtracted acceleration or approximate per-frame delta position into Basalt. Basalt should receive raw sensor measurements and perform its own estimator-side preintegration.

## Build and licensing gate

Before importing upstream source, pin a reviewed Basalt commit and audit its complete dependency graph, Android/NDK compatibility, CMake requirements, ABI support and all bundled third-party licenses. The Basalt core license does not automatically determine the licenses of every dependency or binary shipped in the APK.

Build an isolated native library first for arm64-v8a. Keep the Android app's existing CMake setup intact until a reproducible Basalt build is demonstrated. Add armeabi-v7a only if upstream dependencies and runtime performance support it.

## Acceptance tests

- Deterministic offline replay of a recorded camera/IMU session.
- Timestamp monotonicity and clock-domain checks.
- Correct camera model after crop/resize.
- Stationary test: bounded orientation drift and no large false translation.
- Rotation-only test: visual and inertial rotation agree.
- Translation test: stable tracking with observable parallax.
- Sensor gap and camera frame drop handling.
- Tracking-loss and relocalization state transitions.
- CPU, memory and thermal measurements on entry, mid-range and flagship Android devices.

No 3D reconstruction milestone should depend on Basalt until offline replay and physical-device tracking tests pass.
