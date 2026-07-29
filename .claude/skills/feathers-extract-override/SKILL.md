---
name: feathers-extract-override
description: Extract and Override (Feathers) — make a function testable by extracting its hard dependency
applies-to: function
attach-element: true
editor-template: |
Prepare {{elementPath}} for testing via Extract and Override (Feathers).
---
Identify the hardest dependency in the body (I/O, time, static, process, UI). Extract it into a protected open method with a clear name.
Show a testing subclass that overrides the extracted method, and one test using that subclass. Behavior of the production path must stay identical.
