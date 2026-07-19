---
name: write-unittest
description: Write unit tests for an element, grounded in its real call sites
applies-to: function, class
attach-element: true
editor-template: |
Write unit tests for {{elementPath}}. Request USAGES first to ground the cases in real call sites.
---
JUnit5 + MockK; coEvery + runBlocking for suspend functions (never runTest). Tests go to src/test/kotlin of the owning module.
Do not touch production code. Cover boundaries and failure paths, not just the golden path.
