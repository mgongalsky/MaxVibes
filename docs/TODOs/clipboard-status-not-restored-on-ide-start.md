# Bug: Clipboard session status not honoured on IDE startup

## Symptom

When the IDE is reopened with an existing session that has a persisted `clipboardStatus`
of `AWAITING_PASTE` (or `SESSION_ACTIVE`), the UI and / or service behave as if the session
is in `IDLE`.The user cannot paste a response or continue the dialog without restarting
the session manually.

## Expected behaviour

        On IDE startup the clipboard status is read from persisted XML and the UI renders
correctly(`modeIndicator` shows `⏳ Paste response` or `📋 Active`).`ClipboardInteractionService.handleUserInput` routes correctly based on the restored status .

## Suspected cause

        -`ClipboardInteractionService.sessionState`(in - memory workspace) is always `null` on
startup — it is never restored from persistence.If `handleUserInput` is called while
    `sessionState == null` but `clipboardStatus != IDLE`, most paths return
`ClipboardStepResult.Error("No active clipboard session")`.
-`sessionStateOwner` is also `null`, so `redoLastRequest` scenario B might be the
correct recovery path, but it is not triggered automatically .

## Possible fix directions

1.On session load(`loadCurrentSession`), detect a non - IDLE `clipboardStatus` and
proactively rebuild `sessionState` from domain(similar to `redoLastRequest` Scenario B).2.Or: on startup, reset any persisted `AWAITING_PASTE` / `SESSION_ACTIVE` back to `IDLE`
(simpler, but loses session continuity).
3.Or: show a banner / system bubble informing the user that the session was interrupted
        and offering a "Redo last request" action .

## Related files

        -`ClipboardInteractionService.kt` — `sessionState`, `sessionStateOwner`, `redoLastRequest`
-`ChatPanel.kt` — `loadCurrentSession()`
-`ChatHistoryService.kt` — XML persistence of `clipboardStatus`
