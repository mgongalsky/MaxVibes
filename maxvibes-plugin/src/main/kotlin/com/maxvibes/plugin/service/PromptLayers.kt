package com.maxvibes.plugin.service

import java.io.File

/**
 * Промпт, который поставляет плагин, и имя файла, которым его дополняет проект.
 *
 * [fileName] намеренно совпадает с историческим именем полного переопределения:
 * тот же файл, лежащий не в `base/`, а прямо в каталоге промптов, — это признак
 * старой схемы, который надо распознать и убрать, а не прочитать.
 */
internal enum class PromptKind(val fileName: String, val resourcePath: String) {
    CHAT_SYSTEM("chat-system.md", "/prompts/chat-system.md"),
    PLANNING_SYSTEM("planning-system.md", "/prompts/planning-system.md"),
    CLAUDE_CODE_SYSTEM("claude-code-system.md", "/prompts/claude-code-system.md"),
    CODEX_SYSTEM("codex-system.md", "/prompts/codex-system.md");

    val localFileName: String = fileName.removeSuffix(".md") + ".local.md"
}

/** Что именно изменила синхронизация. Показывается пользователю, поэтому хранит имена файлов. */
internal data class PromptSyncReport(
    val baseWritten: List<String> = emptyList(),
    val localCreated: List<String> = emptyList(),
    val legacyArchived: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        baseWritten.isEmpty() && localCreated.isEmpty() && legacyArchived.isEmpty()
}

/**
 * Двухслойные промпты: неизменяемая база из плагина плюс необязательный слой проекта.
 *
 * База не читается с диска никогда — только из [baseText]. Файлы в `base/` пишутся
 * исключительно чтобы человек видел, что он дополняет: испорченное или отредактированное
 * зеркало не может изменить текст, который уедет модели. Именно эта связь между
 * редактируемым файлом и поведением агента раньше рвалась молча.
 */
internal class PromptLayers(
    private val promptsDir: File,
    private val baseText: (PromptKind) -> String
) {

    companion object {
        const val BASE_DIR = "base"

        /**
         * Заголовок слоя проекта уезжает в промпт вместе с ним и сам объявляет приоритет,
         * поэтому дописывать ту же фразу в каждый из базовых текстов не требуется.
         */
        private const val OVERLAY_HEADER =
            "\n\n## Project-specific instructions\n\n" +
                    "The rules below come from this project and take priority over everything above.\n\n"

        private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }

    /** Базовый текст плюс слой проекта, если тот содержит хоть что-то кроме комментариев. */
    fun compose(kind: PromptKind): String {
        val overlay = overlayOf(kind) ?: return baseText(kind)
        return baseText(kind) + OVERLAY_HEADER + overlay
    }

    fun hasOverlay(kind: PromptKind): Boolean = overlayOf(kind) != null

    /** Файлы старой схемы: полное переопределение, лежащее рядом с `base/`, а не внутри. */
    fun legacyFiles(): List<File> =
        PromptKind.values().map { File(promptsDir, it.fileName) }.filter { it.isFile }

    /**
     * Приводит каталог в актуальный вид: обновляет зеркало базы и создаёт недостающие
     * файлы слоя. Существующий файл слоя не трогается ни при каких условиях — это
     * единственное содержимое каталога, которое принадлежит пользователю.
     */
    fun sync(archiveLegacy: Boolean = false): PromptSyncReport {
        val baseDir = File(promptsDir, BASE_DIR)
        if (!baseDir.isDirectory && !baseDir.mkdirs()) return PromptSyncReport()

        val baseWritten = mutableListOf<String>()
        val localCreated = mutableListOf<String>()

        // values(), а не entries: плагин собирается под apiVersion старых IDE, где
        // entries ещё экспериментальное и требует opt-in.
        PromptKind.values().forEach { kind ->
            val baseFile = File(baseDir, kind.fileName)
            val mirror = generatedHeader(kind) + baseText(kind)
            if (!baseFile.isFile || baseFile.readText() != mirror) {
                baseFile.writeText(mirror)
                baseWritten += "$BASE_DIR/${kind.fileName}"
            }

            val localFile = File(promptsDir, kind.localFileName)
            if (!localFile.exists()) {
                localFile.writeText(localTemplate(kind))
                localCreated += kind.localFileName
            }
        }

        return PromptSyncReport(
            baseWritten = baseWritten,
            localCreated = localCreated,
            legacyArchived = if (archiveLegacy) archiveLegacy() else emptyList()
        )
    }

    /**
     * Убирает файлы старой схемы из-под чтения, сохраняя их содержимое.
     *
     * Именно переименование, а не удаление: там может лежать кастомизация, написанная
     * руками, и кнопка «привести в порядок» не должна быть способом её потерять.
     */
    private fun archiveLegacy(): List<String> =
        legacyFiles().mapNotNull { file ->
            val target = freeLegacyTarget(file)
            if (file.renameTo(target)) target.name else null
        }

    private fun freeLegacyTarget(file: File): File {
        val stem = file.name.removeSuffix(".md")
        var candidate = File(promptsDir, "$stem.legacy.md")
        var index = 2
        while (candidate.exists()) {
            candidate = File(promptsDir, "$stem.legacy.$index.md")
            index++
        }
        return candidate
    }

    /**
     * Комментарии вырезаются до проверки на пустоту: файл-заглушка, который открыли
     * и закрыли не тронув, не должен дописывать модели абзац о том, как им пользоваться.
     */
    private fun overlayOf(kind: PromptKind): String? =
        File(promptsDir, kind.localFileName)
            .takeIf { it.isFile && it.canRead() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.replace(HTML_COMMENT, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun generatedHeader(kind: PromptKind): String =
        "<!-- Сгенерировано MaxVibes и перезаписывается при синхронизации. " +
                "Свои дополнения пишите в ../${kind.localFileName} -->\n\n"

    private fun localTemplate(kind: PromptKind): String =
        """
        <!--
        Дополнения к промпту ${kind.fileName}.

        Этот файл принадлежит вам: MaxVibes его не перезаписывает и не удаляет.
        Всё, что вы напишете вне комментариев, дописывается в конец базового промпта
        и объявляется модели как более приоритетное.

        Базовый текст лежит в ${BASE_DIR}/${kind.fileName} и обновляется вместе
        с плагином — править его бесполезно, изменения не читаются.
        -->
        """.trimIndent() + "\n"
}
