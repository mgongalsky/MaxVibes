package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.shared.result.Result

/**
 * Port for the Claude Code interaction mode — communicates with a locally
 * running `claude` CLI process via stream-JSON over stdin/stdout.
 *
 * The port is implemented in the plugin layer by `ClaudeCodeProcessAdapter`.
 * One adapter instance is owned per project (held by `MaxVibesService`).
 *
 * Lifecycle:
 *  - [ensureStarted] is idempotent: subsequent calls are no-ops while the
 *    process is alive.
 *  - [send] requires the process to be running; otherwise returns
 *    [ClaudeCodeError.Crashed].
 *  - [shutdown] terminates the process and is safe to call multiple times.
 *
 * Concurrency: implementations must serialize concurrent [send] calls so
 * stdin/stdout ordering is preserved.
 */
interface ClaudeCodePort {

    /**
     * Quick liveness check for the CLI binary itself (e.g. runs
     * `claude --version`). Does NOT start a long-lived process.
     *
     * @return `true` if the binary is callable, `false` otherwise.
     */
    fun isAvailable(): Boolean

    /**
     * Lazily starts the underlying `claude` process if not yet running.
     *
     * The MaxVibes system instruction is supplied here (via `--append-system-prompt`)
     * rather than embedded into every user-event JSON. This avoids tripping the
     * Claude Code CLI's built-in prompt-injection classifier, which flags large
     * prompt-looking blobs inside user content.
     *
     * @param resumeSessionId if non-null, the adapter passes `--resume <id>` to
     *        reuse an existing claude session. If null, a fresh session is started
     *        and its session id will be reported in the next [send] result.
     * @param systemPrompt if non-null and non-blank, passed as `--append-system-prompt`
     *        when spawning a fresh process. When resuming an existing session it is
     *        typically null — the prompt was already installed at first start. When the
     *        process is already alive (idempotent re-call), the parameter is ignored.
     */
    suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String? = null
    ): Result<Unit, ClaudeCodeError>

    /**
     * Sends a single [ClipboardRequest] to the running process and waits
     * for the corresponding response turn to complete.
     *
     * Caller is responsible for calling [ensureStarted] first.
     */
    suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError>

    /**
     * Terminates the underlying process and releases all I/O resources.
     * Safe to call multiple times.
     */
    fun shutdown()
}
