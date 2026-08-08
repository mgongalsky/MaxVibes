# Step 4 — Generic CodingAgent Application Flow

## Status

IN PROGRESS.

## Goal

Move provider-independent orchestration out of Claude-specific application names without changing behavior.

## Completed cuts

### Cut 1 — transport-facing flow models

Introduced:

- `CodingAgentTurnCommand`
- `ReceivedCodingAgentTurn`

Existing orchestration consumes `CodingAgentCliPort` and `CodingAgentCliSendResult` directly.

### Cut 2 — workspace result hierarchy

Physically migrated:

- `ClaudeCodeWorkspaceResult` -> `CodingAgentWorkspaceResult`

### Cut 3 — approval outcome hierarchy

Physically migrated:

- `ClaudeCodeApprovalOutcome` -> `CodingAgentApprovalOutcome`

### Cut 4 — turn execution result hierarchy

Physically migrated:

- `ClaudeCodeTurnExecutionResult` -> `CodingAgentTurnExecutionResult`

## Cut 5 — response processor

Introduce the canonical provider-independent processor:

- `CodingAgentResponseProcessor`
- `CodingAgentResponseProcessor.Context`
- `CodingAgentResponseProcessor.Intent`
- `CodingAgentResponseProcessor.Outcome`

`ClaudeCodeResponseHandler` and processor characterization tests migrate to the generic object in the same cut.

The old `ClaudeCodeResponseProcessor` remains only as a deprecated forwarding facade for simple `process` calls. It does not own compatibility copies of the nested classifiers.

## Migration rule

For top-level data classes without nested classifiers, compatibility aliases are acceptable.

For sealed classes, sealed interfaces, or objects whose nested classifiers are referenced directly, migrate the physical declaration and all nested-type usages together. Do not rely on a typealias.

## Main remaining application result

The major remaining provider-specific result hierarchy is:

- `ClaudeCodeStepResult`

It is used across the application service layer, tests and UI-facing integration, so it requires a dedicated characterized cut.

## Next cut

Physically migrate `ClaudeCodeStepResult` to `CodingAgentStepResult` with all direct compile-time usages in one batch. After that, rename the provider-independent service classes themselves to `CodingAgent*` while keeping Claude-specific transport and persistence details explicit until later steps.
