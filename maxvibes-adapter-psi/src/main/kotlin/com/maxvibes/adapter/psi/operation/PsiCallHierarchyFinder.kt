package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/**
 * Call-hierarchy trees for [com.maxvibes.domain.model.code.CodeGranularity.CALLERS]:
 * the programmatic analogue of the IDE Call Hierarchy action, rendered as a
 * compact ASCII tree for the LLM prompt.
 *
 * BFS upward from the target function: shallow levels are completed before deep
 * ones, so when the node budget runs out it is the distant callers that get cut,
 * never the direct ones. Calls that go through super declarations (ports,
 * base classes) are found via [projectSuperDeclarations] and tagged `(via Owner.fn)`.
 *
 * Must be called under a read action (getCodeView already provides one).
 */
class PsiCallHierarchyFinder(private val project: Project) {

    fun renderCallers(target: KtNamedFunction, fallbackName: String): String {
        if (DumbService.isDumb(project)) {
            error("Call hierarchy is unavailable while indexes are being rebuilt - retry in a moment")
        }

        val displayName = declarationDisplayName(target, fallbackName)

        val root = Node(label = displayName, location = null, function = target)
        val visited = HashSet<PsiElement>().apply { add(target) }
        var budget = MAX_NODES

        val queue = ArrayDeque<Pair<Node, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            val fn = node.function ?: continue
            val groups = findCallerGroups(fn)
            if (groups.isEmpty()) continue
            if (depth >= MAX_DEPTH) {
                node.deeperExists = true
                continue
            }
            for (group in groups) {
                if (budget <= 0) {
                    node.omitted++
                    continue
                }
                budget--
                val callerFn = group.function
                val repeated = callerFn != null && !visited.add(callerFn)
                val child = Node(
                    label = group.label,
                    location = group.location,
                    function = if (repeated) null else callerFn,
                    callSiteLines = group.lines,
                    via = group.via,
                    shownAbove = repeated
                )
                node.children.add(child)
                if (child.function != null) queue.add(child to depth + 1)
            }
        }

        if (root.children.isEmpty() && root.omitted == 0) {
            return "callers of $displayName — no callers found in project scope"
        }

        val sb = StringBuilder()
        sb.append("callers of ").append(displayName)
            .append(" — depth ≤ ").append(MAX_DEPTH).append(", project scope:\n")
        renderChildren(root, "", sb)
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------

    private class Node(
        val label: String,
        val location: PsiLocation?,
        val function: KtNamedFunction?,
        val callSiteLines: List<Int> = emptyList(),
        val via: String? = null,
        val shownAbove: Boolean = false
    ) {
        val children = mutableListOf<Node>()
        var deeperExists = false
        var omitted = 0
    }

    private class CallerGroup(
        val label: String,
        val location: PsiLocation?,
        val lines: List<Int>,
        val via: String?,
        val function: KtNamedFunction?
    )

    private class RawHit(
        val containerKey: PsiElement,
        val containerFn: KtNamedFunction?,
        val label: String,
        val location: PsiLocation?,
        val via: String?
    )

    /** All callers of [fn], grouped by containing declaration, sorted by file/line. */
    private fun findCallerGroups(fn: KtNamedFunction): List<CallerGroup> {
        val scope = GlobalSearchScope.projectScope(project)

        val searchTargets = mutableListOf<Pair<PsiElement, String?>>(fn to null)
        projectSuperDeclarations(project, fn).forEach { (declaration, label) ->
            searchTargets.add(declaration to label)
        }

        val hits = mutableListOf<RawHit>()
        for ((searchTarget, via) in searchTargets) {
            for (reference in ReferencesSearch.search(searchTarget, scope).findAll()) {
                val element = reference.element
                // imports and KDoc links are not call sites
                if (PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java) != null) continue
                if (PsiTreeUtil.getParentOfType(element, KDoc::class.java) != null) continue

                hits.add(toRawHit(element, via))
            }
        }

        return hits
            .groupBy { it.containerKey }
            .map { (_, group) ->
                val first = group.minByOrNull { it.location?.line ?: Int.MAX_VALUE } ?: group.first()
                // `via` is shown only when EVERY call site in the group goes through
                // a super declaration; one direct call makes the edge certain anyway.
                val vias = group.mapNotNull { it.via }.distinct()
                val via = if (group.any { it.via == null } || vias.isEmpty()) null else vias.joinToString(", ")
                CallerGroup(
                    label = first.label,
                    location = first.location,
                    lines = group.mapNotNull { it.location?.line }.distinct().sorted(),
                    via = via,
                    function = first.containerFn
                )
            }
            .sortedWith(compareBy({ it.location?.filePath ?: "" }, { it.location?.line ?: 0 }))
    }

    /** Resolves the declaration containing a call site; non-function containers become terminal leaves. */
    private fun toRawHit(element: PsiElement, via: String?): RawHit {
        val location = locate(project, element)

        val fn = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        if (fn != null) {
            return RawHit(fn, fn, declarationDisplayName(fn, fn.name ?: "?"), location, via)
        }

        val accessor = PsiTreeUtil.getParentOfType(element, KtPropertyAccessor::class.java)
        if (accessor != null) {
            val property = accessor.property
            val kind = if (accessor.isGetter) "getter" else "setter"
            return RawHit(
                accessor, null,
                "${declarationDisplayName(property, property.name ?: "?")} ($kind)",
                location, via
            )
        }

        val initializer = PsiTreeUtil.getParentOfType(element, KtAnonymousInitializer::class.java)
        if (initializer != null) {
            val owner = PsiTreeUtil.getParentOfType(initializer, KtClassOrObject::class.java)?.name ?: "?"
            return RawHit(initializer, null, "<init> of ${backtickIfNeeded(owner)}", location, via)
        }

        val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)
        if (property != null) {
            return RawHit(
                property, null,
                "${declarationDisplayName(property, property.name ?: "?")} (property initializer)",
                location, via
            )
        }

        val file = element.containingFile
        return RawHit(file ?: element, null, "(file level)", location, via)
    }

    // ------------------------------------------------------------------

    private fun renderChildren(node: Node, prefix: String, sb: StringBuilder) {
        node.children.forEachIndexed { index, child ->
            val last = index == node.children.lastIndex && node.omitted == 0
            sb.append(prefix).append(if (last) "└─ " else "├─ ").append(nodeLine(child)).append('\n')
            renderChildren(child, prefix + if (last) "   " else "│  ", sb)
        }
        if (node.omitted > 0) {
            sb.append(prefix).append("└─ …and ").append(node.omitted)
                .append(" more callers of ").append(node.label).append(" omitted\n")
        }
    }

    private fun nodeLine(node: Node): String {
        val sb = StringBuilder(node.label)
        val loc = node.location
        if (loc != null) sb.append(" (").append(loc.filePath).append(':').append(loc.line).append(')')
        if (node.callSiteLines.size > 1) {
            sb.append(" (").append(node.callSiteLines.size).append(" call sites: lines ")
                .append(node.callSiteLines.joinToString(", ")).append(')')
        }
        if (node.via != null) sb.append(" (via ").append(node.via).append(')')
        if (node.shownAbove) sb.append(" (shown above)")
        if (node.deeperExists) sb.append(" …▸ deeper callers exist — request CALLERS on this function")
        return sb.toString()
    }

    private companion object {
        const val MAX_DEPTH = 3
        const val MAX_NODES = 40
    }
}