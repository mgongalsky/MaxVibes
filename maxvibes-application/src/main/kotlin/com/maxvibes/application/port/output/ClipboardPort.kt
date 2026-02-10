package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardResponse

/**
 * Порт для clipboard-взаимодействия.
 * Реализуется в plugin слое (ClipboardAdapter).
 */
interface ClipboardPort {

    /**
     * Копирует JSON-запрос в системный буфер обмена.
     * @return true если скопировано успешно
     */
    fun copyRequestToClipboard(request: ClipboardRequest): Boolean

    /**
     * Парсит текст как ClipboardResponse.
     * Поддерживает raw JSON, ```json``` блоки, mixed text+JSON.
     *
     * @param rawText текст из поля ввода
     * @return распарсенный ответ или null если формат невалидный
     */
    fun parseResponse(rawText: String): ClipboardResponse?
}