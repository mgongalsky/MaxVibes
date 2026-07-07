package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Semantic Find Usages for [com.maxvibes.domain.model.code.CodeGranularity.USAGES].
 *
 * Wraps [ReferencesSearch] (the engine behind the IDE Find Usages action) and
 * renders the results as a flat, file-grouped text block ready to be embedded
 * in an LLM prompt. Search scope is the whole project, so usages are found
 * across module boundaries.
 *
 * Overloads are excluded by construction: [ReferencesSearch] only returns
 * references that resolve to the exact element passed in. The declaration
 * itself is never part of the result either.
 *
 * Must be called under a read action (getCodeView already provides one).
 */
class PsiUsagesFinder(private val project: Project) {

    /**
     * Finds all references to [target] in project scope and renders them.
     *
     * @param target       resolved declaration to search for (never a whole file)
     * @param fallbackName used in the header when [target] has no name
     */
    fun renderUsages(target: PsiElement, fallbackName: String): String {
        if (DumbService.isDumb(project)) {
            error("Usage search is unavailable while indexes are being rebuilt - retry in a moment")
        }

        val displayName = displayName(target, fallbackName)

        val usages = ReferencesSearch
            .search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .mapNotNull { toUsage(it.element) }
            .sortedWith(compareBy({ it.filePath }, { it.line }, { it.column }))

        if (usages.isEmpty()) {
            return "usages of $displayName - no usages found in project scope"
        }

        val shown = usages.take(MAX_USAGES)
        val omitted = usages.drop(MAX_USAGES)
        val totalFiles = usages.mapTo(HashSet()) { it.filePath }.size

        val sb = StringBuilder()
        sb.append("usages of ").append(displayName)
            .append(" - ").append(usages.size).append(" usage(s) in ")
            .append(totalFiles).append(" file(s):\n")

        var currentFile: String? = null
        for (usage in shown) {
            if (usage.filePath != currentFile) {
                currentFile = usage.filePath
                sb.append('\n').append(usage.filePath).append('\n')
            }
            sb.append("  ").append(usage.line).append(": ")
            if (usage.tag != null) sb.append(usage.tag).append(' ')
            sb.append(usage.text).append('\n')
        }

        if (omitted.isNotEmpty()) {
            val omittedFiles = omitted.mapTo(HashSet()) { it.filePath }.size
            sb.append("\n...and ").append(omitted.size)
                .append(" more in ").append(omittedFiles).append(" file(s)\n")
        }

        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------

    private data class Usage(
        val filePath: String,
        val line: Int,
        val column: Int,
        val tag: String?,
        val text: String
    )

    private fun displayName(target: PsiElement, fallback: String): String {
        val named = target as? KtNamedDeclaration ?: return fallback
        val name = named.name ?: return fallback
        val owner = PsiTreeUtil.getParentOfType(named, KtClassOrObject::class.java)?.name
        return if (owner != null) "$owner.$name" else name
    }

    private fun toUsage(element: PsiElement): Usage? {
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null

        val basePath = project.basePath?.trimEnd('/')
        val absolutePath = virtualFile.path
        val relativePath =
            if (basePath != null && absolutePath.startsWith(basePath))
                absolutePath.removePrefix(basePath).trimStart('/')
            else absolutePath

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: psiFile.viewProvider.document
        val offset = element.textRange?.startOffset ?: return null

        val line: Int
        val column: Int
        val lineText: String
        if (document != null && offset <= document.textLength) {
            val lineIndex = document.getLineNumber(offset)
            line = lineIndex + 1
            column = offset - document.getLineStartOffset(lineIndex)
            lineText = document.charsSequence
                .subSequence(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
                .toString().trim()
        } else {
            line = 0
            column = 0
            lineText = element.text.lineSequence().firstOrNull()?.trim().orEmpty()
        }

        return Usage(relativePath, line, column, tagFor(element), lineText)
    }

    /** "[import]" for import directives, "[in X]" for the closest named container. */
    private fun tagFor(element: PsiElement): String? {
        if (PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java) != null) {
            return "[import]"
        }
        var container = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java)
        while (container != null && (container.name == null || container is KtParameter)) {
            container = PsiTreeUtil.getParentOfType(container, KtNamedDeclaration::class.java)
        }
        val name = container?.name ?: return null
        val display = if (name.any { it.isWhitespace() }) "`$name`" else name
        return "[in $display]"
    }

    private companion object {
        const val MAX_USAGES = 50
    }
}