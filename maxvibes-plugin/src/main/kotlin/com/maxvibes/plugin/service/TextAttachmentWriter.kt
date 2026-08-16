package com.maxvibes.plugin.service

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Вложение, уже лежащее на диске: в историю чата попадает только ссылка на него. */
data class SavedAttachment(
    val relativePath: String,
    val chars: Int,
    val lines: Int
) {
    val stats: String
        get() {
            val size = if (chars >= 1_000) "%.1fk".format(chars / 1_000.0) else chars.toString()
            return "$size chars \u00B7 ${lines}L"
        }

    val caption: String
        get() = "\uD83D\uDCCE ${relativePath.substringAfterLast('/')} ($stats)"
}

/**
 * Формат SYSTEM-записи, которой вложение живёт в транскрипте.
 *
 * Тело вложения в XML истории класть нельзя: это один PersistentStateComponent,
 * который целиком читается на старте IDE и целиком переписывается при сохранении.
 */
object AttachmentNote {
    private const val PREFIX = "\uD83D\uDCCE Attachment: "

    fun format(attachment: SavedAttachment): String =
        "$PREFIX${attachment.relativePath} (${attachment.stats})"

    fun parsePath(note: String): String? {
        if (!note.startsWith(PREFIX)) return null
        return note.removePrefix(PREFIX).substringBeforeLast(" (").takeIf { it.isNotBlank() }
    }

    fun caption(note: String): String {
        val path = parsePath(note) ?: return note
        val stats = note.substringAfterLast(" (").removeSuffix(")")
        return "\uD83D\uDCCE ${path.substringAfterLast('/')} ($stats)"
    }
}

/** Пишет тело текстового вложения в `.maxvibes/attachments` внутри проекта. */
class TextAttachmentWriter(private val basePath: String) {

    fun save(text: String): SavedAttachment? = runCatching {
        val directory = File(basePath, DIRECTORY)
        directory.mkdirs()
        val name = "attachment-${TIMESTAMP.format(Instant.now())}.txt"
        File(directory, name).writeText(text)
        SavedAttachment(
            relativePath = "$DIRECTORY/$name",
            chars = text.length,
            lines = text.count { it == '\n' } + 1
        )
    }.getOrNull()

    private companion object {
        const val DIRECTORY = ".maxvibes/attachments"
        val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
    }
}
