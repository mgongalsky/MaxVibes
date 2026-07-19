package com.maxvibes.domain.model.planning

/** Status of a single plan step shown in the planner panel. */
enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, SKIPPED }

/**
 * One step of a [TaskPlan].
 *
 * @param id       stable identifier used to address the step in status updates.
 * @param title    short imperative step title shown next to the checkbox.
 * @param status   current progress state.
 * @param docPath  optional project-relative path to the step's STEP_N.md document.
 */
data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val docPath: String? = null
)

/**
 * A task plan maintained by the LLM and pinned above the chat as a checklist.
 *
 * The plan is snapshot-based: the model always sends the full plan, never a diff.
 *
 * @param title    plan title shown in the panel header.
 * @param docPath  optional project-relative path to the feature's PLAN.md document.
 * @param steps    ordered list of steps.
 */
data class TaskPlan(
    val title: String,
    val docPath: String? = null,
    val steps: List<PlanStep> = emptyList()
) {
    /** Number of finished steps; SKIPPED counts as finished so progress can reach 100%. */
    val doneCount: Int get() = steps.count { it.status == PlanStepStatus.DONE || it.status == PlanStepStatus.SKIPPED }

    /** True when every step is DONE or SKIPPED (empty plans are never complete). */
    val isComplete: Boolean get() = steps.isNotEmpty() && doneCount == steps.size

    /** Returns a plan with the status of the step [stepId] replaced; no-op for unknown ids. */
    fun withStepStatus(stepId: String, status: PlanStepStatus): TaskPlan =
        copy(steps = steps.map { if (it.id == stepId) it.copy(status = status) else it })
}
