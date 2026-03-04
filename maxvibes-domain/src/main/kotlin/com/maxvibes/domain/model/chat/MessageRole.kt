package com.maxvibes.domain.model.chat

/**
 * Роль участника чата.
 *
 * Определяет КТО отправил сообщение:
 * - USER: сообщение от пользователя
 * - ASSISTANT: ответ от LLM
 * - SYSTEM: системное сообщение (статус, ошибки, служебная информация)
 */
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}