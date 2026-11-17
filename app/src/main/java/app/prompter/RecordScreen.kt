package app.prompter

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class RecState {
    var recording: Recording? = null
    var videoCapture: VideoCapture<Recorder>? = null
    var camera: Camera? = null
}

private fun qualityOf(name: String): Quality = when (name) {
    "UHD" -> Quality.UHD
    "HD" -> Quality.HD
    "SD" -> Quality.SD
    else -> Quality.FHD
}

private fun qualityName(q: Quality): String = when (q) {
    Quality.UHD -> "UHD"
    Quality.HD -> "HD"
    Quality.SD -> "SD"
    else -> "FHD"
}

private fun qualityLabel(q: Quality): String = when (q) {
    Quality.UHD -> "4K"
    Quality.FHD -> "1080p"
    Quality.HD -> "720p"
    else -> "480p"
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color(0x66FFFFFF),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Color(0x66000000))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Split screen: prompter text on top (next to the front lens), live camera preview below, record button. */
@Composable
fun RecordScreen(onClose: () -> Unit, onGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val state = remember { RecState() }

    var hasPerms by remember {
        mutableStateOf(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerms = it.values.all { granted -> granted }
    }
    DisposableEffect(Unit) {
        if (!hasPerms) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        onDispose { }
    }

    var front by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }
    var qualities by remember { mutableStateOf<List<Quality>>(emptyList()) }
    var stabSupported by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var expSupported by remember { mutableStateOf(false) }
    var expLower by remember { mutableIntStateOf(0) }
    var expUpper by remember { mutableIntStateOf(0) }
    var expIndex by remember { mutableIntStateOf(0) }
    var focusPos by remember { mutableStateOf<Offset?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(hasPerms, front, PrompterStore.quality, PrompterStore.stabilization) {
        val future = ProcessCameraProvider.getInstance(context)
        if (hasPerms) {
            future.addListener({
                try {
                    val provider = future.get()
                    val selector =
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    // Probe the camera first to learn which qualities / stabilization it supports.
                    provider.unbindAll()
                    val probe = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    val caps = Recorder.getVideoCapabilities(probe.cameraInfo)
                    val supported = caps.getSupportedQualities(DynamicRange.SDR)
                    qualities = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).filter { it in supported }
                    stabSupported = caps.isStabilizationSupported

                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                qualityOf(PrompterStore.quality),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            ),
                        )
                        .build()
                    val vc = VideoCapture.Builder(recorder)
                        .setVideoStabilizationEnabled(PrompterStore.stabilization && stabSupported)
                        .build()
                    provider.unbindAll()
                    val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
                    state.videoCapture = vc
                    state.camera = cam

                    hasFlash = cam.cameraInfo.hasFlashUnit()
                    torch = false
                    zoom = 1f
                    val es = cam.cameraInfo.exposureState
                    expSupported = es.isExposureCompensationSupported
                    expLower = es.exposureCompensationRange.lower
                    expUpper = es.exposureCompensationRange.upper
                    expIndex = 0
                    cameraError = null
                } catch (e: Exception) {
                    cameraError = "Kamera tidak tersedia: ${e.message}"
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            state.recording?.stop()
            state.recording = null
            state.camera = null
            if (future.isDone) runCatching { future.get().unbindAll() }
        }
    }

    fun startRecording() {
        val vc = state.videoCapture ?: return
        val name = "Prompter-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val limitMs = PrompterStore.limitSec * 1000L
        val pending = if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Prompter")
            }
            vc.output.prepareRecording(
                context,
                MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(cv)
                    .setDurationLimitMillis(limitMs)
                    .build(),
            )
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            vc.output.prepareRecording(
                context,
                FileOutputOptions.Builder(File(dir, "$name.mp4")).setDurationLimitMillis(limitMs).build(),
            )
        }
        elapsedMs = 0L
        state.recording = pending.withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> elapsedMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    playing = false
                    state.recording = null
                    val limitHit = ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    val msg = if (ev.hasError() && !limitHit) {
                        "Rekaman gagal (kode ${ev.error})"
                    } else {
                        "Tersimpan di Movies/Prompter"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
        isRecording = true
    }

    fun stopRecording() {
        playing = false
        state.recording?.stop()
    }

    fun applyZoom(factor: Float) {
        val cam = state.camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val r = (zs.zoomRatio * factor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        cam.cameraControl.setZoomRatio(r)
        zoom = r
    }

    fun focusAt(pos: Offset) {
        val cam = state.camera ?: return
        val x = if (front && !PrompterStore.mirrorPreview) previewView.width - pos.x else pos.x
        val point = previewView.meteringPointFactory.createPoint(x, pos.y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        focusPos = pos
        scope.launch {
            delay(900)
            focusPos = null
        }
    }

    // Immersive: hide bars and let content reach the camera cutout so the text sits right under the lens.
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (Build.VERSION.SDK_INT >= 28) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    val cutoutTop = with(LocalDensity.current) { WindowInsets.displayCutout.getTop(this).toDp() }

    BackHandler {
        if (isRecording) stopRecording()
        onClose()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Top: prompter, right below the camera cutout.
        Box(
            Modifier
                .height(cutoutTop + PrompterStore.panelDp.dp)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(top = cutoutTop),
        ) {
            PrompterText(
                scroll = scroll,
                playing = playing,
                onReachedEnd = { playing = false },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { playing = !playing },
                            onDoubleTap = {
                                playing = false
                                scope.launch { scroll.scrollTo(0) }
                            },
                        )
                    },
            )
        }

        // Bottom: camera preview with overlays and controls.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPerms) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = if (front && !PrompterStore.mirrorPreview) -1f else 1f },
                )
            } else {
                Text(
                    "Izin kamera & mikrofon dibutuhkan.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Grid, focus ring, and gestures (tap = focus, pinch = zoom).
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusAt(it) }) }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, z, _ -> if (z != 1f) applyZoom(z) }
                    },
            ) {
                if (PrompterStore.grid) {
                    val c = Color(0x88FFFFFF)
                    val w = 1.dp.toPx()
                    for (i in 1..2) {
                        drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
                        drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
                    }
                }
                focusPos?.let {
                    drawCircle(Color.White, 34.dp.toPx(), it, style = Stroke(2.dp.toPx()))
                }
            }

            cameraError?.let {
                Text(it, color = Color(0xFFFFB74D), modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }

            if (countdown > 0) {
                Text(
                    "$countdown",
                    color = Color.White,
                    fontSize = 96.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Top-left: recording timer / zoom level.
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isRecording) {
                    Text(
                        "● ${formatTime(elapsedMs)}" +
                            if (PrompterStore.limitSec > 0) " / ${formatTime(PrompterStore.limitSec * 1000L)}" else "",
                        color = Color(0xFFFF5252),
                        fontSize = 14.sp,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                if (zoom > 1.05f) {
                    Text(
                        "%.1fx".format(zoom),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            // Top-right: quick toggles.
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (hasFlash) {
                    Chip("Flash", selected = torch) {
                        torch = !torch
                        state.camera?.cameraControl?.enableTorch(torch)
                    }
                }
                Chip("Grid", selected = PrompterStore.grid) {
                    PrompterStore.grid = !PrompterStore.grid
                    PrompterStore.saveSettings()
                }
                if (front) {
                    Chip("Mirror", selected = PrompterStore.mirrorPreview) {
                        PrompterStore.mirrorPreview = !PrompterStore.mirrorPreview
                        PrompterStore.saveSettings()
                    }
                }
                Chip("Opsi", selected = showSettings) { showSettings = !showSettings }
                Chip("Galeri") {
                    if (isRecording) stopRecording()
                    onGallery()
                }
            }

            // Settings panel.
            if (showSettings) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 120.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC000000))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Kualitas video", color = Color.White, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        qualities.forEach { q ->
                            Chip(qualityLabel(q), selected = PrompterStore.quality == qualityName(q), enabled = !isRecording) {
                                PrompterStore.quality = qualityName(q)
                                PrompterStore.saveSettings()
                            }
                        }
                    }
                    Text("Hitung mundur", color = Color.White, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 3, 5, 10).forEach { s ->
                            Chip(if (s == 0) "Off" else "${s}s", selected = PrompterStore.countdown == s) {
                                PrompterStore.countdown = s
                                PrompterStore.saveSettings()
                            }
                        }
                    }
                    Text("Batas durasi rekaman", color = Color.White, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Tanpa batas", 30 to "30 dtk", 60 to "1 mnt", 180 to "3 mnt", 600 to "10 mnt").forEach { (sec, label) ->
                            Chip(label, selected = PrompterStore.limitSec == sec, enabled = !isRecording) {
                                PrompterStore.limitSec = sec
                                PrompterStore.saveSettings()
                            }
                        }
                    }
                    if (stabSupported) {
                        Chip(
                            "Stabilisasi video: ${if (PrompterStore.stabilization) "On" else "Off"}",
                            selected = PrompterStore.stabilization,
                            enabled = !isRecording,
                        ) {
                            PrompterStore.stabilization = !PrompterStore.stabilization
                            PrompterStore.saveSettings()
                        }
                    }
                    if (expSupported && expUpper > expLower) {
                        Text("Kecerahan (exposure)", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = expIndex.toFloat(),
                            onValueChange = {
                                expIndex = it.toInt()
                                state.camera?.cameraControl?.setExposureCompensationIndex(expIndex)
                            },
                            valueRange = expLower.toFloat()..expUpper.toFloat(),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(onClick = {
                    if (isRecording) stopRecording()
                    onClose()
                }) { GlyphText("✕", 14) }
                RoundButton(onClick = { PrompterStore.speed = (PrompterStore.speed - 5f).coerceAtLeast(10f) }) {
                    GlyphText("−", 20)
                }
                // Record: red circle, becomes a square while recording.
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                        .clickable {
                            if (countdown > 0) return@clickable
                            if (isRecording) {
                                stopRecording()
                            } else if (hasPerms) {
                                showSettings = false
                                scope.launch {
                                    scroll.scrollTo(0)
                                    for (i in PrompterStore.countdown downTo 1) {
                                        countdown = i
                                        delay(1000)
                                    }
                                    countdown = 0
                                    startRecording()
                                    playing = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRecording) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                    }
                }
                RoundButton(onClick = { PrompterStore.speed = (PrompterStore.speed + 5f).coerceAtMost(150f) }) {
                    GlyphText("+", 20)
                }
                RoundButton(onClick = { if (!isRecording) front = !front }) { GlyphText("⟲", 18) }
            }
        }
    }
}
