# Step 8 — Tests and Codex Smoke Verification

## Automated coverage

The existing Claude Code characterization suite remains the main coverage for the shared Coding Agent application flow:

- send
- continuation
- approval
- requested views
- command results
- reset
- session transitions

New provider-specific coverage was added for:

- default Coding Agent provider compatibility (`CLAUDE_CODE`)
- provider options (`CLAUDE_CODE`, `CODEX`)
- provider policy
  - Claude Code: system prompt at process start
  - Codex: system prompt in MaxVibes request protocol
- Codex App Server parser
  - RPC responses and RPC errors
  - thread start notification
  - turn start/completion
  - agent-message deltas
  - authoritative completed messages
  - reasoning deltas
  - tool completion
  - unknown/malformed notifications
  - token usage

The full plugin suite was green after the integration changes.

## Real Codex smoke test

Verified manually on Windows with **Codex App Server 0.147.0**.

### 1. Initialize

`initialize` returned a successful JSON-RPC response and confirmed native Windows App Server operation.

### 2. Thread start

`thread/start` succeeded with:

- `approvalPolicy = never`
- `sandbox = read-only`

The result confirmed:

- approval policy `never`
- sandbox type `readOnly`
- network access disabled

`thread/started` was emitted as expected.

### 3. Turn and streaming

A real `turn/start` requested the exact token `MAXVIBES_CODEX_SMOKE_OK`.

Observed lifecycle:

- RPC response for `turn/start`
- `turn/started`
- `item/started`
- multiple `item/agentMessage/delta` events
- authoritative `item/completed`
- `thread/tokenUsage/updated`
- `turn/completed` with status `completed`

The final message was exactly `MAXVIBES_CODEX_SMOKE_OK`.

### 4. Token usage regression found by smoke

Real Codex 0.147.0 reports cumulative counters under:

`params.tokenUsage.total`

The original parser expected counters directly under `tokenUsage` and therefore produced `0/0` stats for the real payload.

`CodexAppServerLineParser.parseTokenUsage()` was fixed to support both the nested real payload and the earlier flat shape.

### 5. Cold resume and history

A fresh App Server process resumed the persisted thread with `thread/resume`.

The resumed response contained the previous completed turn. A follow-up turn asking for the previous token returned `MAXVIBES_CODEX_SMOKE_OK`, proving that conversation history survives App Server restart.

### 6. Resume safety regression found by smoke

A bare `thread/resume` restored the thread using Codex defaults:

- `approvalPolicy = on-request`
- sandbox `workspaceWrite`

This is unsafe for the MaxVibes architecture because project edits must go through MaxVibes modifications, not Codex filesystem writes.

The real server was then tested with resume overrides:

- `approvalPolicy = never`
- `sandbox = read-only`

Codex accepted them and returned the expected `never` + `readOnly` state.

`CodexAppServerAdapter.resumeThread()` now sends these overrides explicitly.

### 7. Real MaxVibes end-to-end flow

The final smoke was run through the actual plugin, not a manual JSON-RPC shell session.

Observed flow:

1. MaxVibes launched the configured Windows `codex.cmd` shim.
2. App Server initialized successfully.
3. MaxVibes started a read-only / never Codex thread.
4. The MaxVibes protocol and Codex system prompt were sent through `turn/start`.
5. Codex streamed a JSON response.
6. Codex correctly returned `requestedViews` rather than reading project files directly.
7. MaxVibes parsed the response and entered `Awaiting Approve`.

This verified the intended safety boundary in the real product flow: Codex requests project context through MaxVibes instead of bypassing PSI/project controls.

## Step result

The complete production transport path is verified:

`initialize -> thread/start|thread/resume -> turn/start -> streamed events -> authoritative response -> turn/completed`

Codex remains read-only across both new and resumed sessions.
