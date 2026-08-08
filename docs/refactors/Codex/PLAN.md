# Codex / Generic Coding-Agent Integration

## Goal

Add OpenAI Codex alongside Claude Code without creating a second copy of the existing interaction stack.

The target architecture has one provider-independent coding-agent application flow and separate provider adapters.

## Terminology

- Coding agent — generic product category. Claude Code and Codex are coding agents.
- Coding-agent provider — selected implementation, for example Claude Code or Codex.
- Coding-agent CLI — local transport boundary used by MaxVibes to communicate with a coding-agent runtime.
- Provider adapter — provider-specific implementation of the common transport contract.

Preferred generic names:

- `CodingAgentCliPort`
- `CodingAgentCliError`
- `CodingAgentCliSendResult`
- `CodingAgentProvider`
- `CodingAgentInteractionService`
- `CodingAgentStreamEvent`
- `CodingAgentStreamHub`
- `CodingAgentSessionRef` or equivalent provider-aware session metadata

Provider-specific names stay explicit:

- `ClaudeCodeProcessAdapter`
- `StreamJsonEventParser`
- `CodexAppServerAdapter`
- `CodexAppServerEventParser`

Do not use `LLM` for this abstraction. Claude Code and Codex expose session lifecycle, streaming, interruption and coding-agent runtime behavior beyond a plain model call.

## Architectural rule

Generic above the transport seam, provider-specific below it.

`text
UI
-> CodingAgentInteractionService
-> CodingAgentCliPort
-> ClaudeCodeProcessAdapter
-> CodexAppServerAdapter

Both adapters emit normalized coding-agent stream events.
Both adapters exchange the common MaxVibes request/response protocol.
`

The raw Claude stream-JSON parser and Codex JSON-RPC parser remain separate. Their common output is a normalized coding-agent event model, not a shared wire parser.

## Current state

The first compatibility seam exists and the application tests were green before the terminology adjustment.

Canonical names are now being changed from `AgentCli*` to `CodingAgentCli*`. Temporary aliases preserve compatibility while the rest of the application migrates incrementally.

## Refactor sequence

1. Current architecture — map the existing Claude Code vertical slice. DONE.
2. Seams — identify generic vs provider-specific responsibilities. DONE.
3. CodingAgent CLI contract — introduce provider-independent transport types with compatibility aliases.
4. CodingAgent interaction — migrate application orchestration from Claude-specific names to a shared coding-agent flow.
5. Session and prompts — replace Claude-only persisted session metadata and prompt lookup with provider-aware structures while preserving XML backward compatibility.
6. Codex adapter — implement Codex App Server transport and map JSON-RPC notifications to normalized coding-agent events.
7. Wiring and UI — add provider selection without duplicating dispatcher, background execution or approval flows.
8. Tests — preserve characterization coverage, add provider contract tests and run Codex smoke tests.
9. Extension guide — document what a future coding-agent adapter must implement.

## Constraints

- Keep Claude Code green after every step.
- No second full `CodexInteractionService` or `CodexDispatcher` stack.
- No provider-specific fields such as `codexThreadId` added independently to `ChatSession`.
- Preserve old XML sessions during persistence migration.
- Do not generalize raw provider protocols prematurely.
- Prefer small compatibility seams over one large rename.

## Commit checkpoints

The generic transport seam should be committed independently after the `CodingAgent*` terminology is applied and tests are green.

Suggested commit:

`refactor: introduce generic coding agent CLI contract`
