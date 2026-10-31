package net.phoenix.chromatic.renderer

import ChromaticCode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.*

class ChromaticGradientTextRenderer(private val code: ChromaticCode) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        val startOffset = highlighter.startOffset
        val endOffset = highlighter.endOffset

        val startPoint = editor.offsetToXY(startOffset)
        val endPoint = editor.offsetToXY(endOffset)

        val x = startPoint.x
        val y = startPoint.y
        val width = endPoint.x - x

        if (width <= 0) return

        val text = editor.document.getText(TextRange(startOffset, endOffset))

        val g2 = g as? Graphics2D ?: return
        val oldPaint = g2.paint
        val oldFont = g2.font

        // Save targeted font hints to bypass Kotlin Map property bugs safely
        val oldAntialiasing = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        val oldFractional = g2.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS)

        g2.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val stops = if (code.colors.size >= 2) code.colors
            else listOf(code.primaryColor ?: JBColor.RED, code.secondaryColor ?: JBColor.GREEN)

        g2.paint = LinearGradientPaint(
            x.toFloat(), y.toFloat(),
            x.toFloat() + width, y.toFloat(),
            FloatArray(stops.size) { it.toFloat() / (stops.size - 1) },
            stops.toTypedArray()
        )

        // FIX: Use editor.ascent instead of g2.fontMetrics.ascent to respect IDE line spacing
        g2.drawString(text, x, y + editor.ascent)

        // Clean up and restore styles safely
        g2.paint = oldPaint
        g2.font = oldFont
        if (oldAntialiasing != null) g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldAntialiasing)
        if (oldFractional != null) g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, oldFractional)
    }
}