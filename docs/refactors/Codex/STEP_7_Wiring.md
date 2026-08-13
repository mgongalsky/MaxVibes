# Step 7 — Wiring and UI

## Result

Codex is wired as a provider of the existing Coding Agent flow, not as a new interaction stack.

The persisted interaction-mode id remains `CLAUDE_CODE` for backward compatibility, but the visible UI calls the mode **Coding Agent**. The concrete runtime is selected separately through `codingAgentProvider`.

Supported providers:

- `CLAUDE_CODE`
- `CODEX`

Old installations default to Claude Code because `codingAgentProvider` defaults to `CLAUDE_CODE`.

## Service wiring

`MaxVibesService` owns provider-specific transports lazily:

- `ClaudeCodeProcessAdapter`
- `CodexAppServerAdapter`

It also owns one `CodingAgentInteractionService` instance per provider. Both instances use the same application orchestration and differ only by transport and `CodingAgentProvider`.

The compatibility property `claudeCodeService` resolves the currently selected provider. Existing dispatcher/composition code therefore stays intact while the actual application service is provider-aware.

This avoids creating:

- `CodexDispatcher`
- `CodexInteractionService`
- a second approval flow
- a second command-result flow
- a second background-execution flow

## UI

Settings now contain:

- Coding Agent provider selector
- Claude Code CLI settings
- Codex CLI settings

The interaction mode label is provider-neutral: **Coding Agent (Local CLI)**.

The transcript link is provider-neutral: **Agent log**.

The internal `InteractionMode.CLAUDE_CODE` enum value remains temporarily as a compatibility id and should not be interpreted as the selected provider.

## Lifecycle

Both adapters are lazy and are shut down only if initialized.

`abortClaudeCode()` remains as a compatibility entry point but aborts any initialized Coding Agent transport.

`dispose()` shuts down both provider adapters before closing the shared transcript writer and cancelling the project coroutine scope.

## Codex settings

Codex settings include:

- binary path
- extra App Server arguments
- optional model
- optional reasoning effort
- read timeout
- start timeout

On npm-based Windows installations the binary may resolve to a `.cmd` shim, for example:

`C:\Users\<user>\AppData\Roaming\npm\codex.cmd`

Use `where.exe codex` or `(Get-Command codex).Source` to discover the effective path when IntelliJ does not inherit the same PATH as the terminal.

## Settings initialization regression

During wiring, the Settings page temporarily opened blank because new Coding Agent / Codex fields were declared after the class `init` block while `panel = buildPanel()` referenced them from inside `init`.

Kotlin initializes properties in declaration order, so those fields were not ready when the panel was built.

The fields were moved before the `init` block so the Settings panel is fully initialized before `buildPanel()` runs.

## Safety

Codex threads are started with:

- `approvalPolicy = never`
- `sandbox = read-only`

Cold `thread/resume` must repeat these overrides. Codex App Server otherwise restores the thread with its defaults: `on-request` + `workspaceWrite`.

This was verified against Codex App Server **0.147.0** and fixed in `CodexAppServerAdapter.resumeThread()`.

The important invariant is:

**Codex may reason about project context, but project mutation stays behind the MaxVibes modifications/commands boundary.**
