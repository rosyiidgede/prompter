package app.prompter

/** Colour-grade presets. Approximations of the looks, not official replicas. */
class FilterPreset(
    val id: String,
    val label: String,
    val group: String,
    val sat: Float = 1f,
    val contrast: Float = 1f,
    val bright: Float = 0f,
    val lift: Float = 0f,
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f),
    val shadow: FloatArray = floatArrayOf(0f, 0f, 0f),
    val high: FloatArray = floatArrayOf(0f, 0f, 0f),
)

object Filters {
    val ORIGINAL = FilterPreset("orig", "Original", "")

    val all = listOf(
        ORIGINAL,
        FilterPreset("ip_std", "Standard", "iPhone", sat = 1.05f, contrast = 1.04f),
        FilterPreset("ip_vivid", "Vivid", "iPhone", sat = 1.3f, contrast = 1.1f, bright = 0.01f),
        FilterPreset("ip_warm", "Warm", "iPhone", sat = 1.05f, gain = floatArrayOf(1.07f, 1f, 0.92f)),
        FilterPreset("ip_cool", "Cool", "iPhone", gain = floatArrayOf(0.93f, 1f, 1.08f)),
        FilterPreset("ip_mono", "Mono", "iPhone", sat = 0f, contrast = 1.12f),
        FilterPreset("fj_provia", "Provia", "Fujifilm", sat = 1.05f, contrast = 1.06f),
        FilterPreset("fj_velvia", "Velvia", "Fujifilm", sat = 1.45f, contrast = 1.22f, gain = floatArrayOf(1.02f, 1f, 0.99f)),
        FilterPreset("fj_astia", "Astia", "Fujifilm", sat = 1.12f, contrast = 0.94f, bright = 0.02f, gain = floatArrayOf(1.03f, 1f, 0.98f)),
        FilterPreset(
            "fj_chrome", "Classic Chrome", "Fujifilm", sat = 0.72f, contrast = 1.18f, lift = 0.03f,
            shadow = floatArrayOf(-0.012f, 0.004f, 0.018f), high = floatArrayOf(0.01f, 0.006f, -0.004f),
        ),
        FilterPreset(
            "fj_neg", "Classic Neg", "Fujifilm", sat = 1.05f, contrast = 1.22f, lift = 0.025f,
            gain = floatArrayOf(1.03f, 0.99f, 0.96f),
            shadow = floatArrayOf(-0.01f, 0.012f, 0.02f), high = floatArrayOf(0.02f, 0f, -0.01f),
        ),
        FilterPreset("fj_eterna", "Eterna", "Fujifilm", sat = 0.78f, contrast = 0.88f, lift = 0.05f, gain = floatArrayOf(0.98f, 1f, 1.03f)),
        FilterPreset("fj_acros", "Acros (B&W)", "Fujifilm", sat = 0f, contrast = 1.25f, lift = 0.01f),
    )

    fun byId(id: String): FilterPreset = all.firstOrNull { it.id == id } ?: ORIGINAL
}
