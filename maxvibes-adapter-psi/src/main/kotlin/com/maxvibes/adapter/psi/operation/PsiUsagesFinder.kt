package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Semantic Find Usages for [com.maxvibes.domain.model.code.CodeGranularity.USAGES].
 *
 * Wraps [ReferencesSearch] (the engine behind the IDE Find Usages action) and
 * renders the results as a flat, file-grouped text block ready to be embedded
 * in an LLM prompt. Search scope is the whole project, so usages are found
 * across module boundaries.
 *
 * When the target is a function with super declarations in project sources
 * (ports, base classes), usages of those supers are included too, tagged
 * `[via Owner.fn]`: a call through the interface is a real dependency of the
 * implementation. Without this, `ProcessCommandRunner.run` reports "no usages"
 * while every call goes through `CommandRunnerPort.run`, turning the mandatory
 * pre-DELETE_ELEMENT check into a false "safe to remove".
 *
 * Overloads are excluded by construction: [ReferencesSearch] only returns
 * references that resolve to the exact element passed in. The declaration
 * itself is never part of the result either.
 *
 * Must be called under a read action (getCodeView already provides one).
 */
class PsiUsagesFinder(private val project: Project) {

    /**
     * Finds all references to [target] (and, for functions, its project-scope
     * super declarations) in project scope and renders them.
     *
     * @param target       resolved declaration to search for (never a whole file)
     * @param fallbackName used in the header when [target] has no name
     */
    fun renderUsages(target: PsiElement, fallbackName: String): String {
        if (DumbService.isDumb(project)) {
            error("Usage search is unavailable while indexes are being rebuilt - retry in a moment")
        }

        val displayName = declarationDisplayName(target, fallbackName)
        val scope = GlobalSearchScope.projectScope(project)

        val searchTargets = mutableListOf<Pair<PsiElement, String?>>(target to null)
        if (target is KtNamedFunction) {
            projectSuperDeclarations(project, target).forEach { (declaration, label) ->
                searchTargets.add(declaration to label)
            }
        }

        val usages = searchTargets
            .flatMap { (searchTarget, via) ->
                ReferencesSearch.search(searchTarget, scope).findAll()
                    .mapNotNull { toUsage(it.element, via) }
            }
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
            if (usage.via != null) sb.append("[via ").append(usage.via).append("] ")
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
        val via: String?,
        val text: String
    )

    private fun toUsage(element: PsiElement, via: String?): Usage? {
        val location = locate(project, element) ?: return null
        return Usage(location.filePath, location.line, location.column, tagFor(element), via, location.lineText)
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
        return "[in ${backtickIfNeeded(name)}]"
    }

    private companion object {
        const val MAX_USAGES = 50
    }
}