package com.maxvibes.application.port.output

// Legacy owner of the error variants during the incremental CodingAgent migration.
// Keep the nested variants here until all ClaudeCodeError.X call sites are migrated.
sealed class ClaudeCodeError {
    object BinaryNotFound : ClaudeCodeError()
    object Timeout : ClaudeCodeError()
    data class Crashed(val message: String) : ClaudeCodeError()
    data class ProcessFailed(val exitCode: Int, val stderr: String) : ClaudeCodeError()
    data class ResumeFailed(val sessionId: String, val stderr: String) : ClaudeCodeError()
    data class ParseFailed(val message: String) : ClaudeCodeError()
    data class Aborted(val partialText: String?) : ClaudeCodeError()
}
