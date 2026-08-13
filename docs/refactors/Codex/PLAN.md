# Codex / Generic Coding-Agent Integration

## Goal

Add OpenAI Codex alongside Claude Code without creating a second copy of the existing interaction stack.

The final architecture has one provider-independent coding-agent application flow and separate provider adapters.

## Terminology

- Coding agent — generic product category. Claude Code and Codex are coding agents.
- Coding-agent provider — selected implementation, for example Claude Code or Codex.
- Coding-agent CLI — local transport boundary used by MaxVibes to communicate with a coding-agent runtime.
- Provider adapter — provider-specific implementation of the common transport contract.

Canonical generic layer:

- `CodingAgentCliPort`
- `CodingAgentCliError`
- `CodingAgentCliSendResult`
- `CodingAgentProvider`
- `CodingAgentInteractionService`
- `AgentStreamEvent`
- `AgentStreamHub`
- `CodingAgentSessionRef`

Provider-specific layer:

- `ClaudeCodeProcessAdapter`
- `StreamJsonEventParser`
- `CodexAppServerAdapter`
- `CodexAppServerLineParser`

Do not use `LLM` for this abstraction. Claude Code and Codex expose session lifecycle, streaming, interruption and coding-agent runtime behavior beyond a plain model call.

## Architectural rule

Generic above the transport seam, provider-specific below it.

`UI -> CodingAgentInteractionService -> CodingAgentCliPort -> provider adapter`

Both adapters emit normalized stream events and exchange the common MaxVibes request/response protocol.

Raw Claude stream-JSON and Codex JSON-RPC parsing remain separate.

## Final state

The integration is complete.

Claude Code and Codex now share the same application orchestration. Provider-specific behavior is isolated in transport, parser, prompt-delivery policy and CLI settings.

The existing `InteractionMode.CLAUDE_CODE` value remains as a backward-compatible persisted id, while the UI exposes the mode as **Coding Agent** and stores the concrete provider separately.

Codex uses persistent App Server transport and was smoke-tested on Windows against Codex 0.147.0.

Verified real lifecycle:

`initialize -> thread/start|thread/resume -> turn/start -> streamed events -> authoritative response -> turn/completed`

Cold resume restores conversation history.

Codex start and resume explicitly enforce:

- `approvalPolicy = never`
- `sandbox = read-only`

Real smoke testing also identified and fixed nested token-usage parsing and resume safety-policy loss.

## Refactor sequence

1. Current architecture — map the existing Claude Code vertical slice. DONE.
2. Seams — identify generic vs provider-specific responsibilities. DONE.
3. CodingAgent CLI contract — introduce provider-independent transport types with compatibility aliases. DONE.
4. CodingAgent interaction — migrate application orchestration from Claude-specific names to a shared coding-agent flow. DONE.
5. Session and prompts — replace Claude-only persisted session metadata and prompt lookup with provider-aware structures while preserving XML backward compatibility. DONE.
6. Codex adapter — implement Codex App Server transport and map JSON-RPC notifications to normalized coding-agent events. DONE.
7. Wiring and UI — add provider selection without duplicating dispatcher, background execution or approval flows. DONE.
8. Tests — preserve characterization coverage, add provider contract tests and run Codex smoke tests. DONE.
9. Extension guide — document what a future coding-agent adapter must implement. DONE.

## Constraints preserved

- Claude Code stayed green during migration.
- No second `CodexInteractionService` / `CodexDispatcher` stack was introduced.
- No provider-specific Codex session field was added independently to `ChatSession`.
- Existing Claude persistence remains supported through compatibility fields/aliases.
- Raw provider protocols were not prematurely generalized.
- Coding-agent runtimes remain behind the MaxVibes project-edit boundary.

## Documentation

- `STEP_7_Wiring.md` — provider selection, service/UI wiring and lifecycle.
- `STEP_8_Tests.md` — automated coverage and real Codex smoke findings.
- `STEP_9_ExtensionGuide.md` — recipe for adding another coding-agent provider.

## Final checkpoint

Suggested commit:

`docs: complete Codex integration guide`
