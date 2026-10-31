import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

enum class EffectType {
    COLOR,
    GRADIENT,
    RAINBOW,
    WAVE,
    SHAKE,
    PULSE,
    BREATH,
    WOBBLE,
    STRETCH,
    COMPRESS,
    SCALE,
    VERTICAL_SHEAR,
    CUSTOM_ITALIC,
    LETTER_SPACING,
    WORD_FADE,
    VERTICAL_GRADIENT,
    MIRRORED_GRADIENT,
    DISCORD_GRADIENT,
    CHROMA_WAVE,
    GLITCH,
    CHROMA_GLITCH,
    MATRIX_RAIN,
    MATRIX_SPIN,
    LIQUID_PLASMA,
    TEXT_OUTLINE,
    VOLUMETRIC_3D,
    PHOENIX,
}

/** Effects that only move/transform text; their colors come from the code's own color list. */
val MOVEMENT_EFFECTS = setOf(
    EffectType.WAVE, EffectType.SHAKE, EffectType.WOBBLE, EffectType.STRETCH, EffectType.COMPRESS,
    EffectType.SCALE, EffectType.VERTICAL_SHEAR, EffectType.CUSTOM_ITALIC, EffectType.LETTER_SPACING,
)

data class ChromaticCode(
    val effectType: EffectType,
    val colors: List<Color> = emptyList(),
    val colorSpeed: Float = 1f,
    val moveSpeed: Float = 1f,
    val displayName: String = effectType.name.lowercase().replace('_', ' '),
    /** Key of the YAML entry this code came from (null for built-ins and gradients), enabling color write-back. */
    val sourceKey: String? = null,
    val sourceNamed: Boolean = false,
    /** Hue-cycling colors (a movement effect combined with `rainbow`). */
    val rainbow: Boolean = false,
) {
    val isMovementOnly: Boolean get() = effectType in MOVEMENT_EFFECTS
    val primaryColor: Color?   get() = colors.getOrNull(0)
    val secondaryColor: Color? get() = colors.getOrNull(1)
}

object ChromaticCodeRegistry {

    private class Tables {
        val chars = ConcurrentHashMap<Char, ChromaticCode>()
        val named = ConcurrentHashMap<String, ChromaticCode>()
    }

    // Readers (annotator, completion: background threads) only ever see a fully built table.
    // A reload fills a fresh table in `building` and publishes it with a single volatile write.
    @Volatile private var live = Tables()
    @Volatile private var building: Tables? = null
    private val target: Tables get() = building ?: live

    /** Rebuilds the registry: built-in defaults, then [overrides] (e.g. the YAML config), then atomically publishes it. */
    @Synchronized
    fun reload(overrides: () -> Unit = {}) {
        building = Tables()
        try {
            fillDefaults()
            overrides()
            live = building!!
        } finally {
            building = null
        }
    }

    fun loadDefaults() = reload()

    fun registerChar(code: Char, entry: ChromaticCode) {
        target.chars[code.lowercaseChar()] = entry
    }

    fun registerNamed(name: String, entry: ChromaticCode) {
        target.named[name.lowercase()] = entry
    }

    fun unregisterNamed(name: String) {
        target.named.remove(name.lowercase())
    }

    fun getByChar(code: Char): ChromaticCode? = live.chars[code.lowercaseChar()]
    fun getByName(name: String): ChromaticCode? = live.named[name.lowercase()]
    fun allCharCodes(): Map<Char, ChromaticCode> = live.chars
    fun allNamedCodes(): Map<String, ChromaticCode> = live.named
    fun isKnownChar(code: Char): Boolean = live.chars.containsKey(code.lowercaseChar())
    fun isKnownName(name: String): Boolean = live.named.containsKey(name.lowercase())

