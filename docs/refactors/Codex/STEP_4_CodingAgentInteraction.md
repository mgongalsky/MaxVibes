# Step 4 — Generic CodingAgent Application Flow

## Status

IN PROGRESS.

## Goal

Move provider-independent orchestration out of Claude-specific application names without changing behavior.

## Cut 1 — transport-facing flow models

Introduced physical generic models:

- `CodingAgentTurnCommand`
- `ReceivedCodingAgentTurn`

Temporary source-compatible aliases remain for the previous Claude-specific model names.

Existing orchestration now consumes the generic transport-facing models and `CodingAgentCliPort` / `CodingAgentCliSendResult` directly.

## Cut 2 — workspace result hierarchy

Physically rename:

- `ClaudeCodeWorkspaceResult` -> `CodingAgentWorkspaceResult`

All production and test usages are migrated in the same cut.

No typealias is used for this hierarchy. Kotlin aliases do not expose nested classifiers such as `Ready` and `Failure`, so a physical rename with synchronized caller migration is the safe pattern.

This cut is intentionally small and serves as the template for the remaining internal sealed hierarchies.

## Deferred hierarchies

The following remain Claude-named until their own characterized cuts:

- `ClaudeCodeApprovalOutcome`
- `ClaudeCodeTurnExecutionResult`
- `ClaudeCodeStepResult`
- `ClaudeCodeResponseProcessor.Context`
- `ClaudeCodeResponseProcessor.Intent`

## Migration rule

For top-level data classes without nested classifiers, compatibility aliases are acceptable.

For sealed classes, sealed interfaces, or objects whose nested types are referenced by callers, migrate the physical declaration and all usages together. Do not rely on a typealias.

## Next cut

After `CodingAgentWorkspaceResult` is verified green, migrate `ClaudeCodeApprovalOutcome` and then `ClaudeCodeTurnExecutionResult` using the same pattern.
