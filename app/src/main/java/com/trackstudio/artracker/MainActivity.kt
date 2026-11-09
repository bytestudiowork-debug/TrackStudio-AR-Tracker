package com.trackstudio.artracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, PROJECTS, DETAIL, CAMERA, CAMERA_SETTINGS, APP_SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrackStudioArApp() }
    }
}

@Composable
private fun TrackStudioArApp() {
    val context = LocalContext.current
    val repository = remember { TrackStudioProjectRepository(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("trackstudio_ar_settings", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var projects by remember { mutableStateOf(repository.listProjects()) }
    var selected by remember { mutableStateOf<TrackStudioProject?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraStatus by remember { mutableStateOf("Camera is ready") }
    val cameraController = remember {
        CameraPreviewDiagnosticController(context.applicationContext) { cameraStatus = it }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        cameraStatus = if (granted) "Permission granted — preparing camera" else "Camera permission is required"
    }

    BackHandler(screen != Screen.HOME) {
        screen = when (screen) {
            Screen.CAMERA -> Screen.DETAIL
            Screen.DETAIL -> Screen.PROJECTS
            else -> Screen.HOME
        }
    }

    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF64D8C5), secondary = Color(0xFF9CB9FF),
        background = Color(0xFF101418), surface = Color(0xFF171E24),
        surfaceVariant = Color(0xFF253039)
    ) else lightColorScheme(
        primary = Color(0xFF087E75), secondary = Color(0xFF4569A8),
        background = Color(0xFFF5F7F8), surface = Color.White,
        surfaceVariant = Color(0xFFE9EFF0)
    )

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    { projectName = ""; createDialog = true },
                    { projects = repository.listProjects(); screen = Screen.PROJECTS },
                    { screen = Screen.CAMERA_SETTINGS },
                    { screen = Screen.APP_SETTINGS }
                )
                Screen.PROJECTS -> ProjectsScreen(
                    projects, { screen = Screen.HOME },
                    { projects = repository.listProjects() },
                    { selected = it; screen = Screen.DETAIL }
                )
                Screen.DETAIL -> {
                    val p = selected
                    if (p == null) screen = Screen.PROJECTS
                    else ProjectDetailScreen(p) { screen = Screen.PROJECTS }
                }
                Screen.CAMERA -> {
                    val p = selected
                    if (p == null) screen = Screen.HOME
                    else CameraScreen(p, hasPermission, cameraStatus, cameraController,
                        { screen = Screen.DETAIL },
                        { permissionLauncher.launch(Manifest.permission.CAMERA) })
                }
                Screen.CAMERA_SETTINGS -> CameraSettingsScreen { screen = Screen.HOME }
                Screen.APP_SETTINGS -> AppSettingsScreen(themeMode, {
                    themeMode = it
                    prefs.edit().putString("theme_mode", it).apply()
                }) { screen = Screen.HOME }
            }
        }
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("New project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name this shoot. Its recordings and tracking data will stay organised in this project.")
                    OutlinedTextField(
                        value = projectName, onValueChange = { projectName = it },
                        label = { Text("Project name") }, placeholder = { Text("e.g. Car Tracking Test") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(enabled = projectName.isNotBlank(), onClick = {
                    selected = repository.createProject(projectName)
                    projects = repository.listProjects()
                    createDialog = false
                    screen = Screen.CAMERA
                    if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text("Create and open camera") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HomeScreen(
    openCamera: () -> Unit, openProjects: () -> Unit,
    openCameraSettings: () -> Unit, openAppSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TrackStudio AR", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("LIVE CAMERA TRACKING", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.5.sp)
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("AR", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Card(
            Modifier.fillMaxWidth().clickable(onClick = openCamera),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("◉", color = Color.White, fontSize = 30.sp)
                Text("Open Camera", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Create a project and start a live tracking session.", color = Color.White.copy(alpha = .88f), fontSize = 14.sp)
                Text("START SHOOT  →", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Text("WORKSPACE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("Projects", "Your shoots and tracking data", "▣", Modifier.weight(1f), openProjects)
            HomeTile("Camera settings", "Device camera capabilities", "◎", Modifier.weight(1f), openCameraSettings)
        }
        HomeTile("App settings", "Appearance and preferences", "⚙", Modifier.fillMaxWidth(), openAppSettings)
        Text("Track. Capture. Organise.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun HomeTile(title: String, description: String, symbol: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(symbol, color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, back: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", Modifier.clickable(onClick = back).padding(end = 16.dp), fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProjectsScreen(
    projects: List<TrackStudioProject>, back: () -> Unit,
    refresh: () -> Unit, openProject: (TrackStudioProject) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Projects", "Your live tracking shoots", back)
        if (projects.isEmpty()) {
            Text("No projects yet", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose Open Camera on the home screen to create your first project.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                projects.forEach { p ->
                    Card(Modifier.fillMaxWidth().clickable { openProject(p) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("▣", Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.recordingCount} recordings · ${dateText(p.createdAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("Refresh projects") }
    }
}

@Composable
private fun ProjectDetailScreen(project: TrackStudioProject, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Project", "Project workspace", back)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(project.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Created ${dateText(project.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Divider()
                Text("${project.recordingCount} recordings", fontWeight = FontWeight.SemiBold)
                Text("One project can contain multiple recordings. Each recording will have its own footage, tracking data and sensor files.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Text("RECORDINGS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text("No recordings yet", fontWeight = FontWeight.Medium)
        Text("Saved recordings will appear here inside this project.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = back, Modifier.fillMaxWidth()) { Text("Back to projects") }
    }
}

@Composable
private fun CameraScreen(
    project: TrackStudioProject, hasPermission: Boolean, status: String,
    controller: CameraPreviewDiagnosticController, back: () -> Unit, requestPermission: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, hasPermission, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasPermission) controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (hasPermission && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.start()
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.stop() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { viewContext -> TextureView(viewContext).also(controller::attach) },
                modifier = Modifier.fillMaxSize(),
                update = { textureView -> controller.attach(textureView) }
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Camera permission needed", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Allow camera access to open the viewfinder.", color = Color.LightGray)
                Button(onClick = requestPermission) { Text("Allow camera") }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = back),
                shape = CircleShape,
                color = Color.Black.copy(alpha = .42f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("‹", color = Color.White, fontSize = 34.sp)
                }
            }
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = controller::focus),
                shape = CircleShape,
                color = Color.Black.copy(alpha = .42f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    FocusReticleIcon()
                }
            }
        }
    }
}

private data class CameraCapabilities(
    val sizes: List<String>,
    val fps: List<String>,
    val highSpeedModes: List<String>
)

private fun cameraCapabilities(context: Context): CameraCapabilities = try {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()
    if (id == null) CameraCapabilities(emptyList(), emptyList(), emptyList()) else {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?.map { "${it.width} × ${it.height}" }?.distinct() ?: emptyList()
        val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { "${it.lower}–${it.upper} FPS" }?.distinct()
            ?.sortedByDescending { it.substringAfter("–").substringBefore(" FPS").toIntOrNull() ?: 0 }
            ?: emptyList()
        val highSpeedModes = map?.highSpeedVideoSizes?.flatMap { size ->
            map.getHighSpeedVideoFpsRangesFor(size)?.map { range ->
                "${size.width} × ${size.height} · ${range.lower}–${range.upper} FPS"
            } ?: emptyList()
        }?.distinct()?.sorted() ?: emptyList()
        CameraCapabilities(sizes, fps, highSpeedModes)
    }
} catch (_: Exception) { CameraCapabilities(emptyList(), emptyList(), emptyList()) }

@Composable
private fun FocusReticleIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(27.dp)) {
        val stroke = 2.dp.toPx()
        val arm = size.minDimension * .27f
        val inset = size.minDimension * .18f
        val right = size.width - inset
        val bottom = size.height - inset
        val color = Color.White
        drawLine(color, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(inset + arm, inset), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(inset, inset + arm), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(right, inset), androidx.compose.ui.geometry.Offset(right - arm, inset), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(right, inset), androidx.compose.ui.geometry.Offset(right, inset + arm), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(inset, bottom), androidx.compose.ui.geometry.Offset(inset + arm, bottom), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(inset, bottom), androidx.compose.ui.geometry.Offset(inset, bottom - arm), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right - arm, bottom), stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right, bottom - arm), stroke)
    }
}

@Composable
private fun CameraSettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val capabilities = remember { cameraCapabilities(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        PageHeader("Camera settings", "Automatically detected device capabilities", back)
        val maximumFps = capabilities.fps.maxOfOrNull {
            it.substringAfter("–").substringBefore(" FPS").toIntOrNull() ?: 0
        } ?: 0
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DEVICE DETECTION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (capabilities.sizes.isEmpty()) "No recorder output sizes reported"
                    else "${capabilities.sizes.size} recording resolutions detected",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (maximumFps > 0) "Highest reported camera FPS range: up to $maximumFps FPS"
                    else "Camera FPS ranges are not reported",
                    fontSize = 13.sp
                )
            }
        }
        Text("SUPPORTED RECORDING RESOLUTIONS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        if (capabilities.sizes.isEmpty()) Text("Recording sizes are not reported by Camera2.")
        else capabilities.sizes.forEach { CapabilityRow(it) }
        Text("CAMERA FRAME-RATE RANGES", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        if (capabilities.fps.isEmpty()) Text("Frame-rate ranges are not reported.")
        else capabilities.fps.forEach { CapabilityRow(it) }
        if (capabilities.highSpeedModes.isNotEmpty()) {
            Text("HIGH-SPEED VIDEO MODES", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
            capabilities.highSpeedModes.forEach { CapabilityRow(it) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text("These are capabilities reported by Android for the selected camera. A resolution and FPS range shown separately are not proof that every combination works together. The recording engine must validate the selected combination when recording is implemented.",
                Modifier.padding(14.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CapabilityRow(value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun AppSettingsScreen(themeMode: String, setTheme: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("App settings", "Personalise TrackStudio AR", back)
        Text("APPEARANCE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text("Choose your preferred theme.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEach { (value, label) ->
                if (themeMode == value) Button(onClick = { setTheme(value) }, Modifier.weight(1f)) { Text(label) }
                else OutlinedButton(onClick = { setTheme(value) }, Modifier.weight(1f)) { Text(label) }
            }
        }
        Divider()
        Text("PERFORMANCE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text("Camera analysis will use bounded frame queues and background workers to keep the viewfinder responsive.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private fun dateText(time: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(time))
