package app.prompter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Checks GitHub Releases for a newer build ("build-N" tag), downloads the APK and hands it to the installer. */
object Updater {
    /** Public repo that hosts the releases. */
    const val REPO = "rosyiidgede/prompter"

    data class Update(val code: Int, val url: String)

    var available by mutableStateOf<Update?>(null)
    var status by mutableStateOf("")
    var progress by mutableIntStateOf(-1)
    var busy by mutableStateOf(false)

    fun currentCode(context: Context): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    fun check(context: Context, manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) status = "Mengecek update…"
        val appContext = context.applicationContext
        thread {
            try {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val code = json.getString("tag_name").removePrefix("build-").toIntOrNull() ?: error("tag tidak dikenal")
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                if (url != null && code > currentCode(appContext)) {
                    available = Update(code, url)
                    status = "Versi baru tersedia (build $code)"
                } else {
                    available = null
                    status = if (manual) "Sudah versi terbaru (build ${currentCode(appContext)})" else ""
                }
            } catch (e: Exception) {
                status = if (manual) "Gagal cek update: ${e.message}" else ""
            } finally {
                busy = false
            }
        }
    }

    fun downloadAndInstall(activity: Activity) {
        val u = available ?: return
        if (busy) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            status = "Izinkan install dari app ini, lalu tekan Update lagi"
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        busy = true
        progress = 0
        status = "Mengunduh…"
        thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "prompter.apk")
                val conn = URL(u.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) progress = (read * 100 / total).toInt()
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                status = "Ikuti layar instalasi Android"
            } catch (e: Exception) {
                status = "Gagal mengunduh: ${e.message}"
            } finally {
                progress = -1
                busy = false
            }
        }
    }
}
