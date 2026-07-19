package com.maxvibes.application.port.output

import com.maxvibes.domain.model.chat.ChatSession

/**
 * Порт для хранения и загрузки чат-сессий.
 * Реализуется инфраструктурным слоем (IntelliJ persistence).
 */
interface ChatSessionRepository {

    /** Возвращает все сессии (без сортировки) */
    fun getAllSessions(): List<ChatSession>

    /** Возвращает сессию по ID или null */
    fun getSessionById(id: String): ChatSession?

    /** Возвращает ID активной сессии или null */
    fun getActiveSessionId(): String?

    /** Устанавливает активную сессию */
    fun setActiveSessionId(sessionId: String)

    /** Сохраняет или обновляет сессию */
    fun saveSession(session: ChatSession)

    /** Удаляет сессию по ID (только эту, без потомков) */
    fun deleteSession(sessionId: String)

    /** Возвращает список глобальных контекстных файлов */
    fun getGlobalContextFiles(): List<String>

    /** Устанавливает список глобальных контекстных файлов */
    fun setGlobalContextFiles(files: List<String>)
}
