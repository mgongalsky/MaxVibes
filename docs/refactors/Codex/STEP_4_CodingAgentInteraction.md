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

Existing orchestration consumes the generic transport-facing models and `CodingAgentCliPort` / `CodingAgentCliSendResult` directly.

## Cut 2 — workspace result hierarchy

Physically renamed:

- `ClaudeCodeWorkspaceResult` -> `CodingAgentWorkspaceResult`

All production and test usages were migrated together.

## Cut 3 — approval outcome hierarchy

Physically renamed:

- `ClaudeCodeApprovalOutcome` -> `CodingAgentApprovalOutcome`

All production and test usages were migrated together.

## Cut 4 — turn execution result hierarchy

Physically rename:

- `ClaudeCodeTurnExecutionResult` -> `CodingAgentTurnExecutionResult`

The executor, interaction facade and executor tests migrate in the same cut. No typealias is used because callers reference the nested `Success` and `Failure` classifiers directly.

## Migration rule

For top-level data classes without nested classifiers, compatibility aliases are acceptable.

For sealed classes, sealed interfaces, or objects whose nested types are referenced by callers, migrate the physical declaration and all usages together. Do not rely on a typealias.

## Deferred hierarchy

The main remaining application-level Claude-specific result/parser pair is:

- `ClaudeCodeStepResult`
- `ClaudeCodeResponseProcessor`

These have a much larger blast radius and should be migrated as separate characterized cuts.

## Next cut

After `CodingAgentTurnExecutionResult` is verified green, migrate `ClaudeCodeStepResult` physically with all direct production/test/UI usages, then migrate `ClaudeCodeResponseProcessor` and finally rename the provider-independent service classes themselves.
