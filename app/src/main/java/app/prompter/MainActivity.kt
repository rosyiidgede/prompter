package app.prompter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var canOverlay by mutableStateOf(false)
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrompterStore.init(this)
        Updater.check(this, manual = false)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Color(0xFF0E0B16),
                    surface = Color(0xFF181226),
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var preview by remember { mutableStateOf(false) }
                    var record by remember { mutableStateOf(false) }
                    var gallery by remember { mutableStateOf(false) }
                    if (gallery) {
                        GalleryScreen(onClose = { gallery = false })
                    } else if (record) {
                        RecordScreen(
                            onClose = { record = false },
                            onGallery = { record = false; gallery = true },
                        )
                    } else if (preview) {
                        PreviewScreen(onClose = { preview = false })
                    } else {
                        HomeScreen(
                            canOverlay = canOverlay,
                            onStart = ::startOverlay,
                            onPreview = { preview = true },
                            onRecord = { record = true },
                            onGallery = { gallery = true },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canOverlay = Settings.canDrawOverlays(this)
    }

    override fun onPause() {
        PrompterStore.persist(this)
        super.onPause()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        PrompterStore.persist(this)
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        moveTaskToBack(true) // go to the camera app; the prompter keeps floating
    }
}

@Composable
private fun HomeScreen(
    canOverlay: Boolean,
    onStart: () -> Unit,
    onPreview: () -> Unit,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Prompter", fontSize = 30.sp, fontWeight = FontWeight.Bold)

        val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
        Updater.available?.let { up ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x338B5CF6), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Update tersedia: build ${up.code}", fontWeight = FontWeight.SemiBold)
                if (Updater.progress >= 0) Text("Mengunduh ${Updater.progress}%")
                Button(
                    onClick = { Updater.downloadAndInstall(activity) },
                    enabled = !Updater.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Update sekarang") }
            }
        }
        Text(
            "Tulis naskah, lalu rekam langsung di sini: naskah di bagian atas dekat lensa, preview kamera di bawahnya.",
            color = Color(0xB3FFFFFF),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Naskah (tersimpan otomatis)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { PrompterStore.newScript() }) { Text("+ Baru") }
        }

        OutlinedTextField(
            value = PrompterStore.script,
            onValueChange = { PrompterStore.script = it },
            label = { Text("Naskah") },
            minLines = 8,
            maxLines = 14,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Daftar naskah", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            PrompterStore.scripts.forEach { sc ->
                val selected = sc.id == PrompterStore.currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0x338B5CF6) else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { PrompterStore.select(sc.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sc.title, Modifier.weight(1f), maxLines = 1)
                    Text(
                        "Hapus",
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { PrompterStore.delete(sc.id) }.padding(6.dp),
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Jarak teks dari lubang kamera: ${PrompterStore.extraTopDp.toInt()} dp")
            Slider(PrompterStore.extraTopDp, { PrompterStore.extraTopDp = it }, valueRange = 0f..80f)
            Text("Tinggi area teks (mode rekam): ${PrompterStore.panelDp.toInt()} dp")
            Slider(PrompterStore.panelDp, { PrompterStore.panelDp = it }, valueRange = 90f..320f)
            Text("Kecepatan: %.1fx".format(PrompterStore.speed / 45f))
            Slider(PrompterStore.speed, { PrompterStore.speed = it }, valueRange = 10f..150f)
            Text("Ukuran teks: ${PrompterStore.fontSize.toInt()} sp")
            Slider(PrompterStore.fontSize, { PrompterStore.fontSize = it }, valueRange = 14f..44f)
            Text("Opasitas latar: ${(PrompterStore.opacity * 100).toInt()}%")
            Slider(PrompterStore.opacity, { PrompterStore.opacity = it }, valueRange = 0.2f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode cermin (untuk kaca teleprompter)", Modifier.weight(1f))
                Switch(PrompterStore.mirror, { PrompterStore.mirror = it })
            }
        }

        if (!canOverlay) {
            Text(
                "Izin \"Tampil di atas aplikasi lain\" belum aktif. Tekan Start untuk membuka pengaturannya.",
                color = Color(0xFFFFB74D),
            )
        }

        Button(
            onClick = onRecord,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text("Rekam (teks di atas, kamera di bawah)", Modifier.padding(6.dp)) }

        OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Overlay di atas app kamera lain")
        }

        OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
            Text("Galeri rekaman")
        }

        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            Text("Preview layar penuh")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Updater.status.ifEmpty { "Build ${Updater.currentCode(activity)}" },
                color = Color(0x99FFFFFF),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { Updater.check(activity, manual = true) }, enabled = !Updater.busy) {
                Text("Cek update")
            }
        }

        Text(
            "Tips: geser teks = scroll manual · tap = jeda/lanjut · double-tap = kembali ke awal · pinch = ubah ukuran jendela · seret garis di atas untuk memindahkan.",
            color = Color(0x99FFFFFF),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PreviewScreen(onClose: () -> Unit) {
    val scroll = rememberScrollState()
    var playing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PrompterText(
            scroll = scroll,
            playing = playing,
            onReachedEnd = { playing = false },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .clickable { playing = !playing },
        )
        Text(
            "✕",
            color = Color.White,
            fontSize = 22.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .clickable(onClick = onClose)
                .padding(16.dp),
        )
    }
}
