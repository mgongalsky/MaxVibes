package com.maxvibes.application.service.approval

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy
import com.maxvibes.domain.model.approval.ApprovalSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Единственная точка, где решается, нужно ли спрашивать человека.
 *
 * Сводит два источника: постоянную политику проекта и эфемерный тумблер
 * «разрешать всё» для конкретной сессии.
 *
 * [policyProvider] вызывается на каждое решение, а не читается один раз:
 * пользователь меняет режим в настройках посреди работы агента, и снимок
 * политики отложил бы новую настройку до следующего сообщения.
 *
 * Тумблер живёт в конкурентной структуре, потому что переключают его в
 * UI-потоке, а читает решение фоновая корутина турна.
 */
class ApprovalService(
    private val policyProvider: () -> ApprovalPolicy
) {

    private val allowAllSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Тумблер сильнее политики: иначе нажатая кнопка не разрешала бы то, что в настройках стоит как ASK. */
    fun decide(sessionId: String, kind: AgentActionKind): ApprovalDecision {
        if (sessionId in allowAllSessions) {
            return ApprovalDecision.Allow(ApprovalSource.SESSION_OVERRIDE)
        }
        return when (policyProvider().modeFor(kind)) {
            ApprovalMode.AUTO_ALLOW -> ApprovalDecision.Allow(ApprovalSource.POLICY)
            ApprovalMode.ASK -> ApprovalDecision.Ask
        }
    }

    fun setAllowAll(sessionId: String, enabled: Boolean) {
        if (enabled) allowAllSessions.add(sessionId) else allowAllSessions.remove(sessionId)
    }

    fun isAllowAll(sessionId: String): Boolean = sessionId in allowAllSessions

    /** Тумблер не переживает завершение сессии — автономия не должна включаться сама собой позже. */
    fun clearSession(sessionId: String) {
        allowAllSessions.remove(sessionId)
    }
}
