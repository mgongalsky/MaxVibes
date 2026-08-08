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

### Cut 2 — workspace result hierarchy

Physically migrated:

- `ClaudeCodeWorkspaceResult` -> `CodingAgentWorkspaceResult`

### Cut 3 — approval outcome hierarchy

Physically migrated:

- `ClaudeCodeApprovalOutcome` -> `CodingAgentApprovalOutcome`

### Cut 4 — turn execution result hierarchy

Physically migrated:

- `ClaudeCodeTurnExecutionResult` -> `CodingAgentTurnExecutionResult`

### Cut 5 — response processor

Introduced canonical provider-independent:

- `CodingAgentResponseProcessor`
- `CodingAgentResponseProcessor.Context`
- `CodingAgentResponseProcessor.Intent`
- `CodingAgentResponseProcessor.Outcome`

## Step-result migration constraint

`ClaudeCodeStepResult` is used as a cross-module application-to-plugin contract and its nested variants have many consumers.

A physical rename was attempted, but using Kotlin import aliases as a migration shortcut is not supported by the current MaxVibes `ADD_IMPORT` PSI operation. The operation treated the alias clause as part of the imported reference and produced invalid imports.

The hierarchy therefore remains physically named `ClaudeCodeStepResult` for now.

A provider-neutral type alias is available for signatures:

`typealias CodingAgentStepResult = ClaudeCodeStepResult`

Because Kotlin typealiases do not expose nested classifiers as a namespace, constructors and type checks must still use `ClaudeCodeStepResult.Completed`, `ClaudeCodeStepResult.Error`, and the other physical nested variants until all usages are migrated directly in one coordinated cut.

## Migration rule

Do not use Kotlin typealias or import-alias shortcuts to migrate sealed hierarchies whose nested classifiers are referenced widely.

For such hierarchies:

1. collect all usages;
2. load all affected consumers;
3. migrate references directly;
4. physically rename the hierarchy in the same cut.

## Remaining Step 4 work

The main provider-independent services still carry Claude-specific class names:

- `ClaudeCodeInteractionService`
- `ClaudeCodeResponseHandler`
- `ClaudeCodeApprovalService`
- `ClaudeCodeWorkspaceService`
- `ClaudeCodeTurnExecutor`
- `ClaudeCodeViewResolver`

The physical `ClaudeCodeStepResult` rename is also still pending.

Provider-specific transport, logging, prompt and persistence details remain explicit for Steps 5–6.
