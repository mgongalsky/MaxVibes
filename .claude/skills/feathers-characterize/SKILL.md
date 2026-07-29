---
name: feathers-characterize
description: Characterization tests (Feathers) that pin current behavior before changing it
applies-to: function, class
attach-element: true
editor-template: |
Write characterization tests for {{elementPath}}.
Before writing, request USAGES for it via requestedViews to see real call patterns and edge cases worth pinning.
---
Write characterization tests in the Feathers sense: capture what the code ACTUALLY does now, not what it should do.
Rules: do not modify production code; put tests in src/test/kotlin of the owning module; JUnit5 + MockK; coEvery for suspend functions; runBlocking (never runTest).
Cover the golden path plus the edge cases visible at real call sites. Name each test after the observed behavior it pins.
