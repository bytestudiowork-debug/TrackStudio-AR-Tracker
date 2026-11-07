# Camera–IMU capture and calibration metadata

Each capture writes two files under the app's private `files/sessions` directory:

- `capture-<session>.csv`: timestamped frame and raw sensor telemetry.
- `capture-<session>.json`: device, camera calibration metadata and sensor stream diagnostics.

## Camera metadata

When Camera2 exposes them, the JSON sidecar records:

- Sensor orientation, pixel array, active array and physical sensor size.
- Available focal lengths.
- Intrinsic calibration values (fx, fy, cx, cy, skew).
- Lens distortion coefficients.
- Android lens pose rotation and translation.
- Hardware capability level and camera timestamp source.

Missing values are recorded as unavailable; the app does not fabricate intrinsics or distortion coefficients. Android lens-pose metadata is retained as a device-provided camera pose prior. It is not labelled as a measured camera-to-IMU calibration.

## Sensor diagnostics

Gyroscope and accelerometer streams are monitored independently. The sidecar records accepted, invalid and out-of-order samples, mean and maximum sample intervals, estimated sampling rate and gaps longer than 50 ms. The diagnostics measure the observed stream; they do not prove the sensor's physical accuracy or calibrate bias.

## Timestamp policy

Camera frames are fused with Android sensor events only when Camera2 reports `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`. With `UNKNOWN`, frame and sensor timestamps are still recorded, but the current prototype does not integrate them together. This conservative policy avoids silently combining timestamps whose clock domains are not guaranteed to match.

## Calibration limitations

This capture stage does not yet estimate:

- Gyroscope or accelerometer bias and scale factors.
- Camera-to-IMU spatial extrinsics from a synchronized calibration sequence.
- Rotation-compensated world gravity.
- IMU noise density, random walk or preintegration covariance.

The current low-pass gravity and per-frame accelerometer deltas are diagnostic prototypes only. They must not be treated as world-space position or supplied as calibrated inertial measurements.

## Basalt integration gate

Before feeding sessions into Basalt, the integration must establish a consistent camera model, image dimensions/crop, camera-to-IMU transform, sensor units, timestamp domain and sensor noise parameters. The next native adapter should consume raw calibrated gyro and accelerometer samples plus camera frames; it should not consume the prototype gravity-subtracted acceleration as if it were a calibrated measurement.


## Camera-frame diagnostics and stream layout

The session sidecar also stores the observed ImageReader frame rate, frame interval distribution, timestamp errors and long gaps. The first accepted analysis frame records the actual image dimensions, crop rectangle, Y-plane row stride and pixel stride. The luma copy respects these values and feeds a tightly packed crop to optical flow. Intrinsics are not silently rescaled: the exact analysis crop and source metadata are retained so a later calibrated adapter can apply a documented coordinate transform.

The capture pipeline rejects invalid or non-monotonic camera timestamps before optical flow or inertial interval integration. A passing frame timestamp monitor still does not prove that camera and IMU clocks share a domain; fusion remains gated by the Camera2 timestamp-source metadata.

See [SESSION_VALIDATION.md](SESSION_VALIDATION.md) for the CSV structural validator and its limitations.
