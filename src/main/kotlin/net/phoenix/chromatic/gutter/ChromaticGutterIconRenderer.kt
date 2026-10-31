package net.phoenix.chromatic.gutter

import ChromaticCode
import EffectType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import net.phoenix.chromatic.ChromaticSettingsState
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColorPickerListener
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale

import java.awt.*
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ChromaticGutterIconRenderer(
    private val element: PsiElement,
    private val code: ChromaticCode
) : GutterIconRenderer() {

    override fun getIcon(): Icon = ChromaticGradientIcon(code)

    override fun getTooltipText(): String = "Chromatic Code: ${code.displayName}"

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.CENTER

    override fun getClickAction(): AnAction? {
        return object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val project = element.project
                val settings = ChromaticSettingsState.getInstance(project)

                // Extract the UI element that triggered this click event (the gutter icon component)
                val parentComponent = e.inputEvent?.component ?: return

                fun report(error: String?) {
                    if (error != null) Messages.showInfoMessage(project, error, "Chromatic Codes")
                }

                if (isEditableGradient()) {
                    val dialog = GradientColorDialog(project, code.colors.first(), code.colors.last())
                    if (dialog.showAndGet()) {
                        report(settings.updateGradient(project, code, dialog.startColor, dialog.endColor))
                    }
                    return
                }

                val selectedColor = pickColor(project, parentComponent, "Adjust Chromatic Code Color", code.primaryColor ?: JBColor.WHITE)
                if (selectedColor != null) report(settings.updateColor(project, code, selectedColor))
            }
        }
    }

    /** Gradient defined in the YAML with at least two color stops: edit start and end together. */
    private fun isEditableGradient(): Boolean =
        code.sourceKey != null && code.effectType != EffectType.COLOR && code.colors.size >= 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChromaticGutterIconRenderer) return false
        return code == other.code && element == other.element
    }

    override fun hashCode(): Int {
        var result = element.hashCode()
        result = 31 * result + code.hashCode()
        return result
    }
}

private fun pickColor(project: Project, parent: Component, caption: String, initial: Color): Color? =
    ApplicationManager.getApplication().getService(ColorChooserService::class.java).showDialog(
        project, parent, caption, initial,
        false,                              // config hex is RGB, no opacity
        emptyList<ColorPickerListener>(),
        false
    )

/** Two-swatch editor for a gradient's start and end colors, with a live preview bar. */
private class GradientColorDialog(project: Project, start: Color, end: Color) : DialogWrapper(project) {
    var startColor: Color = start
        private set
    var endColor: Color = end
        private set

    private val bar = object : JComponent() {
        init { preferredSize = Dimension(JBUIScale.scale(260), JBUIScale.scale(24)) }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.paint = LinearGradientPaint(0f, 0f, width.toFloat(), 0f, floatArrayOf(0f, 1f), arrayOf(startColor, endColor))
            g2.fillRect(0, 0, width, height)
        }
    }

    private val startButton = JButton()
    private val endButton = JButton()

    init {
        title = "Edit Gradient Colors"
        startButton.addActionListener {
            pickColor(project, startButton, "Gradient Start Color", startColor)?.let { startColor = it; refresh() }
        }
        endButton.addActionListener {
            pickColor(project, endButton, "Gradient End Color", endColor)?.let { endColor = it; refresh() }
        }
        refresh()
        init()
    }

    private fun refresh() {
        startButton.text = "Start  #%06X".format(startColor.rgb and 0xFFFFFF)
        endButton.text = "End  #%06X".format(endColor.rgb and 0xFFFFFF)
        startButton.icon = swatch(startColor)
        endButton.icon = swatch(endColor)
        bar.repaint()
    }

    private fun swatch(color: Color): Icon = object : Icon {
        private val size = JBUIScale.scale(16)
        override fun getIconWidth() = size
        override fun getIconHeight() = size
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = color
            g.fillRect(x, y, size, size)
            g.color = Color.GRAY
            g.drawRect(x, y, size - 1, size - 1)
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(startButton); add(endButton) }, BorderLayout.NORTH)
        add(bar, BorderLayout.CENTER)
    }
}

class ChromaticGradientIcon(private val code: ChromaticCode) : Icon {

    override fun getIconWidth(): Int = JBUIScale.scale(14)
    override fun getIconHeight(): Int = JBUIScale.scale(14)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val c1 = code.primaryColor ?: JBColor.RED
            val c2 = code.secondaryColor ?: code.primaryColor ?: JBColor.GREEN

            // Renders the exact preview as a distinct square block gutter marker
            g2.paint = LinearGradientPaint(
                x.toFloat(), y.toFloat(),
                (x + iconWidth).toFloat(), y.toFloat(),
                floatArrayOf(0.0f, 1.0f),
                arrayOf(c1, c2)
            )
            g2.fillRect(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }
}