    private fun fillDefaults() {

        // --- Vanilla Minecraft colors (the YAML config can override these) ---
        ("0:000000 1:0000AA 2:00AA00 3:00AAAA 4:AA0000 5:AA00AA 6:FFAA00 7:AAAAAA " +
            "8:555555 9:5555FF a:55FF55 b:55FFFF c:FF5555 d:FF55FF e:FFFF55 f:FFFFFF").split(" ").forEach {
            val (ch, hex) = it.split(":")
            registerChar(ch[0], ChromaticCode(EffectType.COLOR, listOf(Color(hex.toInt(16))), displayName = "#$hex"))
        }

        // --- Built-in single-char codes ---
        registerChar('w', ChromaticCode(EffectType.WAVE,             moveSpeed = 1f,  displayName = "wave"))
        registerChar('p', ChromaticCode(EffectType.PULSE,            colors = listOf(Color(0xFF0000), Color(0x990000)), colorSpeed = 1f, moveSpeed = 2f, displayName = "pulse"))
        registerChar('*', ChromaticCode(EffectType.RAINBOW,          colorSpeed = 1.5f, displayName = "static rainbow"))
        registerChar('g', ChromaticCode(EffectType.GLITCH,           colors = listOf(Color(0x00FF00), Color(0x005500)), colorSpeed = 1f, moveSpeed = 3f, displayName = "glitch"))
        registerChar('y', ChromaticCode(EffectType.GRADIENT,         colors = listOf(Color(0xFF0000), Color(0xFFFF00), Color(0x00FF00)), colorSpeed = 2.5f, displayName = "tri-color"))
        registerChar('^', ChromaticCode(EffectType.DISCORD_GRADIENT, colors = listOf(Color(0xBF00FF), Color(0xFFB7C5)), displayName = "discord"))
        registerChar('$', ChromaticCode(EffectType.BREATH,           colors = listOf(Color(0xFF00FF)), moveSpeed = 2f, displayName = "breath"))
        registerChar('-', ChromaticCode(EffectType.STRETCH,          colors = listOf(Color(0xFF00FF)), moveSpeed = 2f, displayName = "stretch"))

        // --- Built-in named codes (Deduplicated) ---
        registerNamed("rainbow",        ChromaticCode(EffectType.WAVE,             colorSpeed = 1f, moveSpeed = 1f, displayName = "rainbow", rainbow = true))
        registerNamed("matrix",         ChromaticCode(EffectType.GLITCH,           colors = listOf(Color(0x00FF00), Color(0x005500)), colorSpeed = 1f, moveSpeed = 3f, displayName = "matrix"))
        registerNamed("big",            ChromaticCode(EffectType.SCALE,            colors = listOf(Color.WHITE), moveSpeed = 1.5f, displayName = "big"))
        registerNamed("huge",           ChromaticCode(EffectType.SCALE,            colors = listOf(Color.WHITE), moveSpeed = 2.5f, displayName = "huge"))
        registerNamed("frozen",         ChromaticCode(EffectType.GRADIENT,         colors = listOf(Color(0xFF00FF), Color(0x00FFFF)), displayName = "frozen"))
        registerNamed("glossy",         ChromaticCode(EffectType.MIRRORED_GRADIENT,colors = listOf(Color(0xFF0000), Color(0x00FF00), Color(0x0000FF)), displayName = "glossy"))
        registerNamed("sunset",         ChromaticCode(EffectType.VERTICAL_GRADIENT,colors = listOf(Color(0xFFAA00), Color(0xFF00AA)), displayName = "sunset"))
        registerNamed("spaced",         ChromaticCode(EffectType.LETTER_SPACING,   colors = listOf(Color.WHITE), moveSpeed = 4f, displayName = "spaced"))
        registerNamed("speed",          ChromaticCode(EffectType.CUSTOM_ITALIC,    colors = listOf(Color.WHITE), moveSpeed = 0.45f, displayName = "speed"))
        registerNamed("neon_ocean",     ChromaticCode(EffectType.CHROMA_WAVE,      colorSpeed = 1.2f, moveSpeed = 1f, displayName = "neon ocean"))
        registerNamed("cyber",          ChromaticCode(EffectType.CHROMA_WAVE,      colors = listOf(Color(0xFF0055), Color(0x00FFFF)), colorSpeed = 1f, moveSpeed = 1f, displayName = "cyber"))
        registerNamed("uphill",         ChromaticCode(EffectType.VERTICAL_SHEAR,   colors = listOf(Color.WHITE), moveSpeed = 0.2f, displayName = "uphill"))
        registerNamed("rgb_ticker",     ChromaticCode(EffectType.CHROMA_WAVE,      colorSpeed = 1.5f, displayName = "rgb ticker"))
        registerNamed("jelly",          ChromaticCode(EffectType.WOBBLE,           colors = listOf(Color(0x00FFAA)), moveSpeed = 1.2f, displayName = "jelly"))
        registerNamed("elastic",        ChromaticCode(EffectType.COMPRESS,         colors = listOf(Color.WHITE), moveSpeed = 1f, displayName = "elastic"))
        registerNamed("ghost",          ChromaticCode(EffectType.WORD_FADE,        colors = listOf(Color.WHITE), displayName = "ghost"))
        registerNamed("3d_block",       ChromaticCode(EffectType.VOLUMETRIC_3D,    colors = listOf(Color(0xFFCC00), Color(0x443300)), moveSpeed = 5f, displayName = "3d block"))
        registerNamed("digital",        ChromaticCode(EffectType.MATRIX_RAIN,      colors = listOf(Color(0x00FF00)), colorSpeed = 1.5f, displayName = "digital"))
        registerNamed("flip",           ChromaticCode(EffectType.MATRIX_SPIN,      colors = listOf(Color.WHITE), moveSpeed = 1.5f, displayName = "flip"))
        registerNamed("plasma",         ChromaticCode(EffectType.LIQUID_PLASMA,    colors = listOf(Color.WHITE), colorSpeed = 1.5f, displayName = "plasma"))
        registerNamed("phoenix",        ChromaticCode(EffectType.PHOENIX,          colors = listOf(Color(0xBF00FF)), displayName = "phoenix"))
        registerNamed("discord",        ChromaticCode(EffectType.DISCORD_GRADIENT, colors = listOf(Color(0xBF00FF), Color(0xFFB7C5)), displayName = "discord"))
        registerNamed("wave",           ChromaticCode(EffectType.WAVE,             moveSpeed = 1f, displayName = "wave"))
        registerNamed("shake",          ChromaticCode(EffectType.SHAKE,            moveSpeed = 1f, displayName = "shake"))
        registerNamed("pulse",          ChromaticCode(EffectType.PULSE,            colors = listOf(Color(0xFF0000), Color(0x990000)), moveSpeed = 2f, displayName = "pulse"))
        registerNamed("breath",         ChromaticCode(EffectType.BREATH,           colors = listOf(Color(0xFF00FF)), moveSpeed = 2f, displayName = "breath"))
        registerNamed("stretch",        ChromaticCode(EffectType.STRETCH,          colors = listOf(Color(0xFF00FF)), moveSpeed = 2f, displayName = "stretch"))
        registerNamed("glitch",         ChromaticCode(EffectType.GLITCH,           colors = listOf(Color(0x00FF00), Color(0x005500)), moveSpeed = 3f, displayName = "glitch"))
        registerNamed("static_rainbow", ChromaticCode(EffectType.RAINBOW,          colorSpeed = 1.5f, displayName = "static rainbow"))
        registerNamed("outline",        ChromaticCode(EffectType.TEXT_OUTLINE,     colors = listOf(Color.BLACK), displayName = "outline"))
        registerNamed("wobble",         ChromaticCode(EffectType.WOBBLE,           moveSpeed = 1f, displayName = "wobble"))
        registerNamed("compress",       ChromaticCode(EffectType.COMPRESS,         moveSpeed = 1f, displayName = "compress"))
    }

