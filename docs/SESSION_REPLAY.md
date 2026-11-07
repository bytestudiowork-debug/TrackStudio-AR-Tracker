# Session replay and deterministic diagnostics

Each capture stores a CSV event stream and a JSON metadata sidecar under the app-private `files/sessions` directory. When capture stops, the logger drains queued CSV writes, closes the CSV, and then creates a third file named `capture-<timestamp>.replay.json`.

## Streaming analysis

Replay analysis and CSV validation share a single incremental pass over the event stream. The analyzer does not call `toList()`, retain CSV rows, or reopen the input sequence. It keeps only per-stream counters, first/last timestamps, maximum gaps, and a small set of observed camera clock labels. Memory use is therefore bounded by the number of streams rather than the recording duration.

The report JSON is intended for later tooling and Basalt ingestion preflight. It includes:
- Camera, gyroscope, and accelerometer sample counts, time ranges, estimated rates, and maximum gaps.
- Camera timestamp-source consistency.
- CSV schema and integrity counters, including malformed, invalid, out-of-order, and unsupported rows.
- Replay-readiness result and human-readable warnings.

The CSV header is checked against the exact 27-field schema emitted by the capture controller. Rows must have exactly 27 fields, known record types, positive nanosecond timestamps, and strictly increasing timestamps independently for camera frames, each supported IMU sensor type, gyro deltas, and acceleration deltas. Unknown record types and unsupported IMU sensor IDs are rejected.

## Replay screening rules

A replay is marked **ready for a future VIO ingestion stage** only when:
- CSV integrity checks pass.
- At least two camera frames and two samples from each required IMU stream exist.
- Camera frames consistently report REALTIME timestamps.
- Camera gaps do not exceed 100 ms and IMU gaps do not exceed 50 ms.

These thresholds are conservative dataset screening rules, not universal sensor limits. A failed readiness check does not discard the capture; it identifies why the data should be reviewed before VIO ingestion.

## Important distinction

Replay readiness is not a calibration result and is not proof of metric accuracy. The current app still does not estimate camera-to-IMU spatial extrinsics, IMU bias/noise calibration, gravity-compensated world motion, or camera pose. The current accelerometer integration remains diagnostic only. Basalt is not integrated by this milestone.


## Camera frame archive

Each capture also contains a `.basalt.frames` TSBF v1 archive with lossless timestamped GRAY8 frames. The archive uses a bounded asynchronous writer, per-frame CRC32, monotonic timestamp checks and a footer with written/dropped counts. `BasaltFrameArchiveReader` validates the container and streams frames without loading the complete recording into memory. This is not yet a Basalt runtime adapter; it provides the missing image side of a future offline replay dataset.
