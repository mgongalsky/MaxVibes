# Step 5 — Provider - aware sessions and prompts

        Status: **DONE * *

## Goal

Remove Claude -only assumptions from persisted coding - agent session metadata and system - prompt lookup while preserving existing Claude Code sessions and behaviour.

## Implemented

### Provider - aware session model

Added domain types:

-`CodingAgentProvider`
-`CLAUDE_CODE`
-`CODEX`
-`CodingAgentSessionRef`
-provider
-provider - defined remote session / thread id
        -`needsFullContext`

`ChatSession` now has nullable `codingAgentSession` as the generic persisted session reference.The existing `claudeCodeSessionId` and `claudeCodeNeedsFullContext` fields remain temporarily as a backward - compatibility bridge . No independent `codexThreadId` field was added.

### Compatibility helpers

        `ChatSession` owns the transition logic through :

-`resolvedCodingAgentSession(provider)`
-`withCodingAgentSession(sessionRef)`

Old Claude sessions can therefore be consumed through the generic API, while new Claude runtime writes keep the legacy fields synchronized during the migration period.If a generic session belongs to another provider, Claude legacy fields are not used as a fallback for that provider .

### XML persistence

        `XmlChatSession` persists generic coding -agent metadata using:

-`codingAgentProvider`
-`codingAgentRemoteSessionId`
-`codingAgentNeedsFullContext`

Persistence is intentionally dual - read / dual - write during migration:

-new generic fields are canonical when present;
-old XML containing only `claudeCodeSessionId` / `claudeCodeNeedsFullContext` is interpreted as `CLAUDE_CODE` metadata;
-Claude generic metadata is also written to the legacy attributes so older persisted sessions remain compatible.Existing XML without any coding - agent attributes still keeps the safe default of requiring full context.

### Runtime migration

        `ClaudeCodeTurnExecutor` now resolves persisted state through the provider -aware session API.It:

-resolves `CodingAgentProvider.CLAUDE_CODE` through `resolvedCodingAgentSession()`;
-passes the generic remote id into transport resume;
-writes resume -failure state through `withCodingAgentSession()`;
-persists observed remote session ids through `CodingAgentSessionRef`;
-preserves the existing full -context replay and resume -fallback behaviour .

The transport remains Claude -specific, as intended.Only persisted / application session semantics were generalized here.

### Provider - aware prompt lookup

`PromptPort` now exposes:

`codingAgentSystem(provider)`

The compatibility implementation delegates `CLAUDE_CODE` to the existing `claudeCodeSystem()` implementation .

`ClaudeCodeWorkspaceService` uses the provider -aware lookup instead of calling `claudeCodeSystem()` directly.The existing Claude system prompt remains provider - specific.It explicitly describes Claude Code CLI behaviour and disabled Claude built - in tools, so it must not be reused for Codex.A Codex -specific prompt will be introduced with the Codex adapter rather than guessed during this refactor .

### Tests

The thinking -flow MockK fixture was migrated to stub:

`codingAgentSystem(CodingAgentProvider.CLAUDE_CODE)`

Final verification after the Step 5 cuts :

-`maxvibes-application`: green
-`maxvibes-plugin`: green
-full test suite: green

## Intentionally retained

        The following Claude - specific pieces remain by design:

-`ClaudeCodeTurnExecutor` class name and Claude transport errors / messages;
-Claude Code process adapter and wire protocol;
-`ClaudeCodeSessionLogPort`;
-Claude - specific system -prompt resource;
-legacy `ChatSession.claudeCodeSessionId` and `claudeCodeNeedsFullContext` fields while XML compatibility is required .

These do not block Codex because the application -level session and prompt seams are now provider -aware.

## Result

The application can now represent persisted remote sessions and select system prompts by coding -agent provider without adding Codex - specific state to `ChatSession` .

Next: implement the Codex transport adapter against the existing `CodingAgentCliPort`, using Codex App Server session / thread semantics below the provider seam .