    fun loadFromModConfig(yaml: Map<*, *>) {
        val colors = yaml["colors"] as? Map<*, *> ?: yaml

        (colors["customColors"] as? List<*>)?.forEach { entry ->
            val (ch, hex) = parseCharEntry(entry) ?: return@forEach
            val color = parseHex(hex) ?: return@forEach
            registerChar(ch, ChromaticCode(EffectType.COLOR, listOf(color), displayName = "#$hex", sourceKey = ch.toString()))
        }

        (colors["customGradients"] as? List<*>)?.forEach { entry ->
            val (ch, rest) = parseCharEntry(entry) ?: return@forEach
            parseGradientEntry(rest)?.let { registerChar(ch, it.copy(sourceKey = ch.toString())) }
        }

        (colors["namedColors"] as? List<*>)?.forEach { entry ->
            val (name, hex) = parseBracketEntry(entry) ?: return@forEach
            val color = parseHex(hex) ?: return@forEach
            registerNamed(name, ChromaticCode(EffectType.COLOR, listOf(color), displayName = name, sourceKey = name, sourceNamed = true))
        }

        (colors["namedGradients"] as? List<*>)?.forEach { entry ->
            val (name, rest) = parseBracketEntry(entry) ?: return@forEach
            parseGradientEntry(rest)?.let { code ->
                registerNamed(name, code.copy(displayName = name, sourceKey = name, sourceNamed = true))
            }
        }
    }

    private fun parseCharEntry(entry: Any?): Pair<Char, String>? {
        return when (entry) {
            is String -> {
                val colon = entry.indexOf(':')
                if (colon < 1) null else Pair(entry[0], entry.substring(colon + 1))
            }
            is Map<*, *> -> {
                val key   = entry.keys.firstOrNull()?.toString()?.firstOrNull() ?: return null
                val value = entry.values.firstOrNull()?.toString() ?: return null
                Pair(key, value)
            }
            else -> null
        }
    }

