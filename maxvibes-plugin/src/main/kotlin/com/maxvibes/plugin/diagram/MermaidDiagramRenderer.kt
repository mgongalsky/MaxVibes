package com.maxvibes.plugin.diagram

import com.maxvibes.application.port.output.DiagramRenderer
import com.maxvibes.domain.model.planning.DiagramNode
import com.maxvibes.domain.model.planning.PlanDiagram

/**
 * Renders a [PlanDiagram] into Mermaid `flowchart TD` source.
 *
 * Pure Kotlin, zero IntelliJ Platform imports — unit-testable without an IDE.
 *
 * Mapping rules:
 * - group → `subgraph`; nested groups are flattened to one level (Mermaid
 *   limitation), announced with a `%%` comment rather than silently;
 * - nodes outside any group are emitted at the top level;
 * - regular edge → solid arrow; seam-crossing edge → dashed arrow plus a red
 *   `linkStyle`. Mermaid addresses `linkStyle` by ordinal link index in the
 *   output, so indices are collected in the same pass that emits the arrows;
 * - edges with an unknown endpoint are skipped with a comment — otherwise
 *   Mermaid would silently auto-create ghost nodes and shift link indices;
 * - every id is sanitized to `[A-Za-z0-9_]`, prefixed (`n_`/`g_`) to dodge
 *   Mermaid reserved words and leading digits, and de-collided; all label
 *   text is entity-escaped. Markup we add ourselves (`<i>`, `<br/>`) is
 *   appended after escaping so user content can never inject markup.
 */
class MermaidDiagramRenderer : DiagramRenderer {

    override val id: String = "mermaid"

    override val displayName: String = "Mermaid"

    override fun render(diagram: PlanDiagram): String {
        val sb = StringBuilder()
        diagram.title?.takeIf { it.isNotBlank() }?.let { title ->
            sb.append("---\ntitle: \"").append(oneLine(title).replace("\"", "'")).append("\"\n---\n")
        }
        sb.append("flowchart TD\n")

        val nodesById = diagram.nodes.associateBy { it.id }
        val nodeIds = assignMermaidIds(diagram.nodes.map { it.id }, "n_")
        val groupIds = assignMermaidIds(diagram.groups.map { it.id }, "g_")
        val emitted = mutableSetOf<String>()

        for (group in diagram.groups) {
            val parentId = group.parentId
            if (parentId != null) {
                sb.append("    %% group \"").append(oneLine(group.label))
                    .append("\" is nested under \"").append(oneLine(parentId))
                    .append("\" — flattened to one level (Mermaid subgraph limitation)\n")
            }
            sb.append("    subgraph ").append(groupIds.getValue(group.id))
                .append(" [\"").append(escapeLabel(group.label)).append("\"]\n")
            for (rawId in group.nodeIds) {
                val node = nodesById[rawId]
                when {
                    node == null ->
                        sb.append("        %% node \"").append(oneLine(rawId))
                            .append("\" listed in group but not defined — skipped\n")

                    !emitted.add(rawId) ->
                        sb.append("        %% node \"").append(oneLine(rawId))
                            .append("\" already emitted in another group — skipped\n")

                    else ->
                        sb.append("        ").append(nodeIds.getValue(rawId))
                            .append("[\"").append(nodeLabel(node)).append("\"]\n")
                }
            }
            sb.append("    end\n")
        }

        for (node in diagram.nodes) {
            if (!emitted.add(node.id)) continue
            sb.append("    ").append(nodeIds.getValue(node.id))
                .append("[\"").append(nodeLabel(node)).append("\"]\n")
        }

        for (seam in diagram.seams) {
            sb.append("    %% seam: ").append(oneLine(seam.fromGroupId))
                .append(" / ").append(oneLine(seam.toGroupId))
            seam.rationale?.takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(oneLine(it)) }
            sb.append("\n")
        }

        val crossing = diagram.allCrossingEdgeIds
        val crossingIndices = mutableListOf<Int>()
        var linkIndex = 0
        for (edge in diagram.edges) {
            val from = nodeIds[edge.from]
            val to = nodeIds[edge.to]
            if (from == null || to == null) {
                sb.append("    %% edge \"").append(oneLine(edge.id)).append("\" skipped: unknown endpoint\n")
                continue
            }
            val label = escapeLabel(edge.label ?: edge.kind.name.lowercase())
            if (edge.id in crossing) {
                crossingIndices += linkIndex
                sb.append("    ").append(from).append(" -. \"").append(label).append("\" .-> ").append(to).append("\n")
            } else {
                sb.append("    ").append(from).append(" -- \"").append(label).append("\" --> ").append(to).append("\n")
            }
            linkIndex++
        }
        if (crossingIndices.isNotEmpty()) {
            sb.append("    linkStyle ").append(crossingIndices.joinToString(","))
                .append(" stroke:#cc3333,stroke-width:2px\n")
        }
        return sb.toString()
    }

    private fun nodeLabel(node: DiagramNode): String {
        val parts = mutableListOf("\u00ab" + node.kind.name.lowercase() + "\u00bb " + escapeLabel(node.name))
        node.signature?.takeIf { it.isNotBlank() }?.let { sig ->
            parts += "<i>" + escapeLabel(truncateSignature(sig)) + "</i>"
        }
        node.loc?.let { parts += "$it LOC" }
        return parts.joinToString("<br/>")
    }

    private fun truncateSignature(sig: String): String =
        if (sig.length <= MAX_SIGNATURE_LENGTH) sig else sig.take(MAX_SIGNATURE_LENGTH - 1) + "\u2026"

    private fun escapeLabel(raw: String): String = raw
        .replace("\"", "#quot;")
        .replace("<", "#lt;")
        .replace(">", "#gt;")
        .replace("|", "#124;")
        .replace(LINE_BREAKS, " ")

    private fun oneLine(raw: String): String = raw.replace(LINE_BREAKS, " ")

    private fun sanitizeId(raw: String): String =
        NON_ID_CHARS.replace(raw, "_").ifBlank { "x" }

    /**
     * Maps raw diagram ids to unique Mermaid-safe ids. Duplicate raw ids keep
     * their first mapping; sanitization collisions get a numeric suffix.
     */
    private fun assignMermaidIds(rawIds: List<String>, prefix: String): Map<String, String> {
        val used = mutableSetOf<String>()
        val result = LinkedHashMap<String, String>()
        for (raw in rawIds) {
            if (raw in result) continue
            val base = prefix + sanitizeId(raw)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) candidate = base + "_" + n++
            result[raw] = candidate
        }
        return result
    }

    companion object {
        private const val MAX_SIGNATURE_LENGTH = 60
        private val LINE_BREAKS = Regex("\\r?\\n")
        private val NON_ID_CHARS = Regex("[^A-Za-z0-9_]")
    }
}
