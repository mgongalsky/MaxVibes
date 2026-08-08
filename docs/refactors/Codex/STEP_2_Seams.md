# Step 2 — Provider Seams

## Status

DONE.

## Generic responsibilities

        These responsibilities belong above the provider boundary:

-building MaxVibes requests
-gathering requested code views
        -interpreting `InteractionResponse`
        -modification approval
        -question and command handling
        -plan snapshots
        -workspace history
        -common dialog state transitions
        -normalized live activity

## Provider - specific responsibilities

        These responsibilities stay inside provider adapters :

-executable availability
        -process or server lifecycle
        -raw wire protocol
-remote session or thread identifiers
-resume behavior
        -interruption mechanism
        -provider - specific statistics
        -provider - specific stream parsing
-method of delivering the MaxVibes system instruction

## Chosen seam

        The shared transport boundary is `CodingAgentCliPort` .

Claude Code implements it through `ClaudeCodeProcessAdapter` .
Codex will implement it through `CodexAppServerAdapter` .

The adapters must not expose raw provider events to the application layer . They map provider events into a normalized coding - agent stream model.

## Important non -abstraction

Do not create a common raw stream parser . Claude Code uses its own stream - JSON dialect while Codex App Server uses JSON - RPC.Sharing their parser would couple unrelated protocols.
