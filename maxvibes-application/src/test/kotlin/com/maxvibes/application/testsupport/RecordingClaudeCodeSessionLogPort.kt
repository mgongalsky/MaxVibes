package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort

class RecordingClaudeCodeSessionLogPort : ClaudeCodeSessionLogPort {
    data class Event(
        val text: String,
        val data: Map<String, Any?>?
    )

    val begunSessionIds = mutableListOf<String>()
    val events = mutableListOf<Event>()
    val outboundLines = mutableListOf<String>()
    val inboundLines = mutableListOf<String>()
    val stderrLines = mutableListOf<String>()
    val logPaths = mutableMapOf<String, String>()

    override fun begin(chatSessionId: String) {
        begunSessionIds += chatSessionId
    }

    override fun event(text: String, data: Map<String, Any?>?) {
        events += Event(text, data)
    }

    override fun outbound(line: String) {
        outboundLines += line
    }

    override fun inbound(line: String) {
        inboundLines += line
    }

    override fun stderr(line: String) {
        stderrLines += line
    }

    override fun logFilePath(chatSessionId: String): String? =
        logPaths[chatSessionId]
}
