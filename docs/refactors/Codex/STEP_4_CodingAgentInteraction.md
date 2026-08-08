# Step 4 — Generic CodingAgent Application Flow

## Status

IN PROGRESS.

## Goal

Move provider-independent orchestration out of Claude-specific application names without changing behavior.

## Cut 1 — transport-facing flow models

Introduce physical generic models:

- `CodingAgentTurnCommand`
- `ReceivedCodingAgentTurn`

Keep temporary source-compatible aliases:

- `ClaudeCodeTurnCommand` -> `CodingAgentTurnCommand`
- `ReceivedClaudeTurn` -> `ReceivedCodingAgentTurn`

Migrate the existing facade collaborators to consume the generic models:

- `ClaudeCodeTurnExecutor`
- `ClaudeCodeInteractionService`
- `ClaudeCodeApprovalService`
- `ClaudeCodeResponseHandler`

Also expose `CodingAgentCliPort` and `CodingAgentCliSendResult` directly at the application transport boundary.

This cut intentionally does not rename the owning services yet. Runtime behavior, Claude session persistence, request construction, logging and response semantics remain unchanged.

## Why sealed results are deferred

The following types own nested classifiers and cannot be safely migrated through a simple Kotlin typealias:

- `ClaudeCodeStepResult`
- `ClaudeCodeResponseProcessor.Context`
- `ClaudeCodeResponseProcessor.Intent`
- `ClaudeCodeTurnExecutionResult`
- `ClaudeCodeApprovalOutcome`
- `ClaudeCodeWorkspaceResult`

They require a characterized migration of their call sites, similar to the earlier `ClaudeCodeError` issue.

## Next cuts

1. Migrate one sealed application result hierarchy with all usages in one compile-safe batch.
2. Rename provider-independent service classes to `CodingAgent*`.
3. Leave provider-specific transport, persistence and prompt semantics for Step 5 and Step 6.
