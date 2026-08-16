package com.maxvibes.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.output.ApprovalPolicyPort
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy
import com.maxvibes.domain.model.turn.AutonomyBudget

/**
 * Политика апрувалов и лимит автономии, сохранённые для проекта в `maxvibes-approvals.xml`.
 *
 * В файл пишутся имена enum-ов строками, а не сами enum-ы: файл переживает
 * откат плагина на старую версию и правки руками. Незнакомое имя при чтении
 * отбрасывается — потеря одной настройки лучше, чем исключение, из-за которого
 * пользователь молча получит дефолты вместо всей своей политики.
 *
 * Виды действий, которых нет в файле, тоже не пишутся при сохранении:
 * [ApprovalPolicy] подставит текущий дефолт сама, и смена дефолта в будущих
 * версиях дойдёт до тех, кто эту настройку не трогал.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MaxVibesApprovalPolicy",
    storages = [Storage("maxvibes-approvals.xml")]
)
class ApprovalPolicySettings : PersistentStateComponent<ApprovalPolicySettings.State>, ApprovalPolicyPort {

    class State {
        var modes: MutableMap<String, String> = mutableMapOf()
        var autonomousIterations: Int = DEFAULT_AUTONOMOUS_ITERATIONS
        var maxFormatRetries: Int = DEFAULT_MAX_FORMAT_RETRIES
    }

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    override fun load(): ApprovalPolicy {
        var policy = ApprovalPolicy.DEFAULT
        myState.modes.forEach { (kindName, modeName) ->
            val kind = AgentActionKind.values().firstOrNull { it.name == kindName }
            val mode = ApprovalMode.values().firstOrNull { it.name == modeName }
            if (kind != null && mode != null) {
                policy = policy.with(kind, mode)
            }
        }
        return policy
    }

    override fun save(policy: ApprovalPolicy) {
        myState.modes = policy.asMap()
            .map { (kind, mode) -> kind.name to mode.name }
            .toMap()
            .toMutableMap()
    }

    /**
     * Сколько итераций LLM ход проходит сам, прежде чем спросить человека.
     *
     * Значение зажимается при чтении, а не только при записи: файл правят руками,
     * и лимит в ноль молча выключил бы автономию целиком, а лимит в тысячу сделал
     * бы её неотличимой от отсутствия лимита.
     */
    fun loadAutonomousIterations(): Int =
        myState.autonomousIterations.coerceIn(MIN_AUTONOMOUS_ITERATIONS, MAX_AUTONOMOUS_ITERATIONS)

    fun saveAutonomousIterations(iterations: Int) {
        myState.autonomousIterations =
            iterations.coerceIn(MIN_AUTONOMOUS_ITERATIONS, MAX_AUTONOMOUS_ITERATIONS)
    }

    /**
     * Сколько раз подряд агента просят переслать правки, которые не разобрались.
     *
     * Ноль — валидное значение и означает «не переспрашивать»: агент, который
     * стабильно шлёт мусор в `modifications`, иначе будет молотить бюджет хода
     * на каждой попытке.
     */
    fun loadMaxFormatRetries(): Int =
        myState.maxFormatRetries.coerceIn(MIN_FORMAT_RETRIES, MAX_FORMAT_RETRIES)

    fun saveMaxFormatRetries(retries: Int) {
        myState.maxFormatRetries = retries.coerceIn(MIN_FORMAT_RETRIES, MAX_FORMAT_RETRIES)
    }

    companion object {
        const val MIN_AUTONOMOUS_ITERATIONS: Int = 1
        const val MAX_AUTONOMOUS_ITERATIONS: Int = 50

        const val MIN_FORMAT_RETRIES: Int = 0
        const val MAX_FORMAT_RETRIES: Int = 5
        const val DEFAULT_MAX_FORMAT_RETRIES: Int = 2

        /** Дефолт берётся из домена, чтобы настройка и бюджет хода не разъехались. */
        val DEFAULT_AUTONOMOUS_ITERATIONS: Int = AutonomyBudget.DEFAULT.maxAutonomousIterations

        fun getInstance(project: Project): ApprovalPolicySettings = project.service()
    }
}
