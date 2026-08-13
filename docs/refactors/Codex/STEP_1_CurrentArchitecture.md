# Step 1 — Current Claude Code Architecture

## Status

DONE.

## Purpose

Map the existing Claude Code vertical slice before introducing Codex or extracting shared abstractions .

## Existing flow

`text
InteractionMode.CLAUDE_CODE
-> ClaudeCodeDispatcher
-> ClaudeCodeInteractionService
-> ClaudeCodeTurnExecutor
-> ClaudeCodePort
-> ClaudeCodeProcessAdapter
-> claude CLI
`

The surrounding application flow already contains reusable pieces :

-`InteractionRequestBuilder`
-`InteractionProtocolCodec`
-`ProtocolConverter`
-requested - view resolution
        -modification approval and application
        -questions and commands
-workspace reconstruction
        -session state transitions
-normalized stream events and live UI

## Provider - specific areas

        The Claude adapter owns :

-Claude CLI command - line flags
        -process lifecycle
        -stream - JSON wire format
-Claude resume semantics
-system - prompt delivery
        -Claude - specific rate -limit and thinking event parsing

## Conclusion

The existing vertical slice should not be copied for Codex.Most application semantics can become a shared coding -agent flow while transport details remain provider -specific.
