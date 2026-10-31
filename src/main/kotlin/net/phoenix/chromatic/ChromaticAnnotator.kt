package net.phoenix.chromatic

import ChromaticCode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.ui.Gray
import net.phoenix.chromatic.renderer.ChromaticGradientTextRenderer
import net.phoenix.chromatic.gutter.ChromaticGutterIconRenderer
import net.phoenix.chromatic.preview.ShowChromaticPreviewFix
import java.awt.Color
import java.awt.Font

class ChromaticAnnotator : Annotator {

    companion object {
        private val CHROMATIC_HIGHLIGHT_KEY = Key.create<Boolean>("net.phoenix.chromatic.gradient.highlighter")
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Leaves only: composite string nodes (JSON, Kotlin templates) would annotate the same text twice
        if (element.firstChild != null) return
        val type = element.elementType?.toString() ?: return
        if (!isStringLiteral(type)) return

        val text = element.text
        val offset = element.textRange.startOffset

        annotateCharCodes(text, offset, holder, element)
        annotateNamedCodes(text, offset, holder, element)
    }

    private fun isStringLiteral(type: String): Boolean =
        type == "STRING_LITERAL" || type == "REGULAR_STRING_PART" ||
                type == "string_content" || type.contains("STRING")

    private fun annotateCharCodes(text: String, offset: Int, holder: AnnotationHolder, element: PsiElement) {
        var i = 0
        var pendingCode: ChromaticCode? = null
        var segmentStart = -1

        fun flush(end: Int) {
            if (pendingCode != null && segmentStart != -1 && segmentStart < end) {
                applyAnnotation(holder, element, offset + segmentStart, offset + end, pendingCode!!)
            }
            pendingCode = null
            segmentStart = -1
        }

        while (i < text.length) {
            when {
                // Support both § and & formatting keys
                (text[i] == '§' || text[i] == '&') && i + 1 < text.length && text[i + 1] != '[' -> {
                    flush(i)
                    val ch = text[i + 1]
                    pendingCode = ChromaticCodeRegistry.getByChar(ch)
                    segmentStart = i + 2
                    i += 2
                }
                // Stop bounds if bracket tags start here
                (text[i] == '§' || text[i] == '&') && i + 1 < text.length && text[i + 1] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 2)
                    if (closeBracket != -1) {
                        val name = text.substring(i + 2, closeBracket)
                        if (ChromaticCodeRegistry.isKnownName(name) || name.startsWith("/")) {
                            flush(i)
                            i = closeBracket + 1
                            segmentStart = i
                            continue
                        }
                    }
                    i++
                }
                else -> i++
            }
        }

        if (pendingCode != null && segmentStart != -1 && segmentStart < text.length) {
            applyAnnotation(holder, element, offset + segmentStart, offset + text.length, pendingCode!!)
        }
    }

    private fun annotateNamedCodes(text: String, offset: Int, holder: AnnotationHolder, element: PsiElement) {
        val dimAttributes = TextAttributes().apply { foregroundColor = Gray._150 }
        val coveredRanges = mutableListOf<IntRange>()

        // Explicit closing variant matching either prefix: &[name]...&[/name]
        val closedRegex = Regex("[&§]\\[([a-zA-Z0-9_*^$\\-]+)]([^&§\\[]*)[&§]\\[/\\1]")

        closedRegex.findAll(text).forEach { match ->
            val tagName = match.groups[1]?.value ?: return@forEach
            val innerGroup = match.groups[2] ?: return@forEach
            val code = ChromaticCodeRegistry.getByName(tagName) ?: return@forEach

            val contentStart = offset + innerGroup.range.first
            val contentEnd   = offset + innerGroup.range.last + 1
            if (contentStart < contentEnd)
                applyAnnotation(holder, element, contentStart, contentEnd, code)

            val openEnd   = offset + innerGroup.range.first
            val closeStart = offset + innerGroup.range.last + 1
            val closeEnd   = offset + match.range.last + 1
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(offset + match.range.first, openEnd)).enforcedTextAttributes(dimAttributes).create()
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(closeStart, closeEnd)).enforcedTextAttributes(dimAttributes).create()

            coveredRanges += match.range
        }

        // Open-ended variant matching either prefix: &[name]text
        val openRegex = Regex("[&§]\\[([a-zA-Z0-9_*^$\\-]+)]([^&§\\[]*)")
        openRegex.findAll(text).forEach { match ->
            if (coveredRanges.any { match.range.first in it }) return@forEach

            val tagName    = match.groups[1]?.value ?: return@forEach
            val innerGroup = match.groups[2] ?: return@forEach
            val code = ChromaticCodeRegistry.getByName(tagName) ?: return@forEach

            val tagEnd       = offset + innerGroup.range.first
            val contentStart = tagEnd
            val contentEnd   = offset + innerGroup.range.last + 1

            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(offset + match.range.first, tagEnd)).enforcedTextAttributes(dimAttributes).create()
            if (contentStart < contentEnd)
                applyAnnotation(holder, element, contentStart, contentEnd, code)
        }
    }

    private fun applyAnnotation(holder: AnnotationHolder, element: PsiElement, start: Int, end: Int, code: ChromaticCode) {
        if (start >= end) return
        val range = TextRange(start, end)
        val isGradient = code.effectType in listOf(
            EffectType.GRADIENT, EffectType.VERTICAL_GRADIENT,
            EffectType.MIRRORED_GRADIENT, EffectType.DISCORD_GRADIENT, EffectType.PHOENIX
        ) || (code.isMovementOnly && code.colors.size >= 2)

        val color = if (isGradient) Color(0, 0, 0, 0) else resolveDisplayColor(code)
        val attrs = TextAttributes(color, null, null, null, Font.PLAIN)

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .enforcedTextAttributes(attrs)
            .withFix(ShowChromaticPreviewFix(element, start - element.textRange.startOffset, end - element.textRange.startOffset))
            .gutterIconRenderer(ChromaticGutterIconRenderer(element, code))
            .create()

        if (isGradient) {
            val elementRange = element.textRange
            ApplicationManager.getApplication().invokeLater {
                val editor = FileEditorManager.getInstance(element.project).selectedTextEditor ?: return@invokeLater
                if (editor.document == element.containingFile.viewProvider.document) {
                    val markupModel = editor.markupModel

                    markupModel.allHighlighters.forEach { oldHighlighter ->
                        if (oldHighlighter.getUserData(CHROMATIC_HIGHLIGHT_KEY) == true &&
                            oldHighlighter.startOffset >= elementRange.startOffset &&
                            oldHighlighter.endOffset <= elementRange.endOffset) {
                            markupModel.removeHighlighter(oldHighlighter)
                        }
                    }

                    val highlighter = markupModel.addRangeHighlighter(
                        start, end, HighlighterLayer.SYNTAX + 2,
                        TextAttributes().apply { foregroundColor = Color(0, 0, 0, 0) },
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    highlighter.putUserData(CHROMATIC_HIGHLIGHT_KEY, true)
                    highlighter.customRenderer = ChromaticGradientTextRenderer(code)
                }
            }
        }
    }

    private fun resolveDisplayColor(code: ChromaticCode): Color {
        return when (code.effectType) {
            EffectType.COLOR          -> code.primaryColor ?: Color.WHITE
            EffectType.GRADIENT,
            EffectType.VERTICAL_GRADIENT,
            EffectType.MIRRORED_GRADIENT,
            EffectType.DISCORD_GRADIENT -> midpoint(code.primaryColor, code.secondaryColor)
            EffectType.RAINBOW,
            EffectType.CHROMA_WAVE     -> Color(255, 100, 100)
            EffectType.WAVE            -> code.primaryColor ?: Color(100, 200, 255)
            EffectType.SHAKE           -> code.primaryColor ?: Color(255, 180, 80)
            EffectType.WOBBLE, EffectType.STRETCH, EffectType.COMPRESS, EffectType.SCALE,
            EffectType.VERTICAL_SHEAR, EffectType.CUSTOM_ITALIC, EffectType.LETTER_SPACING
                                       -> code.primaryColor ?: Color(200, 200, 200)
            EffectType.PULSE           -> code.primaryColor ?: Color(255, 140, 0)
            EffectType.BREATH          -> code.primaryColor ?: Color(100, 180, 255)
            EffectType.GLITCH,
            EffectType.CHROMA_GLITCH   -> Color(0, 255, 80)
            EffectType.MATRIX_RAIN,
            EffectType.MATRIX_SPIN     -> Color(0, 255, 65)
            EffectType.PHOENIX         -> Color(255, 140, 0)
            EffectType.LIQUID_PLASMA   -> Color(180, 0, 255)
            EffectType.TEXT_OUTLINE    -> Color.WHITE
            else                       -> Color(200, 200, 200)
        }
    }

    private fun midpoint(a: Color?, b: Color?): Color {
        if (a == null && b == null) return Color.WHITE
        if (a == null) return b!!
        if (b == null) return a
        return Color((a.red + b.red) / 2, (a.green + b.green) / 2, (a.blue + b.blue) / 2)
    }
}