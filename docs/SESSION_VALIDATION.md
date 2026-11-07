# Capture session validation

A capture writes a CSV telemetry stream and JSON metadata sidecar under the app-private `files/sessions` directory.

## Camera frame timing
The frame monitor accepts only positive, strictly increasing ImageReader timestamps. The sidecar records accepted frames, invalid and out-of-order timestamps, mean and maximum frame intervals, estimated rate and gaps longer than 100 ms. Rejected timestamps are not passed to optical flow or IMU interval integration.

## Image plane handling
The luma packer copies the Image crop rectangle and honors Y-plane row and pixel strides. Output dimensions match the crop rectangle and pixels are tightly packed. The sidecar records first-observed analysis dimensions, crop, image format and plane strides. Camera intrinsics remain in their Camera2-provided coordinate convention; this stage does not guess a crop/resize transform.

## CSV structural validation
`SessionCsvValidator` checks the 27-column header and row width, positive timestamps, and monotonic ordering independently for camera frames, each sensor type, gyro deltas and acceleration deltas. It reports row and error counts. This is a structural integrity check, not proof of calibration, metric motion or VIO accuracy.

## Limitations
- Camera-to-IMU spatial extrinsics are not calibrated.
- Intrinsics may be absent on devices that do not expose Camera2 calibration metadata.
- Image rotation/orientation and preview display transform are not yet applied to analysis pixels.
- Prototype accelerometer integration is not a world-space position estimate.
- Passing structural validation does not make a session a calibrated Basalt dataset.
