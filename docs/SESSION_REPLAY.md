# Session replay and deterministic diagnostics

Each capture stores a CSV event stream and a JSON sidecar under the app-private `files/sessions` directory. The replay analyzer reads the CSV as an immutable event log and derives repeatable stream summaries.

## Integrity contract

The CSV header is checked against the exact 27-field schema emitted by the capture controller. Rows must have exactly 27 fields, known record types, positive nanosecond timestamps, and strictly increasing timestamps independently for camera frames, each IMU sensor type, gyro deltas, and acceleration deltas. Unknown record types are rejected instead of silently ignored.

## Replay summaries

The analyzer reports sample counts, first/last timestamps, stream duration, estimated sample rate and maximum inter-sample gap for camera frames, gyroscope, and accelerometer. It also checks that camera frames consistently report a single timestamp source.

A replay is marked **ready for a future VIO ingestion stage** only when:
- CSV integrity checks pass.
- At least two camera frames and two samples from each required IMU stream exist.
- Camera timestamps are explicitly marked REALTIME.
- Camera gaps do not exceed 100 ms and IMU gaps do not exceed 50 ms.

These thresholds are conservative dataset screening rules, not universal sensor limits. A failed readiness check does not discard the capture; it identifies why the data should not be fed to a VIO backend without review.

## Important distinction

Replay readiness is not a calibration result and is not proof of metric accuracy. The current app still does not estimate camera-to-IMU spatial extrinsics, IMU bias/noise calibration, gravity-compensated world motion, or camera pose. The current accelerometer integration remains diagnostic only. Basalt is not integrated by this milestone.
