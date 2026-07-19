package com.maxvibes.domain.model.planning

/** Kind of a diagram node — the code element the planner is talking about. */
enum class DiagramNodeKind { CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, MODULE }

/** Semantic relation between two diagram nodes. */
enum class DiagramEdgeKind { CALLS, USES, EXTENDS, IMPLEMENTS, OWNS }

/**
 * One node of a [PlanDiagram].
 *
 * @param id        stable identifier referenced by edges and groups.
 * @param kind      code element kind the node represents.
 * @param name      display name (class/function name).
 * @param signature optional short signature shown under the name; renderers may truncate it.
 * @param filePath  optional project-relative path to the source file (reserved for future navigation).
 * @param loc       optional lines-of-code count shown in the node label.
 */
data class DiagramNode(
    val id: String,
    val kind: DiagramNodeKind,
    val name: String,
    val signature: String? = null,
    val filePath: String? = null,
    val loc: Int? = null
)

/**
 * One directed edge of a [PlanDiagram].
 *
 * The [id] is mandatory: seams reference edges by id to mark the future
 * public contract between modules ([DiagramSeam.crossingEdgeIds]).
 *
 * @param id    stable identifier referenced by seams.
 * @param from  id of the source node.
 * @param to    id of the target node.
 * @param kind  semantic relation kind.
 * @param label optional edge label.
 */
data class DiagramEdge(
    val id: String,
    val from: String,
    val to: String,
    val kind: DiagramEdgeKind = DiagramEdgeKind.USES,
    val label: String? = null
)

/**
 * A semantic grouping of nodes — a future module after the cut.
 *
 * @param id       stable identifier referenced by seams and child groups.
 * @param label    display name of the group.
 * @param nodeIds  ids of the nodes belonging to this group.
 * @param parentId optional id of the enclosing group; renderers that cannot nest
 *                 (e.g. Mermaid subgraph limitations) may flatten the hierarchy.
 */
data class DiagramGroup(
    val id: String,
    val label: String,
    val nodeIds: List<String> = emptyList(),
    val parentId: String? = null
)

/**
 * A seam — the place where the plan cuts the code into two future modules.
 *
 * @param fromGroupId     id of the group on one side of the cut.
 * @param toGroupId       id of the group on the other side.
 * @param rationale       why the cut goes here.
 * @param crossingEdgeIds ids of the edges the seam crosses — the future public
 *                        contract between the modules; renderers highlight them.
 */
data class DiagramSeam(
    val fromGroupId: String,
    val toGroupId: String,
    val rationale: String? = null,
    val crossingEdgeIds: List<String> = emptyList()
)

/**
 * A structural diagram attached to a task plan: what the code looks like,
 * how it is grouped into future modules, and where the seams cut.
 *
 * The model is renderer-agnostic and carries no layout information —
 * coordinates are always computed by a layout engine, never by the LLM.
 * All collections default to empty so responses without a diagram (or with
 * a partial one) parse as-is.
 */
data class PlanDiagram(
    val title: String? = null,
    val nodes: List<DiagramNode> = emptyList(),
    val edges: List<DiagramEdge> = emptyList(),
    val groups: List<DiagramGroup> = emptyList(),
    val seams: List<DiagramSeam> = emptyList()
) {
    /** Union of edge ids crossed by any seam — every renderer highlights these. */
    val allCrossingEdgeIds: Set<String>
        get() = seams.flatMapTo(mutableSetOf()) { it.crossingEdgeIds }

    /** Nodes not claimed by any group — rendered at the top level. */
    val ungroupedNodes: List<DiagramNode>
        get() {
            val grouped = groups.flatMapTo(mutableSetOf()) { it.nodeIds }
            return nodes.filter { it.id !in grouped }
        }
}
