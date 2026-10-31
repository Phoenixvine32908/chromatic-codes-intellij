package net.phoenix.chromatic

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JPanel

class ChromaticSettingsConfigurable(private val project: Project) : Configurable {

    private var pathField: TextFieldWithBrowseButton? = null
    private var statusLabel: JBLabel? = null

    override fun getDisplayName() = "Chromatic Codes"

    override fun createComponent(): JComponent {
        val settings = ChromaticSettingsState.getInstance(project)

        pathField = TextFieldWithBrowseButton().apply {
            text = settings.yamlConfigPath
            addBrowseFolderListener(
                "Select phoenix_chromatic_colors_codes.yaml",
                "Point to your mod's config file so the plugin can read your registered codes.",
                project,
                FileChooserDescriptorFactory.createSingleFileDescriptor("yaml")
                    .withFileFilter { it.name.endsWith(".yaml") || it.name.endsWith(".yml") }
            )
        }

        statusLabel = JBLabel(resolvedStatusText(settings)).apply {
            foreground = Color(130, 130, 130)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Config file path (leave blank to auto-detect):"),
                pathField!!,
                true
            )
            .addComponent(statusLabel!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = ChromaticSettingsState.getInstance(project)
        return pathField?.text?.trim() != settings.yamlConfigPath
    }

    override fun apply() {
        val settings = ChromaticSettingsState.getInstance(project)
        settings.yamlConfigPath = pathField?.text?.trim() ?: ""
        settings.reloadRegistry(project)
        statusLabel?.text = resolvedStatusText(settings)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        val settings = ChromaticSettingsState.getInstance(project)
        pathField?.text = settings.yamlConfigPath
        statusLabel?.text = resolvedStatusText(settings)
    }

    private fun resolvedStatusText(settings: ChromaticSettingsState): String {
        val resolved = settings.resolvedConfigFile(project)
        return if (resolved != null) "Using: ${resolved.path}" else "No config found — using built-in defaults only"
    }
}
