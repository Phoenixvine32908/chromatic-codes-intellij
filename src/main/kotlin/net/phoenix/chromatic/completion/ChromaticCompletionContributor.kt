package net.phoenix.chromatic.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import net.phoenix.chromatic.gutter.ChromaticGradientIcon

class ChromaticCompletionContributor : CompletionContributor() {
    init {
        // Match universally to avoid strict compile-time language class dependencies
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    // 1. Language-agnostic tree walk to check if caret is inside a string expression context
                    var isInsideString = false
                    var current: PsiElement? = parameters.position

                    while (current != null && current !is PsiFile) {
                        val typeStr = current.node?.elementType?.toString() ?: ""
                        if (typeStr.contains("STRING") || typeStr.contains("LITERAL")) {
                            isInsideString = true
                            break
                        }
                        current = current.parent
                    }

                    // Exit early if the user is typing outside a string literal block
                    if (!isInsideString) return

                    // 2. Perform cursor parsing and code completion payload generation
                    val text = parameters.position.text
                    val cursorOffsetInElement = parameters.offset - parameters.position.textRange.startOffset

                    if (cursorOffsetInElement < 0 || cursorOffsetInElement > text.length) return

                    val prefixToCursor = text.substring(0, cursorOffsetInElement)
                    val bracketIndex = maxOf(prefixToCursor.lastIndexOf("&["), prefixToCursor.lastIndexOf("§["))

                    if (bracketIndex != -1 && !prefixToCursor.substring(bracketIndex).contains("]")) {
                        val customPrefix = prefixToCursor.substring(bracketIndex + 2)
                        val resultSet = result.withPrefixMatcher(customPrefix)

                        ChromaticCodeRegistry.allNamedCodes().forEach { (name, code) ->
                            resultSet.addElement(
                                LookupElementBuilder.create(name)
                                    .withTypeText(code.displayName)
                                    .withTailText(" (Chromatic Effect)", true)
                                    .withIcon(ChromaticGradientIcon(code))
                                    .withInsertHandler { ctx, _ ->
                                        val offset = ctx.tailOffset
                                        ctx.document.insertString(offset, "]")
                                        ctx.editor.caretModel.moveToOffset(offset + 1)
                                    }
                            )
                        }
                    }
                }
            }
        )
    }
}