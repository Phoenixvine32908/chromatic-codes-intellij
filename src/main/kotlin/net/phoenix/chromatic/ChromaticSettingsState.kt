package net.phoenix.chromatic

import ChromaticCode
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.io.File

@Service(Service.Level.PROJECT)
@State(name = "ChromaticCodesSettings", storages = [Storage("chromaticCodes.xml")])
class ChromaticSettingsState : PersistentStateComponent<ChromaticSettingsState.State> {

    data class State(
        var yamlConfigPath: String = "",
    )

    private var state = State()

    override fun getState() = state
    override fun loadState(s: State) { state = s }

    var yamlConfigPath: String
        get() = state.yamlConfigPath
        set(value) { state.yamlConfigPath = value }

    /**
     * Reload the registry. Priority:
     * 1. Explicit path set in settings
     * 2. Auto-detected phoenix_chromatic_colors_codes.yaml (or the older phoenix_chromatic_codes.yaml)
     *    in run/config, the project root, or src/main/resources
     * 3. Built-in defaults only
     */
    fun reloadRegistry(project: Project) {
        cachedAutoDetected = null
        val data = resolveConfigFile(project)?.let { readYaml(it) }
        ChromaticCodeRegistry.reload {
            // A half-broken config shouldn't discard the defaults
            if (data != null) runCatching { ChromaticCodeRegistry.loadFromModConfig(data) }
        }

        // Re-annotate all open files so changes appear immediately
        ApplicationManager.getApplication().invokeLater {
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /** Auto-detection can walk the project tree, so remember the result until the next reload. */
    @Volatile private var cachedAutoDetected: File? = null

    private fun resolveConfigFile(project: Project): File? {
        // 1. User-specified path
        val explicit = state.yamlConfigPath.trim()
        if (explicit.isNotEmpty()) {
            val f = File(explicit)
            if (f.exists()) return f
        }

        cachedAutoDetected?.takeIf { it.exists() }?.let { return it }
        return autoDetectConfigFile(project).also { cachedAutoDetected = it }
    }

    private fun autoDetectConfigFile(project: Project): File? {
        // 2. Auto-detect: walk the project base dirs looking for the mod config
        val basePath = project.basePath ?: return null
        val dirs = listOf(
            // Forge/Fabric default config output location when running in dev
            "$basePath/run/config",
            // Project root (in case someone copies it there)
            basePath,
            // src/main/resources (if it ships a default config)
            "$basePath/src/main/resources",
        )
        val names = listOf(
            "phoenix_chromatic_colors_codes.yaml",
            "phoenix_chromatic_colors_codes.yml",
            "phoenix_chromatic_codes.yaml",
            "phoenix_chromatic_codes.yml",
        )
        dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.exists() }?.let { return it }

        // Fall back to a shallow search (e.g. config/, run/<instance>/config/)
        val skip = setOf("build", ".gradle", ".git", ".idea", "node_modules", "out")
        return File(basePath).walkTopDown()
            .maxDepth(4)
            .onEnter { it.name !in skip }
            .firstOrNull { it.isFile && it.name in names }
    }

    /** Parsed YAML root, or null if the file is empty or malformed (defaults then stay in effect). */
    private fun readYaml(file: File): Map<*, *>? = try {
        Yaml().load<Any?>(file.readText()) as? Map<*, *>
    } catch (_: Exception) {
        null
    }

    private fun configDocument(project: Project): Document? {
        val file = resolveConfigFile(project) ?: return null
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return null
        return FileDocumentManager.getInstance().getDocument(vf)
    }

    /**
     * Rewrites the hex of the single-color YAML entry [code] came from. Returns an error message, or null on success.
     */
    fun updateColor(project: Project, code: ChromaticCode, color: Color): String? {
        val key = code.sourceKey
            ?: return "Only colors defined in the YAML config (customColors / namedColors) can be edited here."
        val doc = configDocument(project) ?: return "No chromatic codes config file was found."

        val keyPattern = if (code.sourceNamed) "\\[${Regex.escape(key)}]" else Regex.escape(key)
        val options = if (code.sourceNamed) setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE) else setOf(RegexOption.MULTILINE)
        val match = Regex("(^|[\\s\"'\\-])($keyPattern[\"']?\\s*:\\s*[\"']?#?)([0-9A-Fa-f]{6,8})", options)
            .find(doc.text) ?: return "Couldn't find the entry for '$key' in the config file."

        val hex = "%06X".format(color.rgb and 0xFFFFFF)
        val hexRange = match.groups[3]!!.range
        WriteCommandAction.runWriteCommandAction(project, "Change Chromatic Color", "Chromatic Codes", {
            doc.replaceString(hexRange.first, hexRange.last + 1, hex)
            FileDocumentManager.getInstance().saveDocument(doc)
        })
        reloadRegistry(project)
        return null
    }

