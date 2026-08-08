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

Physically renamed:

- `ClaudeCodeWorkspaceResult` -> `CodingAgentWorkspaceResult`

All production and test usages were migrated together. No typealias is used because nested classifiers such as `Ready` and `Failure` are referenced directly.

## Cut 3 — approval outcome hierarchy

Physically rename:

- `ClaudeCodeApprovalOutcome` -> `CodingAgentApprovalOutcome`

All production and test usages move in the same cut. The owning `ClaudeCodeApprovalService` remains unchanged for now; service-class renaming is deferred until the internal models are generic.

## Migration rule

For top-level data classes without nested classifiers, compatibility aliases are acceptable.

For sealed classes, sealed interfaces, or objects whose nested types are referenced by callers, migrate the physical declaration and all usages together. Do not rely on a typealias.

## Deferred hierarchies

The following remain Claude-named until their own characterized cuts:

- `ClaudeCodeTurnExecutionResult`
- `ClaudeCodeStepResult`
- `ClaudeCodeResponseProcessor.Context`
- `ClaudeCodeResponseProcessor.Intent`

## Next cut

After `CodingAgentApprovalOutcome` is verified green, migrate `ClaudeCodeTurnExecutionResult` using the same physical-rename pattern.
