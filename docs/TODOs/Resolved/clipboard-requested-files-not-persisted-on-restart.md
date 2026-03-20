# TODO: Requested files lost after IDE restart

## Problem

When the LLM responds with `requestedFiles`, those paths are saved into the last
ASSISTANT message in the domain session via `persistRequestedFilesIntoDomain()`.
This works correctly within a single IDE session.

However, after an IDE restart the in-memory `ClipboardSessionState` (`sessionState`)
is gone. `redoLastRequest` Scenario B rebuilds the workspace from the domain, and
it does read `requestedFiles` from the last ASSISTANT message — **but only if that
field was actually persisted to XML before the restart**.

The root issue is that `ChatMessage.requestedFiles` may not be serialized to XML at
all (or may be serialized as an empty list) depending on how `ChatHistoryService`
handles the field. If the XML does not contain the requested paths, Scenario B falls
back to `emptyList()`, and the regenerated JSON will be missing the files the LLM
asked for.

## Impact

- After IDE restart, pressing **Copy JSON** in an active clipboard session produces
a JSON without the files that were gathered in the previous turn.
- The LLM receives no file content and will likely re-request the same files,
breaking the dialog flow.

## Investigation starting points

1. Check `ChatHistoryService` (XML persistence adapter): verify that the
`requestedFiles: List<String>` field of `ChatMessage` is included in the XML
schema and round-trips correctly through serialize/deserialize.
2. Check `ChatMessage` domain model: confirm the field has a sensible default
(`emptyList()`) so older XML without it deserializes without error.
3. Add a smoke test: persist a session with a non-empty `requestedFiles`, reload
it, and assert the field survives the round-trip.

## Notes

- `persistRequestedFilesIntoDomain()` in `ClipboardInteractionService` is the write
path — it looks correct.
- The read path is in `redoLastRequest` Scenario B:
```kotlin
val lastRequestedFiles = session.messages
.lastOrNull { it.role == MessageRole.ASSISTANT && it.requestedFiles.isNotEmpty() }
?.requestedFiles
?: emptyList()
```
If the XML round-trip is broken, this always returns `emptyList()`.
