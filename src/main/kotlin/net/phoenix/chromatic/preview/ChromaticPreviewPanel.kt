package net.phoenix.chromatic.preview

import ChromaticCode
import EffectType
import com.intellij.ide.ui.UISettings

import java.awt.*
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.*

/**
 * Renders a Minecraft-style preview of a string containing §X and [named] codes.
 * Dynamically adjusts its sizing specifications according to the bound text limits.
 */
class ChromaticPreviewPanel(text: String = "") : JPanel() {

    var previewText: String = text
        set(value) {
            field = value
            segments = parseSegments(value)
            recalculateLayoutDimensions()
            repaint()
        }

    private var segments: List<Segment> = parseSegments(text)
    private var tick: Long = 0L

    private val timer = Timer(33) {  // ~30 fps
        tick++
        repaint()
    }

    private val mcFont: Font = Font("Monospaced", Font.PLAIN, 20)
    private val charWidth = 12f
    private val charHeight = 20f
    private val basePadding = 24f

    init {
        background = Color(30, 30, 30)
        timer.start()
        recalculateLayoutDimensions()
    }

    /**
     * Measures the absolute plain text character contents length to scale the panel
     * width automatically when opened, preventing text truncation or empty dead space.
     */
    private fun recalculateLayoutDimensions() {
        val plainTextLength = segments.sumOf { it.text.length }
        val calculatedWidth = (plainTextLength * charWidth.toInt()) + (basePadding.toInt() * 2)
        val finalWidth = calculatedWidth.coerceAtLeast(300).coerceAtMost(1000)

        preferredSize = Dimension(finalWidth, 80)
        invalidate()
    }

    fun stop() = timer.stop()

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as? Graphics2D ?: return

        // FIX: Replaced internal internal platform utils with the official, stable
        // IDE UI settings manager pipeline. This guarantees matching subpixel antialiasing!
        UISettings.setupAntialiasing(g2)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        g2.font = mcFont

        val startX = basePadding
        val startY = height / 2f + charHeight / 4f

        var currentX = startX

