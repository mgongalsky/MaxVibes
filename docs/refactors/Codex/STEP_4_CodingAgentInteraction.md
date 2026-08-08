# Step 4 — Generic CodingAgent Application Flow

## Status

PENDING until Step 3 is verified green.

## Goal

Move provider -independent orchestration out of Claude - specific application names without changing behavior .

## Target names

        Likely shared types:

-`CodingAgentInteractionService`
-`CodingAgentTurnExecutor`
-`CodingAgentTurnCommand`
-`ReceivedCodingAgentTurn`
-`CodingAgentStepResult`
-`CodingAgentApprovalService`
-`CodingAgentWorkspaceService`
-`CodingAgentViewResolver`

Provider - specific types remain explicit :

-`ClaudeCodeProcessAdapter`
-`StreamJsonEventParser`
-future `CodexAppServerAdapter`
        -future `CodexAppServerEventParser`

## Migration rule

        Do not perform a single repository -wide rename . Migrate one collaboration boundary at a time and keep compatibility aliases where they materially reduce blast radius .

Before renaming existing widely -used stream or UI types, inspect usages and migrate them as a separate characterized cut .
