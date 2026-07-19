# Modification apply results are not fed back to the LLM

## Symptom(confirmed 2026 - 07 - 17, claude session 568e5 a7b)
Dialog 1: the model sent two RENAME_ELEMENT ops . Op 1(ScorchedEarthGame -> ArtilleryGame)
succeeded — file + class renamed, usages updated by the IDE.Op 2(ScorchedPhysics ->
ArtilleryPhysics) targeted `class[ScorchedPhysics]`, which does not exist (the file contains
        Point, Projectile and top - level launchProjectile in package `scorched`) — the op was not
        applied, yet the model reported both renames as done .

Turn 2 of the same session: the failure surfaced only by accident — the model requested a
SIGNATURES view of ArtilleryPhysics . kt and received "// ERROR: Could not render SIGNATURES
view: File not found". It then silently ignored the error and moved on without mentioning it.

## Root cause
        The protocol closes the loop for shell commands (`commandResults`) but not for modifications.After approval the plugin applies the batch and the model never learns per - op outcomes, so it
        (a) reports phantom successes to the user and (b) cannot retry or work around failures.

## Proposed fix
        1.Collect per -op outcomes at apply time: index, type, path, status =
    SUCCESS | FAILED(reason) | SKIPPED.2.Add a `modificationResults` request field: constant in InteractionRequestSchema, wiring in
        InteractionRequestBuilder, documentation in the system prompt.Same lifecycle as
`commandResults` — included in the next request; the model must react to failures instead
of assuming success.3.UI: a FAILED op should raise a warning notification . Verify what(if anything) was shown
for the ScorchedPhysics rename failure in dialog 1.

## Related
-psi - delete - element - silent - failure - in - batch.md — same family of silent batch failures
        -missing - requested - files - feedback.md — requestedViews already return inline "// ERROR:" strings;
modifications should follow the same explicit -feedback pattern
