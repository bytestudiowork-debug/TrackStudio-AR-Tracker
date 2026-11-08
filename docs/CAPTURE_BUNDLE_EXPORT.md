# Capture bundle export

The AR Tracker screen includes **Export latest completed capture**. It packages the newest session that has a completed replay report into one ZIP and opens Android's share sheet.

The ZIP contains:
- `.csv`: raw camera and IMU event stream.
- `.json`: camera, device, sensor and analysis-stream metadata.
- `.basalt.frames`: lossless timestamped GRAY8 analysis frames.
- `.replay.json`: structural and timing screening report.
- `README.txt`: bundle contents and limitations.

The exporter only selects a session with a completed replay report, validates the CSV and fully reads/verifies the frame archive before packaging. It does not delete or modify the original capture. The shared ZIP is created in app cache and exposed only through AndroidX FileProvider.

If the button reports that no completed capture exists, leave the app (which stops capture and finalizes the report), reopen it, and export the previous completed session. Do not share the ZIP publicly; it may contain camera footage and motion-sensor recordings.
