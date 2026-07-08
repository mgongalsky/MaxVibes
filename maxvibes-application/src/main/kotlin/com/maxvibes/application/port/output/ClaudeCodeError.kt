package com.maxvibes.application.port.output

/**
 * Errors emitted by [ClaudeCodePort] implementations.
 *
 * Sealed hierarchy so consumers can exhaustively match on every failure
 * mode without resorting to string parsing.
 */
sealed class ClaudeCodeError {

    /** `claude --version` failed or the binary is not on PATH / at the configured path. */
    object BinaryNotFound : ClaudeCodeError()

    /** Read from stdout did not complete within the configured timeout. */
    object Timeout : ClaudeCodeError()

    /** The process is dead, an I/O operation failed, or the adapter is in an unrecoverable state. */
    data class Crashed(val message: String) : ClaudeCodeError()

    /** The process exited unexpectedly (e.g. on startup). */
    data class ProcessFailed(val exitCode: Int, val stderr: String) : ClaudeCodeError()

    /** `--resume <id>` was passed but claude refused to resume the session. Caller should fall back to a fresh start. */
    data class ResumeFailed(val sessionId: String, val stderr: String) : ClaudeCodeError()

    /** stdout was received but could not be decoded into an `InteractionResponse`. */
    /** stdout was received but could not be decoded into an `InteractionResponse`. */
    data class ParseFailed(val message: String) : ClaudeCodeError()

    /** The turn was aborted (user Stop or inactivity watchdog). Carries any partial narration. */
    data class Aborted(val partialText: String?) : ClaudeCodeError()
}
