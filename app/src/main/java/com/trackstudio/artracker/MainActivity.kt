package com.trackstudio.artracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaptureScreen()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CaptureScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var stats by remember { mutableStateOf(CaptureStats()) }
    val basaltNativeStatus = remember { BasaltNativeBridge.status() }
    val controller = remember {
        CameraImuCaptureController(context.applicationContext) { stats = it }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) stats = stats.copy(status = "Camera permission denied")
    }

    DisposableEffect(lifecycleOwner, hasCameraPermission, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasCameraPermission) controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            hasCameraPermission
        ) {
            controller.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101318)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "TRACKSTUDIO  /  AR TRACKER",
                color = Color.White,
                fontSize = 18.sp
            )
            Text(
                text = "VISUAL + INERTIAL TIMING TEST",
                color = Color(0xFF9AA7B5),
                fontSize = 11.sp
            )
            Text(
                text = "Basalt native: $basaltNativeStatus",
                color = Color(0xFF9AA7B5),
                fontSize = 10.sp
            )
            Spacer(Modifier.height(12.dp))

            if (hasCameraPermission) {
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    factory = { viewContext ->
                        TextureView(viewContext).also(controller::attach)
                    },
                    update = controller::attach
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera access is required for tracking.", color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }) {
                            Text("Grant camera access")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stats.status, color = Color(0xFF73D6A5), fontSize = 13.sp)
            Text("Camera frames: ${stats.frameCount}  |  ${stats.cameraRateHz} Hz", color = Color.White)
            Text("Camera max frame gap: ${stats.cameraMaxGapMs} ms  |  timestamp errors: ${stats.cameraTimestampErrors}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Analysis stream: ${stats.analysisStreamSummary}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Features detected: ${stats.featureCount}  |  Tracked: ${stats.trackedFeatureCount}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Mean image motion: ${stats.meanMotionX}, ${stats.meanMotionY} px/frame", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text(
                "Gyroscope (rad/s): ${stats.gyroX}, ${stats.gyroY}, ${stats.gyroZ}",
                color = Color(0xFFB9C5D2),
                fontSize = 12.sp
            )
            Text(
                "Accelerometer (m/s²): ${stats.accelX}, ${stats.accelY}, ${stats.accelZ}",
                color = Color(0xFFB9C5D2),
                fontSize = 12.sp
            )
            Text(
                "Gyro delta / frame (rad): ${stats.gyroDeltaX}, ${stats.gyroDeltaY}, ${stats.gyroDeltaZ}",
                color = Color(0xFFB9C5D2),
                fontSize = 12.sp
            )
            Text("Gravity estimate (m/s², low-pass): ${stats.gravityEstimate}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Observed IMU rate (Hz): gyro ${stats.gyroRateHz}  |  accel ${stats.accelRateHz}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Largest sensor interval (ms): gyro ${stats.gyroMaxGapMs}  |  accel ${stats.accelMaxGapMs}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Camera calibration: ${stats.cameraCalibrationStatus}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Accel Δ velocity (m/s): ${stats.accelDeltaVelocity}  |  valid: ${stats.accelDeltaValid}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text("Accel Δ position (m, approximate): ${stats.accelDeltaPosition}", color = Color(0xFFB9C5D2), fontSize = 12.sp)
            Text(
                "IMU interval: ${stats.imuIntervalMs} ms  |  samples: ${stats.imuSampleCount}  |  valid: ${stats.imuDeltaValid}",
                color = Color(0xFFB9C5D2),
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Camera clock: ${stats.cameraTimestampSource}", color = Color(0xFF778596), fontSize = 11.sp)
                Text("API 26+", color = Color(0xFF778596), fontSize = 11.sp)
            }
        }
    }
}