    private fun parseBracketEntry(entry: Any?): Pair<String, String>? {
        return when (entry) {
            is String -> {
                if (!entry.startsWith("[")) return null
                val bracket = entry.indexOf(']')
                if (bracket < 2) null
                else Pair(entry.substring(1, bracket), entry.substring(bracket + 2))
            }
            is Map<*, *> -> {
                val key   = entry.keys.firstOrNull()
                val value = entry.values.firstOrNull()?.toString() ?: return null
                val name  = when (key) {
                    is List<*> -> key.firstOrNull()?.toString() ?: return null
                    is String  -> key.removePrefix("[").removeSuffix("]")
                    else       -> return null
                }
                Pair(name, value)
            }
            else -> null
        }
    }

    /** Movement ids accepted in `speed:speed:movement:colors` entries, in display order. */
    val movementIds = listOf(
        "none", "wave", "shake", "pulse", "breath", "glitch", "stretch", "wobble",
        "compress", "size", "slant", "spacing", "shear", "fade",
    )

    /** Reverse of the movement-id lookup; "none" for effects that have no movement id. */
    fun movementIdFor(effect: EffectType): String = when (effect) {
        EffectType.WAVE -> "wave"
        EffectType.SHAKE -> "shake"
        EffectType.PULSE -> "pulse"
        EffectType.BREATH -> "breath"
        EffectType.GLITCH -> "glitch"
        EffectType.STRETCH -> "stretch"
        EffectType.WOBBLE -> "wobble"
        EffectType.COMPRESS -> "compress"
        EffectType.SCALE -> "size"
        EffectType.CUSTOM_ITALIC -> "slant"
        EffectType.LETTER_SPACING -> "spacing"
        EffectType.VERTICAL_SHEAR -> "shear"
        EffectType.WORD_FADE -> "fade"
        else -> "none"
    }

    /** Parses a `speed:speed:movement:colors` entry body, e.g. `1:1:wave:FF0000,0000FF`. */
    fun codeFromEntry(entry: String): ChromaticCode? = parseGradientEntry(entry)

    private fun parseGradientEntry(entry: String): ChromaticCode? {
        val parts = entry.split(":", limit = 4)
        if (parts.size < 4) return null

        val colorSpeed = parts[0].trim().toFloatOrNull() ?: 1f
        val moveSpeed  = parts[1].trim().toFloatOrNull() ?: 1f
        val movementId = parts[2].trim().lowercase()
        val colorPart  = parts[3].trim()

        val effectType = movementIdToEffect(movementId, colorPart)

        val colors = if (colorPart.equals("rainbow", ignoreCase = true)) {
            emptyList()
        } else {
            colorPart.split(",").mapNotNull { parseHex(it.trim()) }
        }

        return ChromaticCode(
            effectType  = effectType,
            colors      = colors,
            colorSpeed  = colorSpeed,
            moveSpeed   = moveSpeed,
            displayName = movementId,
            rainbow     = colorPart.equals("rainbow", ignoreCase = true),
        )
    }

    private fun movementIdToEffect(id: String, colorPart: String): EffectType = when (id) {
        "wave"          -> EffectType.WAVE
        "shake"         -> EffectType.SHAKE
        "pulse"         -> EffectType.PULSE
        "breath"        -> EffectType.BREATH
        "glitch"        -> EffectType.GLITCH
        "discord"       -> EffectType.DISCORD_GRADIENT
        "stretch"       -> EffectType.STRETCH
        "wobble"        -> EffectType.WOBBLE
        "compress"      -> EffectType.COMPRESS
        "size"          -> EffectType.SCALE
        "slant"         -> EffectType.CUSTOM_ITALIC
        "spacing"       -> EffectType.LETTER_SPACING
        "shear"         -> EffectType.VERTICAL_SHEAR
        "fade"          -> EffectType.WORD_FADE
        "mirror"        -> EffectType.MIRRORED_GRADIENT
        "vertical"      -> EffectType.VERTICAL_GRADIENT
        "rainbow_wave"  -> EffectType.CHROMA_WAVE
        "chroma"        -> EffectType.CHROMA_WAVE
        "chroma_wave"   -> EffectType.CHROMA_WAVE
        "static_rainbow"-> EffectType.RAINBOW
        "matrix_rain"   -> EffectType.MATRIX_RAIN
        "3d_spin"       -> EffectType.MATRIX_SPIN
        "3d"            -> EffectType.VOLUMETRIC_3D
        "liquid_plasma" -> EffectType.LIQUID_PLASMA
        "none"          -> {
            if (colorPart.equals("rainbow", ignoreCase = true)) EffectType.RAINBOW
            else if (colorPart.contains(",")) EffectType.GRADIENT
            else EffectType.COLOR
        }
        else -> EffectType.COLOR
    }

    private fun parseHex(hex: String): Color? {
        if (hex.isBlank()) return null
        return runCatching {
            val clean = hex.replace("#", "").trim()
            Color(clean.toLong(16).toInt(), clean.length == 8)
        }.getOrNull()
    }
}