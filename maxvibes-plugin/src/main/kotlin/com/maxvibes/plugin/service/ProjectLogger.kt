package com.maxvibes.plugin.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.output.LoggerPort
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProjectLogger(private val project: Project) : LoggerPort {

    private val sessionId: String = "s-" + UUID.randomUUID().toString().take(8)
    private val ideLogger = Logger.getInstance(ProjectLogger::class.java)

    private val logDir = File(project.basePath, ".maxvibes")
    private val logFile = File(logDir, "plugin.log")
    private val maxSizeBytes = 5L * 1024 * 1024
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val queue = LinkedBlockingQueue<String>()

    @Volatile
    private var running = true

    private val writerThread = thread(start = false, isDaemon = true, name = "MaxVibes-ProjectLogger-${project.name}") {
        var writer: PrintWriter? = null
        try {
            writer = PrintWriter(FileWriter(logFile, true), true)
            while (running || queue.isNotEmpty()) {
                val line = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                writer.println(line)
            }
        } catch (e: Exception) {
            ideLogger.error("ProjectLogger writer thread failed for project ${project.name}", e)
        } finally {
            writer?.close()
        }
    }

    init {
        try {
            logDir.mkdirs()
            rotateIfNeeded()
            writerThread.start()
            val ideVersion = try {
                com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
            } catch (_: Exception) {
                "unknown"
            }
            log(
                "SESSION", "ProjectLogger", "START", mapOf(
                    "project" to project.name,
                    "ideVersion" to ideVersion,
                    "os" to (System.getProperty("os.name") + " " + System.getProperty("os.version"))
                )
            )
        } catch (e: Exception) {
            ideLogger.error("Failed to initialize ProjectLogger for ${project.name}", e)
        }
    }

    override fun debug(tag: String, msg: String, data: Map<String, Any?>?) {
        ideLogger.debug("[$tag] $msg")
        log("DEBUG", tag, msg, data)
    }

    override fun info(tag: String, msg: String, data: Map<String, Any?>?) {
        ideLogger.info("[$tag] $msg")
        log("INFO", tag, msg, data)
    }

    override fun warn(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) {
        ideLogger.warn("[$tag] $msg", ex)
        log("WARN", tag, msg, exData(ex, data))
    }

    override fun error(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) {
        ideLogger.error("[$tag] $msg", ex)
        log("ERROR", tag, msg, exData(ex, data, stack = true))
    }

    fun shutdown() {
        log("SESSION", "ProjectLogger", "END", null)
        running = false
        writerThread.join(2000)
    }

    private fun exData(ex: Throwable?, base: Map<String, Any?>?, stack: Boolean = false): Map<String, Any?>? {
        if (ex == null) return base
        val m = mutableMapOf<String, Any?>("ex" to ex.javaClass.simpleName, "exMsg" to ex.message)
        if (stack) m["stack"] = ex.stackTrace.take(10).joinToString(" | ")
        return (base ?: emptyMap()) + m
    }

    private fun log(level: String, tag: String, msg: String, data: Map<String, Any?>?) {
        val t = LocalDateTime.now().format(formatter)
        val sb = StringBuilder()
        sb.append('{')
        sb.append(kv("t", t)).append(',')
        sb.append(kv("s", sessionId)).append(',')
        sb.append(kv("lvl", level)).append(',')
        sb.append(kv("tag", tag)).append(',')
        sb.append(kv("msg", msg))
        if (!data.isNullOrEmpty()) {
            sb.append(',').append('"').append("data").append('"').append(':').append('{')
            sb.append(data.entries.joinToString(",") { (k, v) -> kv(k, v) })
            sb.append('}')
        }
        sb.append('}')
        queue.offer(sb.toString())
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > maxSizeBytes) {
            val backup = File(logDir, "plugin.log.old")
            backup.delete()
            logFile.renameTo(backup)
        }
    }

    private fun kv(k: String, v: Any?): String {
        return '"' + k + "\":" + valueToJson(v)
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is String -> escapeJson(v)
        else -> escapeJson(v.toString())
    }
}
