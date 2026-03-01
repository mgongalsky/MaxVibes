package com.maxvibes.plugin.service

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.intellij.openapi.diagnostic.Logger
import com.maxvibes.application.port.output.LoggerPort

object MaxVibesLogger : LoggerPort {

    val sessionId: String = "s-" + UUID.randomUUID().toString().take(8)
    private val LOG = Logger.getInstance(MaxVibesLogger::class.java)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val queue = LinkedBlockingQueue<String>()
    private val maxSizeBytes = 5L * 1024 * 1024

    // По умолчанию — глобальная папка, будет переопределена через configure()
    @Volatile
    private var logDir: File = File(System.getProperty("user.home"), ".maxvibes/logs")

    @Volatile
    private var logFile: File = File(logDir, "maxvibes.log")

    @Volatile
    private var configured = false

    @Volatile
    private var running = true

    private val writerThread = Thread({
        var writer: PrintWriter? = null
        try {
            // Ждём пока configure() не задаст правильный путь (до 10 секунд)
            val deadline = System.currentTimeMillis() + 10_000
            while (!configured && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (!configured) {
                println("[MaxVibesLogger] WARNING: configure() never called, using default path")
            }
            logDir.mkdirs()
            rotateIfNeeded()
            writer = PrintWriter(FileWriter(logFile, true), true)
            println("[MaxVibesLogger] Writing to: ${logFile.absolutePath}")
            while (running || queue.isNotEmpty()) {
                val line = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                writer.println(line)
            }
        } catch (e: Exception) {
            LOG.error("MaxVibesLogger writer thread failed", e)
            println("[MaxVibesLogger] Writer thread ERROR: ${e.message}")
        } finally {
            writer?.close()
        }
    }, "MaxVibes-Logger").also { it.isDaemon = true }

    init {
        writerThread.start()
        println("[MaxVibesLogger] Writer thread started. Default log: ${logFile.absolutePath}")
    }

    /**
     * Вызывается из MaxVibesService при инициализации проекта.
     * Переключает логи в папку конкретного проекта: [projectBasePath]/.maxvibes/logs/
     */
    fun configure(projectBasePath: String) {
        if (configured) return
        configured = true
        logDir = File(projectBasePath, ".maxvibes/logs")
        logFile = File(logDir, "maxvibes.log")
        println("[MaxVibesLogger] Configured for project. Log file: ${logFile.absolutePath}")
        val ideVersion = try {
            com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
        } catch (_: Exception) {
            "unknown"
        }
        log(
            "SESSION", "MaxVibesLogger", "START", mapOf(
                "logFile" to logFile.absolutePath,
                "ideVersion" to ideVersion,
                "os" to (System.getProperty("os.name") + " " + System.getProperty("os.version"))
            )
        )
    }

    override fun debug(tag: String, msg: String, data: Map<String, Any?>?) = log("DEBUG", tag, msg, data)
    override fun info(tag: String, msg: String, data: Map<String, Any?>?) = log("INFO", tag, msg, data)
    override fun warn(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) =
        log("WARN", tag, msg, exData(ex, data))

    override fun error(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) =
        log("ERROR", tag, msg, exData(ex, data, stack = true))

    fun shutdown() {
        log("SESSION", "MaxVibesLogger", "END", null)
        running = false
        writerThread.join(2000)
    }

    private fun exData(ex: Throwable?, base: Map<String, Any?>?, stack: Boolean = false): Map<String, Any?>? {
        if (ex == null) return base
        val m = mutableMapOf<String, Any?>("ex" to ex.javaClass.simpleName, "exMsg" to ex.message)
        if (stack) m["stack"] = ex.stackTrace.take(5).joinToString(" | ")
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
            val backup = File(logDir, "maxvibes.log.old")
            backup.delete()
            logFile.renameTo(backup)
        }
    }

    private fun kv(k: String, v: Any?): String = "\"$k\":${valueToJson(v)}"

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> {
                    sb.append('\\'); sb.append('\\')
                }

                '"' -> {
                    sb.append('\\'); sb.append('"')
                }

                '\n' -> {
                    sb.append('\\'); sb.append('n')
                }

                '\r' -> {
                    sb.append('\\'); sb.append('r')
                }

                '\t' -> {
                    sb.append('\\'); sb.append('t')
                }

                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        is String -> escapeJson(v)
        else -> escapeJson(v.toString())
    }
}
