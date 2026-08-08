# Codex / Generic Coding -Agent Integration

## Goal

Add OpenAI Codex alongside Claude Code without creating a second copy of the existing interaction stack .

The target architecture has one provider -independent coding -agent application flow and separate provider adapters.

## Terminology

-* * Coding agent * * — generic product category . Claude Code and Codex are coding agents .
-* * Agent provider * * — selected implementation / vendor, for example Claude Code or Codex.
-* * Agent CLI * * — local transport boundary used by MaxVibes to communicate with a coding agent runtime .
-* * Agent adapter * * — provider -specific implementation of the common transport contract.Preferred generic names:

-`AgentCliPort`
-`AgentCliError`
-`AgentCliSendResult`
-`AgentProvider`
-`AgentInteractionService`
-`AgentStreamEvent`
-`AgentStreamHub`
-`AgentSessionRef` or equivalent provider -aware session metadata

Provider - specific names stay explicit :

-`ClaudeCodeProcessAdapter`
-`StreamJsonEventParser`
-`CodexAppServerAdapter`
-`CodexAppServerEventParser`

Do not use `LLM` for this abstraction . Claude Code and Codex expose session lifecycle, streaming, interruption and agent runtime behavior beyond a plain model call .

## Architectural rule

        Generic above the transport seam, provider - specific below it.

`text
UI
-> AgentInteractionService
-> AgentCliPort
-> ClaudeCodeProcessAdapter
-> CodexAppServerAdapter

Both adapters emit:
-> AgentStreamEvent

Both adapters exchange MaxVibes protocol objects :
-> ClipboardRequest / InteractionResponse
`

The raw Claude stream -JSON parser and Codex JSON - RPC parser must remain separate.Their common output is the normalized `AgentStreamEvent` model, not a shared wire parser.

## Current state

        The first compatibility seam is complete and tests are green :

-added `AgentCliPort`
        -added `AgentCliError`
        -added `AgentCliSendResult`
        -retained temporary `ClaudeCode*` typealiases

        This is intentionally runtime - neutral.Existing Claude Code behavior is unchanged .

## Refactor sequence

        1.* * Current architecture * * — map the existing Claude Code vertical slice . DONE .
2.* * Seams * * — identify generic vs provider -specific responsibilities . DONE .
3.* * Agent CLI contract * * — introduce provider -independent transport types with Claude compatibility aliases.DONE.4.* * Agent interaction * * — migrate the application orchestration from Claude - specific names to a shared agent flow.5.* * Session and prompts * * — replace Claude -only persisted session metadata and prompt lookup with provider - aware structures while preserving XML backward compatibility .
6.* * Codex adapter * * — implement Codex App Server transport and map JSON - RPC notifications to `AgentStreamEvent` .
7.* * Wiring and UI * * — add provider selection without duplicating dispatcher / background / approve flows.8.* * Tests * * — run characterization tests for the generic flow plus provider contract tests and Codex smoke tests.9.* * Extension guide * * — document what a future coding -agent adapter must implement .

## Constraints

-Keep Claude Code green after every step.
-No second full `CodexInteractionService` / `CodexDispatcher` stack.
-No provider -specific fields such as `codexThreadId` added independently to `ChatSession`.
-Preserve old XML sessions during persistence migration.
-Do not generalize raw provider protocols prematurely.
-Prefer small compatibility seams over one large rename .

## Commit checkpoints

        The completed generic transport seam is worth committing independently before migrating the application layer.Suggested commit :

`refactor: introduce generic agent CLI contract`
