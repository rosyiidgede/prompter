package app.prompter

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Script(val id: Long, val text: String) {
    val title: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: "(kosong)"
}

/** Shared state for the app and the overlay service (same process). Scripts autosave. */
object PrompterStore {
    private const val DEFAULT_SCRIPT =
        "If you sound weird on a teleprompter, you're probably using it wrong. " +
            "Most people try to read it. Instead, glance, then say it like you mean it.\n\n" +
            "What's your biggest fear about making videos? Mine was forgetting what I wanted to say."

    val scripts = mutableStateListOf<Script>()
    var currentId by mutableLongStateOf(0L)

    var speed by mutableFloatStateOf(45f)       // dp per second
    var fontSize by mutableFloatStateOf(20f)    // sp
    var opacity by mutableFloatStateOf(0.95f)
    var panelDp by mutableFloatStateOf(150f)    // height of the text area in record mode
    var mirror by mutableStateOf(false)
    var extraTopDp by mutableFloatStateOf(0f)   // extra gap between camera hole and text

    // camera settings
    var quality by mutableStateOf("FHD")          // UHD | FHD | HD | SD
    var countdown by mutableIntStateOf(3)         // seconds before recording
    var limitSec by mutableIntStateOf(0)          // 0 = no limit
    var stabilization by mutableStateOf(false)   // adds preview delay, so off by default
    var mirrorPreview by mutableStateOf(true)     // front camera: show like a mirror
    var grid by mutableStateOf(false)
    var filterId by mutableStateOf("orig")
    var filterStrength by mutableFloatStateOf(1f)

    var script: String
        get() = scripts.firstOrNull { it.id == currentId }?.text ?: ""
        set(v) {
            val i = scripts.indexOfFirst { it.id == currentId }
            if (i >= 0) {
                scripts[i] = scripts[i].copy(text = v)
                save()
            }
        }

    private var loaded = false
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences("prompter", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val arr = JSONArray(p.getString("scripts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                scripts.add(Script(o.getLong("id"), o.getString("text")))
            }
        }
        if (scripts.isEmpty()) {
            // migrate the old single-script key, if any
            scripts.add(Script(System.currentTimeMillis(), p.getString("script", DEFAULT_SCRIPT) ?: DEFAULT_SCRIPT))
        }
        currentId = p.getLong("currentId", scripts[0].id).takeIf { id -> scripts.any { it.id == id } } ?: scripts[0].id
        speed = p.getFloat("speed", speed)
        fontSize = p.getFloat("fontSize", fontSize)
        opacity = p.getFloat("opacity2", opacity)
        panelDp = p.getFloat("panelDp", panelDp)
        mirror = p.getBoolean("mirror", mirror)
        extraTopDp = p.getFloat("extraTopDp", extraTopDp)
        quality = p.getString("quality", quality) ?: quality
        countdown = p.getInt("countdown", countdown)
        limitSec = p.getInt("limitSec", limitSec)
        stabilization = p.getBoolean("stab2", stabilization)
        mirrorPreview = p.getBoolean("mirrorPreview", mirrorPreview)
        grid = p.getBoolean("grid", grid)
        filterId = p.getString("filterId", filterId) ?: filterId
        filterStrength = p.getFloat("filterStrength", filterStrength)
    }

    fun newScript() {
        val s = Script(System.currentTimeMillis(), "")
        scripts.add(0, s)
        currentId = s.id
        save()
    }

    fun select(id: Long) {
        currentId = id
        save()
    }

    fun delete(id: Long) {
        scripts.removeAll { it.id == id }
        if (scripts.isEmpty()) scripts.add(Script(System.currentTimeMillis(), ""))
        if (scripts.none { it.id == currentId }) currentId = scripts[0].id
        save()
    }

    fun persist(@Suppress("UNUSED_PARAMETER") context: Context) = save()

    /** Call after changing a camera setting. */
    fun saveSettings() = save()

    private fun save() {
        val arr = JSONArray()
        scripts.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text)) }
        prefs?.edit()
            ?.putString("scripts", arr.toString())
            ?.putLong("currentId", currentId)
            ?.putFloat("speed", speed)
            ?.putFloat("fontSize", fontSize)
            ?.putFloat("opacity2", opacity)
            ?.putFloat("panelDp", panelDp)
            ?.putBoolean("mirror", mirror)
            ?.putFloat("extraTopDp", extraTopDp)
            ?.putString("quality", quality)
            ?.putInt("countdown", countdown)
            ?.putInt("limitSec", limitSec)
            ?.putBoolean("stab2", stabilization)
            ?.putBoolean("mirrorPreview", mirrorPreview)
            ?.putBoolean("grid", grid)
            ?.putString("filterId", filterId)
            ?.putFloat("filterStrength", filterStrength)
            ?.apply()
    }
}
