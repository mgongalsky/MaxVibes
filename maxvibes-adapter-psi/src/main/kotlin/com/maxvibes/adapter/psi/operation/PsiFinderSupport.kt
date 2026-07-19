package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Shared low-level helpers for the PSI finders ([PsiUsagesFinder],
 * [PsiCallHierarchyFinder]): file-relative locations, display names,
 * super-declaration discovery.
 *
 * Everything here must be called under a read action.
 */

/** Location of a PSI element: project-relative path, 1-based line, column, trimmed line text. */
internal data class PsiLocation(
    val filePath: String,
    val line: Int,
    val column: Int,
    val lineText: String
)

/** Resolves [element] to a [PsiLocation]; null when the element has no file or offset. */
internal fun locate(project: Project, element: PsiElement): PsiLocation? {
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

    return PsiLocation(relativePath, line, column, lineText)
}

/** Wraps [name] in backticks when it contains whitespace (test-style function names). */
internal fun backtickIfNeeded(name: String): String =
    if (name.any { it.isWhitespace() }) "`$name`" else name

/** "Owner.name" for class members, "name" for top-level declarations; [fallback] when unnamed. */
internal fun declarationDisplayName(target: PsiElement, fallback: String): String {
    val named = target as? KtNamedDeclaration ?: return fallback
    val name = named.name ?: return fallback
    val owner = PsiTreeUtil.getParentOfType(named, KtClassOrObject::class.java)?.name
    return if (owner != null) "${backtickIfNeeded(owner)}.${backtickIfNeeded(name)}"
    else backtickIfNeeded(name)
}

/**
 * Super declarations of [fn] located in PROJECT sources, collected transitively
 * (Impl -> Port -> BasePort), each paired with its "Owner.name" label.
 *
 * External supers (stdlib / libraries, e.g. Runnable.run) are skipped: their
 * references cannot be attributed to this implementation, and searching them
 * project-wide only produces noise. The climb still continues through them,
 * so a project interface sitting above an external base class is found.
 *
 * For Kotlin supers the returned search target is the `navigationElement`
 * (the KtNamedFunction itself) — ReferencesSearch then matches Kotlin call sites.
 */
internal fun projectSuperDeclarations(project: Project, fn: KtNamedFunction): List<Pair<PsiElement, String>> {
    val scope = GlobalSearchScope.projectScope(project)
    val result = LinkedHashMap<PsiElement, String>()
    val seen = HashSet<PsiMethod>()
    val queue = ArrayDeque<PsiMethod>()
    queue.addAll(fn.toLightMethods())

    while (queue.isNotEmpty()) {
        val method = queue.removeFirst()
        for (superMethod in method.findSuperMethods()) {
            if (!seen.add(superMethod)) continue
            queue.add(superMethod) // climb transitively, even past external supers

            val declaration = superMethod.navigationElement ?: superMethod
            val virtualFile = declaration.containingFile?.virtualFile
                ?: superMethod.containingFile?.virtualFile
            if (virtualFile == null || !scope.contains(virtualFile)) continue // external — skip

            if (declaration !in result) {
                val owner = superMethod.containingClass?.name
                result[declaration] =
                    if (owner != null) "${backtickIfNeeded(owner)}.${backtickIfNeeded(superMethod.name)}"
                    else backtickIfNeeded(superMethod.name)
            }
        }
    }
    return result.map { it.key to it.value }
}