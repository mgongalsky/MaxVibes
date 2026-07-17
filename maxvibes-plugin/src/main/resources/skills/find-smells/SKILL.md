---
name: find-smells
description: Review an element for smells, risks and hidden side effects
applies-to: any
attach-element: true
editor-template: |
Review {{elementPath}} for smells and risks. Analysis only.
---
Look for SRP violations, hidden side effects, error-handling gaps, and threading hazards. When the element is a class member, request OUTLINE of the containing class for structure.
Prioritize findings (high/medium/low) and for each suggest the smallest safe refactoring. Keep modifications and commands empty.