    /**
     * Rewrites the first and last color of the gradient entry [code] came from
     * (`speed:speed:movement:HEX,HEX[,HEX...]`); middle stops are kept. Returns an error message, or null on success.
     */
    fun updateGradient(project: Project, code: ChromaticCode, start: Color, end: Color): String? {
        val key = code.sourceKey
            ?: return "Only gradients defined in the YAML config (customGradients / namedGradients) can be edited here."
        val doc = configDocument(project) ?: return "No chromatic codes config file was found."

        val keyPattern = if (code.sourceNamed) "\\[${Regex.escape(key)}]" else Regex.escape(key)
        val options = if (code.sourceNamed) setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE) else setOf(RegexOption.MULTILINE)
        val match = Regex(
            "(^|[\\s\"'\\-])($keyPattern[\"']?\\s*:\\s*[\"']?\\s*[^:\\n\"']*:[^:\\n\"']*:[^:\\n\"']*:)([^\"'\\n]+)",
            options
        ).find(doc.text) ?: return "Couldn't find the gradient entry for '$key' in the config file."

        val colorsGroup = match.groups[3]!!
        val tokens = colorsGroup.value.split(",").toMutableList()
        if (tokens.size < 2) return "The entry for '$key' doesn't list two colors."

        val hexRegex = Regex("[0-9A-Fa-f]{6,8}")
        fun hex(c: Color) = "%06X".format(c.rgb and 0xFFFFFF)
        if (!hexRegex.containsMatchIn(tokens.first()) || !hexRegex.containsMatchIn(tokens.last()))
            return "The entry for '$key' doesn't use hex colors."
        tokens[0] = tokens.first().replaceFirst(hexRegex, hex(start))
        tokens[tokens.lastIndex] = tokens.last().replaceFirst(hexRegex, hex(end))

        WriteCommandAction.runWriteCommandAction(project, "Change Chromatic Gradient", "Chromatic Codes", {
            doc.replaceString(colorsGroup.range.first, colorsGroup.range.last + 1, tokens.joinToString(","))
            FileDocumentManager.getInstance().saveDocument(doc)
        })
        reloadRegistry(project)
        return null
    }

    /**
     * Appends `[name]: <body>` (body = `speed:speed:movement:colors`) to namedGradients in the config file.
     * Returns an error message, or null on success.
     */
    fun addNamedEntry(project: Project, name: String, body: String): String? {
        if (!Regex("[A-Za-z0-9_]+").matches(name)) return "Name may only contain letters, digits and underscores."
        if (ChromaticCodeRegistry.isKnownName(name)) return "A code named '$name' already exists."
        val doc = configDocument(project)
            ?: return "No chromatic codes config file was found. Set one under Settings > Tools > Chromatic Codes."

        val entry = "\"[$name]: $body\""
        val lines = doc.text.lines()

        val keyIdx = lines.indexOfFirst { it.trimStart().startsWith("namedGradients:") }
        val insertAfter: Int
        val toInsert: String
        if (keyIdx >= 0) {
            if (lines[keyIdx].substringAfter("namedGradients:").isNotBlank())
                return "namedGradients in the config isn't a block list; add the entry manually."
            val indent = lines[keyIdx].takeWhile { it.isWhitespace() }
            val itemIndent = lines.getOrNull(keyIdx + 1)?.takeIf { it.trimStart().startsWith("- ") }
                ?.takeWhile { it.isWhitespace() } ?: "$indent  "
            insertAfter = keyIdx
            toInsert = "$itemIndent- $entry"
        } else {
            val colorsIdx = lines.indexOfFirst { it.startsWith("colors:") }
            if (colorsIdx >= 0) {
                insertAfter = colorsIdx
                toInsert = "  namedGradients:\n    - $entry"
            } else {
                insertAfter = lines.lastIndex
                toInsert = (if (lines.last().isEmpty()) "" else "\n") + "namedGradients:\n  - $entry"
            }
        }

        val offset = lines.take(insertAfter + 1).sumOf { it.length + 1 }.coerceAtMost(doc.textLength)
        val prefix = if (offset > 0 && doc.text[offset - 1] != '\n') "\n" else ""
        WriteCommandAction.runWriteCommandAction(project, "Add Chromatic Gradient", "Chromatic Codes", {
            doc.insertString(offset, "$prefix$toInsert\n")
            FileDocumentManager.getInstance().saveDocument(doc)
        })
        reloadRegistry(project)
        return null
    }

    /** Returns the file that would be used right now, or null if none found. */
    fun resolvedConfigFile(project: Project): File? = resolveConfigFile(project)

    companion object {
        fun getInstance(project: Project): ChromaticSettingsState =
            project.service<ChromaticSettingsState>()
    }
}
