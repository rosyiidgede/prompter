package app.prompter

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.WindowManager
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
}

/** Split screen: prompter text on top (next to the front lens), live camera preview below, record button. */
@Composable
fun RecordScreen(onClose: () -> Unit) {
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

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(hasPerms, front) {
        val future = ProcessCameraProvider.getInstance(context)
        if (hasPerms) {
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    vc,
                )
                state.videoCapture = vc
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            state.recording?.stop()
            state.recording = null
            if (future.isDone) runCatching { future.get().unbindAll() }
        }
    }

    fun startRecording() {
        val vc = state.videoCapture ?: return
        val name = "Prompter-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val pending = if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Prompter")
            }
            vc.output.prepareRecording(
                context,
                MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(cv).build(),
            )
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            vc.output.prepareRecording(context, FileOutputOptions.Builder(File(dir, "$name.mp4")).build())
        }
        state.recording = pending.withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                isRecording = false
                val msg = if (ev.hasError()) "Rekaman gagal (kode ${ev.error})" else "Tersimpan di Movies/Prompter"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        isRecording = true
    }

    fun stopRecording() {
        playing = false
        state.recording?.stop()
        state.recording = null
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
        // Top: prompter, right below the status bar / front lens.
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

        // Bottom: camera preview with controls.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPerms) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    "Izin kamera & mikrofon dibutuhkan.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (countdown > 0) {
                Text(
                    "$countdown",
                    color = Color.White,
                    fontSize = 96.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
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
                                scope.launch {
                                    scroll.scrollTo(0)
                                    for (i in 3 downTo 1) {
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
