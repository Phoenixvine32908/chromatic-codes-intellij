package net.phoenix.chromatic.preview

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.BoxLayout
import javax.swing.JPanel

class ShowChromaticPreviewFix(
    private val element: PsiElement,
    private val start: Int,
    private val end: Int,
) : IntentionAction {

    override fun getText() = "Preview Chromatic Codes formatting"
    override fun getFamilyName() = "Chromatic Codes"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
    override fun startInWriteAction() = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return

        // Walk up to find if we are inside a Java string literal token
        val stringLiteral = PsiTreeUtil.findFirstParent(element, false) {
            it.node?.elementType?.toString()?.contains("STRING_LITERAL") == true
        }

        val cleanText = if (stringLiteral != null) {
            val rawString = stringLiteral.text
            // Strip the enclosing leading and trailing " quotes safely
            if (rawString.startsWith("\"") && rawString.endsWith("\"") && rawString.length >= 2) {
                rawString.substring(1, rawString.length - 1)
            } else {
                rawString
            }
        } else {
            // Other languages (JSON, YAML, ...): the string leaf itself, so the code prefix is kept
            element.text.removeSurrounding("\"").removeSurrounding("'")
        }

        showPreviewPopup(cleanText, editor)
    }

    companion object {
        fun showPreviewPopup(text: String, editor: Editor) {
            val panel = ChromaticPreviewPanel(text)
            val wrapper = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(panel)
            }

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(wrapper, panel)
                .setTitle("Chromatic Preview")
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setMinSize(wrapper.preferredSize)
                .createPopup()

            popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                    panel.stop()
                }
            })

            popup.showInBestPositionFor(editor)
        }
    }
}