# Step 3 — Generic Agent CLI Contract

## Status

DONE.Existing application tests are green.

## Purpose

Create a provider - independent transport seam before changing application orchestration or adding Codex .

## Added contract

        -`AgentCliPort`
-`AgentCliError`
-`AgentCliSendResult`

The contract intentionally preserves the semantics already required by Claude Code:

-availability check
        -lazy start or resume
        -opaque remote session identifier
        -one MaxVibes request per turn
-normalized successful result
-shutdown
-abort

## Compatibility strategy

        The existing names remain temporary aliases :

-`ClaudeCodePort` -> `AgentCliPort`
-`ClaudeCodeError` -> `AgentCliError`
-`ClaudeCodeSendResult` -> `AgentCliSendResult`

This lets the existing Claude adapter and characterization tests continue compiling while callers migrate incrementally.

## Why this is the first cut

        It changes compile - time architecture without changing runtime behavior . That makes it a stable checkpoint before renaming services, modifying persistence, or introducing Codex JSON -RPC.

## Next step

        Migrate the application orchestration toward `AgentInteractionService` and generic turn / result names while leaving `ClaudeCodeProcessAdapter` provider - specific.
