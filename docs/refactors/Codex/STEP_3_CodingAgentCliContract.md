# Step 3 — Generic CodingAgent CLI Contract

## Status

IN PROGRESS.

## Purpose

Create a provider-independent transport seam before changing application orchestration or adding Codex.

## Canonical contract

- `CodingAgentCliPort`
- `CodingAgentCliError`
- `CodingAgentCliSendResult`

The contract preserves the semantics already required by the Claude Code implementation:

- availability check
- lazy start or resume
- opaque remote session identifier
- one MaxVibes request per turn
- normalized successful result
- shutdown
- abort

## Compatibility strategy

`ClaudeCodePort` and `ClaudeCodeSendResult` can safely alias the new generic types directly.

The error hierarchy needs a temporary reverse alias:

- `CodingAgentCliError` -> `ClaudeCodeError`

`ClaudeCodeError` temporarily remains the physical sealed class that owns `BinaryNotFound`, `ResumeFailed`, `Aborted`, and the other nested variants. Kotlin type aliases do not expose nested classifiers through the alias name, so making `ClaudeCodeError` an alias immediately would break existing `ClaudeCodeError.ResumeFailed` call sites.

A later characterized migration will replace those call sites with `CodingAgentCliError.*`; only then can the sealed hierarchy itself move under the canonical generic name.

Legacy `AgentCli*` names also remain temporary aliases to the corresponding `CodingAgentCli*` names.

## Definition of done

- canonical `CodingAgentCli*` contract is available to new code
- existing Claude Code behavior remains unchanged
- application tests are green
- existing `ClaudeCodeError.X` callers remain source-compatible
- no production orchestration has been duplicated

## Next step

After the contract is green, migrate application orchestration toward `CodingAgentInteractionService` and generic turn/result names while keeping provider adapters explicit.
