package com.maxvibes.domain.model.check

/**
 * Текст падения теста в виде, по которому сразу видна причина.
 *
 * Вынесено из раннера намеренно: IDE отдаёт заголовок исключения, сравнение и
 * стек тремя разными кусками, и склейка этих кусков — единственная часть
 * отчёта, которую можно проверить тестами без запущенной IDE.
 */
object TestFailureText {

    /**
     * Заголовок падения. [expected] и [actual] IDE забирает из исключения в
     * отдельные поля, чтобы нарисовать диалог сравнения, и в сообщении остаётся
     * голое имя класса исключения — без этой склейки причина падения теряется.
     */
    fun message(header: String?, expected: String?, actual: String?): String {
        val cleaned = header?.trim()?.trimEnd(':')?.trim()?.takeIf { it.isNotBlank() }
        val comparison = if (expected == null && actual == null) null
        else "expected: <${expected.orEmpty()}> but was: <${actual.orEmpty()}>"
        if (comparison != null && cleaned != null && cleaned.contains(comparison)) return cleaned
        return listOfNotNull(cleaned, comparison).joinToString(": ").ifBlank { "Test failed" }
    }

    /**
     * Кадры стека, принадлежащие проекту. Фреймворк кладёт сверху десяток
     * собственных кадров, и место падения тонет в них. Если после отсева не
     * осталось ничего, возвращается начало исходного стека: пустой details хуже
     * шумного.
     */
    fun relevantFrames(stacktrace: String?, limit: Int = 5): String? {
        val lines = stacktrace?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) return null
        val meaningful = lines.filterNot { isFrameworkFrame(it) }
        return meaningful.ifEmpty { lines }.take(limit).joinToString("\n")
    }

    private fun isFrameworkFrame(line: String): Boolean {
        if (!line.startsWith("at ")) return false
        val frame = line.removePrefix("at ").trim()
        return FRAMEWORK_PREFIXES.any { frame.startsWith(it) }
    }

    private val FRAMEWORK_PREFIXES = listOf(
        "org.junit.",
        "junit.",
        "org.opentest4j.",
        "org.testng.",
        "io.kotest.",
        "kotlin.test.",
        "java.",
        "jdk.",
        "sun.",
        "org.gradle.",
        "worker.org.gradle."
    )
}
