package com.maxvibes.application.port.output

/** Provider-independent failures reported by [AgentCliPort] implementations. */
sealed class AgentCliError {
    /** Configured executable is unavailable. */
    object BinaryNotFound : AgentCliError()

    /** No transport activity completed within the configured timeout. */
    object Timeout : AgentCliError()

    /** Process or transport I/O entered an unrecoverable state. */
    data class Crashed(val message: String) : AgentCliError()

    /** Provider process exited unexpectedly. */
    data class ProcessFailed(val exitCode: Int, val stderr: String) : AgentCliError()

    /** Provider rejected restoration of a previously persisted remote session. */
    data class ResumeFailed(val sessionId: String, val stderr: String) : AgentCliError()

    /** Final provider output could not be decoded into the MaxVibes protocol. */
    data class ParseFailed(val message: String) : AgentCliError()

    /** User or watchdog aborted the turn; partial narration may still be available. */
    data class Aborted(val partialText: String?) : AgentCliError()
}
