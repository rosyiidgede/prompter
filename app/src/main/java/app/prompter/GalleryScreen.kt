package app.prompter

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class VideoItem(val uri: Uri, val name: String, val durationMs: Long, val dateMs: Long, val sizeBytes: Long)

@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<VideoItem>() }
    var loading by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)

    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) {
            val list = mutableListOf<VideoItem>()
            if (Build.VERSION.SDK_INT >= 29) {
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                )
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    proj,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("Movies/Prompter%"),
                    "${MediaStore.Video.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        list.add(
                            VideoItem(
                                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                c.getString(1) ?: "video",
                                c.getLong(2),
                                c.getLong(3) * 1000,
                                c.getLong(4),
                            ),
                        )
                    }
                }
            }
            list
        }
        items.clear()
        items.addAll(found)
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0E0B16)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Galeri rekaman", Modifier.weight(1f), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Tutup", color = Accent, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        when {
            Build.VERSION.SDK_INT < 29 -> Text(
                "Galeri membutuhkan Android 10 ke atas. Video tersimpan di folder Movies milik app.",
                color = Color(0xB3FFFFFF),
                modifier = Modifier.padding(16.dp),
            )
            loading -> Text("Memuat…", color = Color(0xB3FFFFFF), modifier = Modifier.padding(16.dp))
            items.isEmpty() -> Text("Belum ada rekaman.", color = Color(0xB3FFFFFF), modifier = Modifier.padding(16.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(items, key = { it.uri.toString() }) { v ->
                    VideoRow(v, onDeleted = { items.remove(v) })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(v: VideoItem, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(v.uri, Size(240, 240), null) }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF181226))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A1B52))) {
            thumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(v.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
            val sec = v.durationMs / 1000
            Text(
                "%d:%02d · %.1f MB · %s".format(sec / 60, sec % 60, v.sizeBytes / 1_048_576.0,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(v.dateMs))),
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Putar", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                })
                Text("Bagikan", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, v.uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Bagikan video",
                        ),
                    )
                })
                Text("Hapus", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.clickable {
                    val ok = runCatching { context.contentResolver.delete(v.uri, null, null) > 0 }.getOrDefault(false)
                    if (ok) onDeleted() else Toast.makeText(context, "Tidak bisa menghapus", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}