        for (seg in segments) {
            val code = seg.code
            val isGradient = isGradientEffect(code.effectType)

            for (idx in seg.text.indices) {
                val ch = seg.text[idx]
                val charStr = ch.toString()
                val globalIdx = seg.globalOffset + idx

                val gCopy = g2.create() as Graphics2D
                try {
                    applyTransformations(gCopy, code, currentX, startY, idx, seg.text.length, globalIdx)
                    applyColoring(gCopy, code, currentX, startY, idx, seg.text.length, globalIdx, isGradient)

                    gCopy.drawString(charStr, currentX, startY)
                } finally {
                    gCopy.dispose()
                }

                currentX += charWidth + if (code.effectType == EffectType.LETTER_SPACING) code.moveSpeed else 0f
            }
        }
    }

    private fun isGradientEffect(effect: EffectType): Boolean {
        return effect in listOf(
            EffectType.GRADIENT, EffectType.VERTICAL_GRADIENT,
            EffectType.MIRRORED_GRADIENT, EffectType.DISCORD_GRADIENT, EffectType.PHOENIX
        )
    }

    private fun applyTransformations(g2: Graphics2D, code: ChromaticCode, cx: Float, cy: Float, idx: Int, len: Int, globalIdx: Int) {
        val t = tick.toDouble()
        val ms = code.moveSpeed.toDouble()
        when (code.effectType) {
            EffectType.WAVE -> {
                val offset = sin(t * 0.2 * ms + globalIdx * 0.5) * 4.0
                g2.translate(0.0, offset)
            }
            EffectType.CHROMA_WAVE -> {
                val offset = sin(t * 0.15 * ms + globalIdx * 0.4) * 5.0
                g2.translate(0.0, offset)
            }
            EffectType.SHAKE -> {
                val amp = 1.5 * ms.coerceAtLeast(0.5)
                g2.translate((Math.random() - 0.5) * amp, (Math.random() - 0.5) * amp)
            }
            EffectType.GLITCH, EffectType.CHROMA_GLITCH -> {
                if (tick % 20L == 0L && Math.random() < 0.3) {
                    g2.translate((Math.random() - 0.5) * 4.0 * ms, 0.0)
                }
            }
            EffectType.WOBBLE -> {
                val angle = sin(t * 0.1 * ms + globalIdx * 0.3) * 0.1
                g2.rotate(angle, cx.toDouble() + charWidth / 2.0, cy.toDouble() - charHeight / 2.0)
            }
            EffectType.SCALE -> {
                val scale = ms.coerceAtLeast(0.1)
                g2.scale(scale, scale)
            }
            EffectType.STRETCH -> {
                val sx = 1.0 + sin(t * 0.2 * ms + globalIdx * 0.5).coerceAtLeast(0.0) * 0.5
                g2.scale(sx, 1.0)
            }
            EffectType.COMPRESS -> {
                val sy = 1.0 + sin(t * 0.2 * ms + globalIdx * 0.5).coerceAtLeast(0.0) * 0.5
                g2.scale(1.0, sy)
            }
            EffectType.CUSTOM_ITALIC -> {
                val shear = -(ms.coerceIn(0.1, 1.0))
                g2.shear(shear, 0.0)
            }
            EffectType.VERTICAL_SHEAR -> {
                val step = idx * ms * 0.5
                g2.translate(0.0, -step)
            }
            else -> {}
        }
    }

    private fun applyColoring(g2: Graphics2D, code: ChromaticCode, cx: Float, cy: Float, idx: Int, len: Int, globalIdx: Int, isGradient: Boolean) {
        val t = tick.toDouble()
        val cs = code.colorSpeed.toDouble()
        val colors = code.colors

        // Movement effect + rainbow: hue cycles while the text moves
        if (code.isMovementOnly && code.rainbow) {
            g2.color = hsvToColor(((tick * cs * 2 + globalIdx * 10) % 360).toFloat() / 360f, 0.8f, 0.9f)
            return
        }

        // Gradient effects (and movement effects with 2+ colors): interpolate across all color stops
        if ((isGradient || code.isMovementOnly) && colors.size >= 2 && len > 1) {
            val ratio = idx.toFloat() / (len - 1).toFloat()
            g2.color = lerpMultiStop(colors, ratio)
            return
        }

        val c1 = colors.getOrElse(0) { Color.WHITE }
        val c2 = colors.getOrElse(1) { Color.GRAY }

        when (code.effectType) {
            EffectType.COLOR    -> g2.color = c1
            EffectType.GRADIENT -> {
                val ratio = if (len > 1) idx.toFloat() / (len - 1) else 0f
                g2.color = lerpMultiStop(colors.ifEmpty { listOf(c1) }, ratio)
            }
            EffectType.RAINBOW,
            EffectType.CHROMA_WAVE -> {
                val hue = ((tick * cs * 2 + globalIdx * 10) % 360).toFloat() / 360f
                g2.color = hsvToColor(hue, 0.8f, 0.9f)
            }
            EffectType.PULSE -> {
                val factor = (sin(t * 0.15 * cs) + 1.0) / 2.0
                g2.color = lerpColor(c1, c2, factor.toFloat())
            }
            EffectType.BREATH -> {
                val factor = (sin(t * 0.08 * cs) + 1.0) / 2.0
                g2.color = lerpColor(c1, Color(40, 40, 40), factor.toFloat())
            }
            EffectType.GLITCH,
            EffectType.CHROMA_GLITCH -> {
                if (Math.random() < 0.06 * cs) {
                    g2.color = listOf(c1, c2, Color.CYAN, Color.MAGENTA).random()
                } else {
                    g2.color = c1
                }
            }
            EffectType.LIQUID_PLASMA -> {
                val r = (0.5 + 0.5 * sin(t * 0.1 * cs + globalIdx * 0.7)).toFloat()
                val gr = (0.5 + 0.5 * sin(t * 0.13 * cs + globalIdx * 0.4 + 2)).toFloat()
                val b = (0.5 + 0.5 * sin(t * 0.08 * cs + globalIdx * 0.6 + 4)).toFloat()
                g2.color = Color(r, gr, b)
            }
            EffectType.WORD_FADE -> {
                val alpha = ((sin(t * 0.07 * cs + globalIdx * 0.4) + 1.0) / 2.0).toFloat()
                g2.color = Color(c1.red, c1.green, c1.blue, (alpha * 255).toInt().coerceIn(0, 255))
            }
            EffectType.MATRIX_RAIN,
            EffectType.MATRIX_SPIN -> {
                val brightness = (0.3 + 0.7 * ((sin(t * 0.2 * cs + globalIdx * 0.5) + 1.0) / 2.0)).toFloat()
                g2.color = Color(0, (c1.green * brightness).toInt().coerceIn(0, 255), 0)
            }
            else -> g2.color = c1
        }
    }

    /** Lerp across an arbitrary list of color stops at position [t] in [0,1]. */
    private fun lerpMultiStop(stops: List<Color>, t: Float): Color {
        if (stops.isEmpty()) return Color.WHITE
        if (stops.size == 1) return stops[0]
        val scaled = t.coerceIn(0f, 1f) * (stops.size - 1)
        val lo = scaled.toInt().coerceIn(0, stops.size - 2)
        val hi = lo + 1
        return lerpColor(stops[lo], stops[hi], scaled - lo)
    }

    private fun parseSegments(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        if (text.isEmpty()) return result

        // "&[name]" / "§[name]" are the in-game spellings of the bracket tags handled below
        val text = text.replace("&[", "[").replace("§[", "[")

        var i = 0
        var globalOffset = 0
        var currentCode = ChromaticCodeRegistry.getByChar('f') ?: ChromaticCode(EffectType.COLOR, listOf(Color.WHITE))

        // Text accumulation buffer
        var textBuffer = StringBuilder()

        fun flushBuffer() {
            if (textBuffer.isNotEmpty()) {
                result.add(Segment(currentCode, textBuffer.toString(), globalOffset))
                globalOffset += textBuffer.length
                textBuffer = StringBuilder()
            }
        }

        while (i < text.length) {
            // 1. Check for standard color code symbols: §x
            if ((text[i] == '§' || (text[i] == '&' && i + 1 < text.length && ChromaticCodeRegistry.isKnownChar(text[i + 1])))
                && i + 1 < text.length) {
                flushBuffer()
                val codeChar = text[i + 1]
                currentCode = ChromaticCodeRegistry.getByChar(codeChar) ?: currentCode
                i += 2
            }
            // 2. Check for custom closing tags: [/tag]
            else if (text[i] == '[' && text.startsWith("[/", i)) {
                val closeIndex = text.indexOf(']', i)
                if (closeIndex != -1) {
                    flushBuffer()
                    // Revert back to regular clean white text style after closing tag
                    currentCode = ChromaticCodeRegistry.getByChar('f') ?: ChromaticCode(EffectType.COLOR, listOf(Color.WHITE))
                    i = closeIndex + 1
                } else {
                    textBuffer.append(text[i])
                    i++
                }
            }
            // 3. Check for custom opening tags: [phoenix] or [discord]
            else if (text[i] == '[') {
                val closeIndex = text.indexOf(']', i)
                if (closeIndex != -1) {
                    val tagName = text.substring(i + 1, closeIndex)
                    val targetCode = ChromaticCodeRegistry.getByName(tagName)

                    if (targetCode != null) {
                        flushBuffer()
                        currentCode = targetCode
                        i = closeIndex + 1
                    } else {
                        // Not a valid registered code name, treat it as plain text syntax
                        textBuffer.append(text[i])
                        i++
                    }
                } else {
                    textBuffer.append(text[i])
                    i++
                }
            }
            // 4. Normal character payload
            else {
                textBuffer.append(text[i])
                i++
            }
        }

        flushBuffer()
        return result
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val x = c * (1f - abs((h * 6f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h * 6f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val ct = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * ct).toInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * ct).toInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * ct).toInt().coerceIn(0, 255)
        )
    }

    private data class Segment(val code: ChromaticCode, val text: String, val globalOffset: Int)
}
