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

/**
 * Политика апрувалов, сохранённая для проекта в `maxvibes-approvals.xml`.
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

    companion object {
        fun getInstance(project: Project): ApprovalPolicySettings = project.service()
    }
}
