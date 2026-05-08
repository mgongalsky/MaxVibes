package com.maxvibes.domain.model.interaction

/**
 * Режим взаимодействия с LLM.
 *
 * Определяет КАК MaxVibes общается с моделью:
 * - API: прямой вызов через LangChain4j (текущий режим)
 * - CLIPBOARD: генерация JSON для копипаста через чат (подписка)
 * - CHEAP_API: дешёвая модель по API для простых задач
 * - CLAUDE_CODE: локальный процесс Claude CLI в режиме stream-JSON
 */
enum class InteractionMode {
    /** Direct API call via LangChain4j. Full automation, costs per token. */
    API,

    /** Generate JSON → paste through Claude/ChatGPT chat. Uses subscription, no API costs. */
    CLIPBOARD,

    /** Cheap LLM API (DeepSeek, Haiku) for simple tasks. Minimal cost. */
    CHEAP_API,

    /**
     * Claude Code (local CLI process). MaxVibes spawns `claude` CLI in stream-JSON mode
     * and exchanges request/response JSON identical to CLIPBOARD mode.
     * All code modifications and context gathering are performed by the plugin —
     * Claude Code only generates JSON responses.
     */
    CLAUDE_CODE
}
