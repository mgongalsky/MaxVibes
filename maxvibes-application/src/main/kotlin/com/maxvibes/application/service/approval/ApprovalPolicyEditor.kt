package com.maxvibes.application.service.approval

import com.maxvibes.application.port.output.ApprovalPolicyPort
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy

/**
 * Редактируемый черновик политики апрувалов под контракт страницы настроек.
 *
 * Держит две версии: [saved] — то, что лежит в хранилище, [draft] — то, что
 * пользователь наметил. Разница между ними и есть «есть несохранённые изменения».
 * Ни одного UI-типа, поэтому проверяется без IDE и без Swing.
 */
class ApprovalPolicyEditor(private val port: ApprovalPolicyPort) {

    private var saved: ApprovalPolicy = port.load()
    private var draft: ApprovalPolicy = saved

    /** Перечитывает хранилище и выбрасывает черновик: страница настроек открыта заново или нажат Reset. */
    fun reset() {
        saved = port.load()
        draft = saved
    }

    fun modeFor(kind: AgentActionKind): ApprovalMode = draft.modeFor(kind)

    fun select(kind: AgentActionKind, mode: ApprovalMode) {
        draft = draft.with(kind, mode)
    }

    /** Сравнение по содержимому, а не по ссылке: редактор не зависит от того, дата-класс ли политика. */
    fun isModified(): Boolean = draft.asMap() != saved.asMap()

    fun apply() {
        port.save(draft)
        saved = draft
    }
}
