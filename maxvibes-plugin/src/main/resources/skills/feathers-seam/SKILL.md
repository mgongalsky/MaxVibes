---
name: feathers-seam
description: Seam analysis (Feathers) — find where to break dependencies, no code changes
applies-to: function, class
attach-element: true
editor-template: |
Analyze {{elementPath}} for seams (Feathers). Analysis only — no modifications, no commands.
Also request USAGES and CALLERS for it to map the dependency pressure.
---
List every hard dependency of the element (I/O, static calls, constructors, time, process, singletons).
For each, propose a breaking technique — Extract Interface, Parameterize Constructor, or Extract and Override — with its trade-off.
Finish with a recommendation: the single cheapest seam to introduce first. Keep modifications and commands empty.
