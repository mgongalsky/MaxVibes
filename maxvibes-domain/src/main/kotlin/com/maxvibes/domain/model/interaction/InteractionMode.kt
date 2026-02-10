package com.maxvibes.domain.model.interaction

/**
 * Режим взаимодействия с LLM.
 *
 * Определяет КАК MaxVibes общается с моделью:
 * - API: прямой вызов через LangChain4j (текущий режим)
 * - CLIPBOARD: генерация JSON для копипаста через чат (подписка)
 * - CHEAP_API: дешёвая модель по API для простых задач
 */
enum class InteractionMode {
    /** Direct API call via LangChain4j. Full automation, costs per token. */
    API,

    /** Generate JSON → paste through Claude/ChatGPT chat. Uses subscription, no API costs. */
    CLIPBOARD,

    /** Cheap LLM API (DeepSeek, Haiku) for simple tasks. Minimal cost. */
    CHEAP_API
}