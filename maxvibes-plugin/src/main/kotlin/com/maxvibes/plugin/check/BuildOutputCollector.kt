package com.maxvibes.plugin.check

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.maxvibes.domain.model.check.CheckIssue

/** `e: file:///E:/proj/Foo.kt:12:5 Unresolved reference` — нынешний kotlinc. */
private val KOTLIN_URL_ERROR = Regex("""^e: file://(.+?):(\d+):(\d+)[: ]\s*(.*)$""")

/** `e: E:\proj\Foo.kt: (12, 5): Unresolved reference` — kotlinc до 2.0. */
private val KOTLIN_LEGACY_ERROR = Regex("""^e: (.+?): \((\d+), (\d+)\):\s*(.*)$""")

/** `E:\proj\Foo.java:12: error: cannot find symbol` — javac. */
private val JAVA_ERROR = Regex("""^(.+?\.java):(\d+): error:\s*(.*)$""")

/** Лог многомодульной сборки исчисляется мегабайтами — держим только хвост. */
private const val MAX_OUTPUT_CHARS = 200_000

private const val MAX_RAW_OUTPUT_CHARS = 4_000

internal const val MAX_REPORTED_ISSUES = 50

/**
 * Приводит абсолютный путь к виду, пригодному для отчёта агенту.
 *
 * Разделители нормализуются: на Windows compiler API отдаёт обратные слэши, а
 * пути в протоколе везде прямые.
 */
internal fun toProjectRelativePath(project: Project, path: String): String {
    val normalized = path.replace('\\', '/')
    val base = project.basePath?.replace('\\', '/') ?: return normalized
    return normalized.removePrefix(base).removePrefix("/")
}

/**
 * Собирает диагностику сборки из тех каналов, где она действительно появляется.
 *
 * Основной канал — поток [com.intellij.build.events.BuildEvent], питающий
 * вкладку Build. Он выбран не из удобства: Android Studio запускает Gradle
 * собственным инвокером, и вывод не доходит ни до слушателя внешних систем, ни
 * до `ProcessHandler` запуска. На практике это выглядело так, что проверка
 * честно сообщала о провале сборки, но не могла показать ни одной строки
 * ошибки, хотя во вкладке Build они были.
 *
 * Слушатель внешних систем оставлен вторым каналом: он даёт сырой текст, из
 * которого позиция вытаскивается регулярками, когда событие пришло без неё.
 *
 * События приходят с чужих потоков, поэтому обе накопительные структуры
 * защищены блокировками.
 */
class BuildOutputCollector(private val project: Project) {

    private val issues = mutableListOf<CheckIssue>()
    private val output = StringBuilder()
    private var buildViewDisposable: Disposable? = null
    private var externalSystemListener: ExternalSystemTaskNotificationListenerAdapter? = null

    fun start() {
        val parent = Disposer.newDisposable("MaxVibes build diagnostics")
        buildViewDisposable = parent
        project.getService(BuildViewManager::class.java)?.addListener(
            BuildProgressListener { _, event ->
                when {
                    event is MessageEvent && event.kind == MessageEvent.Kind.ERROR -> record(event)
                    event is OutputBuildEvent -> append(event.message)
                }
            },
            parent
        )

        val listener = object : ExternalSystemTaskNotificationListenerAdapter() {
            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
                append(text)
            }
        }
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(listener)
        externalSystemListener = listener
    }

    fun stop() {
        buildViewDisposable?.let { Disposer.dispose(it) }
        buildViewDisposable = null
        externalSystemListener?.let {
            ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(it)
        }
        externalSystemListener = null
    }

    /** Структурные ошибки идут первыми: у них есть файл и строка, у разобранных текстом — не всегда. */
    fun issues(): List<CheckIssue> {
        val structured = synchronized(issues) { issues.toList() }
        val parsed = snapshot().lineSequence().mapNotNull { parseCompilerLine(it.trim()) }.toList()
        return (structured + parsed).distinct().take(MAX_REPORTED_ISSUES)
    }

    fun outputTail(): String {
        val text = snapshot().trim()
        return if (text.length <= MAX_RAW_OUTPUT_CHARS) text
        else text.substring(text.length - MAX_RAW_OUTPUT_CHARS)
    }

    fun hasOutput(): Boolean = snapshot().isNotBlank()

    private fun record(event: MessageEvent) {
        val text = event.message.trim().ifEmpty { event.description?.trim().orEmpty() }
        if (text.isEmpty()) return
        val position = (event as? FileMessageEvent)?.filePosition
        val issue = CheckIssue(
            message = text,
            filePath = position?.file?.path?.let { toProjectRelativePath(project, it) },
            // FilePosition нумерует строки с нуля, отчёт — с единицы.
            line = position?.startLine?.plus(1)
        )
        synchronized(issues) { issues += issue }
    }

    private fun parseCompilerLine(line: String): CheckIssue? {
        KOTLIN_URL_ERROR.matchEntire(line)?.let { match ->
            val (path, lineNumber, _, text) = match.destructured
            return CheckIssue(
                message = text.trim(),
                filePath = toProjectRelativePath(project, stripFileUrl(path)),
                line = lineNumber.toIntOrNull()
            )
        }
        KOTLIN_LEGACY_ERROR.matchEntire(line)?.let { match ->
            val (path, lineNumber, _, text) = match.destructured
            return CheckIssue(
                message = text.trim(),
                filePath = toProjectRelativePath(project, path),
                line = lineNumber.toIntOrNull()
            )
        }
        JAVA_ERROR.matchEntire(line)?.let { match ->
            val (path, lineNumber, text) = match.destructured
            return CheckIssue(
                message = text.trim(),
                filePath = toProjectRelativePath(project, path),
                line = lineNumber.toIntOrNull()
            )
        }
        return null
    }

    /** `file:///E:/proj/Foo.kt` — на Windows ведущий слэш перед буквой диска лишний. */
    private fun stripFileUrl(path: String): String {
        val withoutScheme = path.removePrefix("file://")
        return if (Regex("^/[A-Za-z]:.*").matches(withoutScheme)) withoutScheme.substring(1) else withoutScheme
    }

    private fun append(text: String) {
        synchronized(output) {
            output.append(text)
            if (output.length > MAX_OUTPUT_CHARS) output.delete(0, output.length - MAX_OUTPUT_CHARS)
        }
    }

    private fun snapshot(): String = synchronized(output) { output.toString() }
}
