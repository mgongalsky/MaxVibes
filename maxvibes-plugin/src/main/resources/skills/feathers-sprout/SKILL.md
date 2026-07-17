---
name: feathers-sprout
description: Sprout Method (Feathers) — add new logic as a separate tested function
applies-to: function
attach-element: true
editor-template: |
Use Sprout Method (Feathers) on {{elementName}} ({{elementPath}}).
New logic to add: <describe here>
---
Implement the new logic as a NEW separate function with its own unit test; change the original function minimally — ideally a single call line.
Prefer CREATE_ELEMENT for the sprout function and one REPLACE_ELEMENT for the host. Do not restructure the host beyond the insertion.
