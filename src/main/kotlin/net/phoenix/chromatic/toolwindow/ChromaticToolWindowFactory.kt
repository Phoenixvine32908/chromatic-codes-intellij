package net.phoenix.chromatic.toolwindow

import ChromaticCode
import EffectType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColorPickerListener
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import net.phoenix.chromatic.ChromaticSettingsState
import net.phoenix.chromatic.preview.ChromaticPreviewPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChromaticToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChromaticGeneratorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private const val BLANK_PRESET = "(blank)"

private class SwatchIcon(var color: Color) : Icon {
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

private fun hex(c: Color) = "%06X".format(c.rgb and 0xFFFFFF)

/**
 * Builds a chromatic string: pick a movement and colors (solid, gradient or rainbow), name it, and insert
 * `&[name]text` at the caret. A name that already exists in the registry is inserted as-is; a new name is
 * saved to the YAML config first so the mod can resolve it.
 */
class ChromaticGeneratorPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var startColor = Color(0xFF0055)
    private var endColor = Color(0x00FFFF)

    init {
        val previewPanel = ChromaticPreviewPanel("")

        val presetCombo = ComboBox((listOf(BLANK_PRESET) + ChromaticCodeRegistry.allNamedCodes().keys.sorted()).toTypedArray())
        val nameInput = JBTextField("my_effect")
        val movementCombo = ComboBox(ChromaticCodeRegistry.movementIds.toTypedArray())
        val rainbowCheck = JBCheckBox("Rainbow colors")
        val gradientCheck = JBCheckBox("Gradient (blend to end color)", true)
        val startIcon = SwatchIcon(startColor)
        val endIcon = SwatchIcon(endColor)
        val startButton = JButton("Start", startIcon)
        val endButton = JButton("End", endIcon)
        val colorSpeedInput = JBTextField("1.0", 4)
        val moveSpeedInput = JBTextField("1.0", 4)
        val textInput = JBTextField("Preview Text")
        val hint = JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
        val insertButton = JButton("Insert at Caret").apply {
            toolTipText = "Injects this formatted string directly into your open file"
        }

        /** The `speed:speed:movement:colors` body for the current controls. */
        fun entryBody(): String {
            val colors = when {
                rainbowCheck.isSelected -> "rainbow"
                gradientCheck.isSelected -> "${hex(startColor)},${hex(endColor)}"
                else -> hex(startColor)
            }
            val cs = colorSpeedInput.text.trim().toFloatOrNull() ?: 1f
            val ms = moveSpeedInput.text.trim().toFloatOrNull() ?: 1f
            return "$cs:$ms:${movementCombo.selectedItem}:$colors"
        }

        val updatePreview = {
            startButton.isEnabled = !rainbowCheck.isSelected
            gradientCheck.isEnabled = !rainbowCheck.isSelected
            endButton.isEnabled = !rainbowCheck.isSelected && gradientCheck.isSelected

            val name = nameInput.text.trim()
            hint.text = if (ChromaticCodeRegistry.isKnownName(name))
                "'$name' already exists: inserting the existing code."
            else "New name: will be saved to your config on insert."

            val code = ChromaticCodeRegistry.codeFromEntry(entryBody())
            val previewTag = "__generator_preview__"
            if (code != null) ChromaticCodeRegistry.registerNamed(previewTag, code)
            // The panel parses on assignment, so the temporary tag can be dropped straight away.
            previewPanel.previewText = "&[$previewTag]${textInput.text}"
            ChromaticCodeRegistry.unregisterNamed(previewTag)
        }

        fun pickColor(current: Color, icon: SwatchIcon, button: JButton, assign: (Color) -> Unit) {
            val picked = ApplicationManager.getApplication().getService(ColorChooserService::class.java).showDialog(
                project, button, "Choose Color", current, false, emptyList<ColorPickerListener>(), false
            ) ?: return
            assign(picked)
            icon.color = picked
            button.repaint()
            updatePreview()
        }

        startButton.addActionListener { pickColor(startColor, startIcon, startButton) { startColor = it } }
        endButton.addActionListener { pickColor(endColor, endIcon, endButton) { endColor = it } }

        // Loading a preset copies its movement and colors into the controls and adopts its name.
        presetCombo.addActionListener {
            val name = presetCombo.selectedItem as? String ?: return@addActionListener
            val code = ChromaticCodeRegistry.getByName(name) ?: return@addActionListener
            nameInput.text = name
            movementCombo.selectedItem = ChromaticCodeRegistry.movementIdFor(code.effectType)
            rainbowCheck.isSelected = code.rainbow || code.effectType == EffectType.RAINBOW || code.effectType == EffectType.CHROMA_WAVE
            code.colors.getOrNull(0)?.let { startColor = it; startIcon.color = it }
            gradientCheck.isSelected = code.colors.size >= 2
            code.colors.lastOrNull()?.takeIf { code.colors.size >= 2 }?.let { endColor = it; endIcon.color = it }
            colorSpeedInput.text = code.colorSpeed.toString()
            moveSpeedInput.text = code.moveSpeed.toString()
            startButton.repaint()
            endButton.repaint()
            updatePreview()
        }

        val refresh = { updatePreview() }
        movementCombo.addActionListener { refresh() }
        rainbowCheck.addActionListener { refresh() }
        gradientCheck.addActionListener { refresh() }
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
        }
        listOf(nameInput, colorSpeedInput, moveSpeedInput, textInput).forEach { it.document.addDocumentListener(docListener) }

        insertButton.addActionListener {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@addActionListener
            val name = nameInput.text.trim()

            if (!ChromaticCodeRegistry.isKnownName(name)) {
                val error = ChromaticSettingsState.getInstance(project).addNamedEntry(project, name, entryBody())
                if (error != null) {
                    Messages.showErrorDialog(project, error, "Chromatic Codes")
                    return@addActionListener
                }
            }

            val stringToInsert = "&[$name]${textInput.text}"
            val document = editor.document
            val offset = editor.caretModel.offset
            WriteCommandAction.runWriteCommandAction(project, "Insert Chromatic String", "Chromatic Codes", {
                document.insertString(offset, stringToInsert)
                editor.caretModel.moveToOffset(offset + stringToInsert.length)
            })
        }

        val colorRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(startButton)
            add(endButton)
        }
        val speedRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Color"))
            add(colorSpeedInput)
            add(JBLabel("Move"))
            add(moveSpeedInput)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Load preset:"), presetCombo)
            .addLabeledComponent(JBLabel("Name:"), nameInput)
            .addLabeledComponent(JBLabel("Movement:"), movementCombo)
            .addComponent(rainbowCheck)
            .addComponent(gradientCheck)
            .addLabeledComponent(JBLabel("Colors:"), colorRow)
            .addLabeledComponent(JBLabel("Speeds:"), speedRow)
            .addLabeledComponent(JBLabel("Text:"), textInput)
            .addComponent(hint)
            .addVerticalGap(8)
            .addComponent(previewPanel)
            .addVerticalGap(8)
            .addComponent(insertButton)
            .panel.apply { border = JBUI.Borders.empty(10) }

        updatePreview()
        add(form, BorderLayout.NORTH)
    }
}
