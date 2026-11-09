package com.trackstudio.artracker

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CameraCompatibilityScreen()
            }
        }
    }
}

/**
 * Temporary diagnostic screen. The only active device feature is a Camera2
 * preview stream; the production capture pipeline is intentionally not started.
 */
@Composable
private fun CameraCompatibilityScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("Waiting for camera permission") }
    val controller = remember {
        CameraPreviewDiagnosticController(context.applicationContext) { status = it }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        status = if (granted) "Permission granted — preparing preview" else "Camera permission denied"
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
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("TRACKSTUDIO / CAMERA DIAGNOSTIC", color = Color.White, fontSize = 18.sp)
            Text(
                "Stage 1: Camera2 preview only",
                color = Color(0xFF9AA7B5),
                fontSize = 12.sp
            )
            Text(
                "OpenCV, JNI, IMU, ImageReader processing and recording are disabled for this test.",
                color = Color(0xFF9AA7B5),
                fontSize = 12.sp
            )
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text("Grant camera permission")
                    }
                }
            }
            Text(status, color = Color(0xFF73D6A5), fontSize = 14.sp)
            Text(
                "Please keep this screen open for 30 seconds after the preview appears.",
                color = Color(0xFFB9C5D2),
                fontSize = 12.sp
            )
        }
    }
}
