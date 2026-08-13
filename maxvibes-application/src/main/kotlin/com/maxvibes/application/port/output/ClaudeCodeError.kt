package com.maxvibes.application.port.output

/**
 * Transport failures retained under the historical Claude Code name during the
 * incremental Agent CLI migration.
 *
 * New generic code should refer to [AgentCliError], which is currently a typealias
 * to this hierarchy. Keeping the concrete hierarchy here preserves source compatibility
 * for existing references such as `ClaudeCodeError.ResumeFailed`.
 */
sealed class ClaudeCodeError {

    object BinaryNotFound : ClaudeCodeError()

    object Timeout : ClaudeCodeError()

    data class Crashed(val message: String) : ClaudeCodeError()

    data class ProcessFailed(val exitCode: Int, val stderr: String) : ClaudeCodeError()

    data class ResumeFailed(val sessionId: String, val stderr: String) : ClaudeCodeError()

    data class ParseFailed(val message: String) : ClaudeCodeError()

    data class Aborted(val partialText: String?) : ClaudeCodeError()
}
