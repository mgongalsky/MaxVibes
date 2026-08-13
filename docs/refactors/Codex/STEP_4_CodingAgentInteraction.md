# Step 4 — Generic CodingAgent Application Flow

## Status

DONE.

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

### Cut 6 — response handling

Physically migrated:

- `ClaudeCodeResponseHandler` -> `CodingAgentResponseHandler`

### Cut 7 — requested-view resolution

Canonical implementation is now:

- `CodingAgentViewResolver`

A temporary internal compatibility alias remains for old source references.

### Cut 8 — approval flow

Physically migrated:

- `ClaudeCodeApprovalService` -> `CodingAgentApprovalService`

### Cut 9 — application facade

Canonical application facade is now:

- `CodingAgentInteractionService`

Production composition and `ClaudeCodeDispatcher` depend on the canonical generic facade. Claude Code remains the currently wired provider through `CodingAgentCliPort`.

## Provider boundary after Step 4

Provider-independent application flow:

- `CodingAgentInteractionService`
- `CodingAgentResponseHandler`
- `CodingAgentApprovalService`
- `CodingAgentViewResolver`
- `CodingAgentResponseProcessor`
- `CodingAgentTurnCommand`
- `ReceivedCodingAgentTurn`
- `CodingAgentWorkspaceResult`
- `CodingAgentApprovalOutcome`
- `CodingAgentTurnExecutionResult`

Still intentionally Claude-specific below or adjacent to the seam:

- `ClaudeCodeWorkspaceService`
- `ClaudeCodeTurnExecutor`
- `ClaudeCodeSessionLogPort`
- Claude-specific prompt/session persistence details
- Claude-specific UI labels and dispatcher naming

Those concerns are handled by Steps 5–7 rather than generalized prematurely.

## Step-result migration constraint

`ClaudeCodeStepResult` is used as a cross-module application-to-plugin contract and its nested variants have many consumers.

A physical rename was attempted, but using Kotlin import aliases as a migration shortcut is not supported by the current MaxVibes `ADD_IMPORT` PSI operation. The operation treated the alias clause as part of the imported reference and produced invalid imports.

The hierarchy therefore remains physically named `ClaudeCodeStepResult` for now.

A provider-neutral type alias is available for signatures:

`typealias CodingAgentStepResult = ClaudeCodeStepResult`

Because Kotlin typealiases do not expose nested classifiers as a namespace, constructors and type checks must still use `ClaudeCodeStepResult.Completed`, `ClaudeCodeStepResult.Error`, and the other physical nested variants until all usages are migrated directly in one coordinated cut.

## Migration rules learned

1. Do not use Kotlin import aliases through `ADD_IMPORT`; the current PSI operation does not support them.
2. Do not use typealias as a namespace replacement for sealed hierarchies with nested classifiers.
3. Constructor PSI replacement is fragile; prefer property/class element changes or `REPLACE_FILE` when changing primary-constructor declarations.
4. For wide physical renames, collect usages first and migrate all compile-time consumers in the same cut.

## Result

The application orchestration now has a provider-independent coding-agent facade while Claude-specific runtime concerns remain explicit below the transport/session boundary.

Step 5 can now generalize persisted session metadata and prompt selection without duplicating the application flow.
