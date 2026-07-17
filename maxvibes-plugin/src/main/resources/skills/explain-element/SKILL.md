---
name: explain-element
description: Explain what an element does, its invariants and non-obvious parts
applies-to: any
attach-element: true
editor-template: |
Explain {{elementPath}}: purpose, how it works, non-obvious parts and invariants. No modifications.
---
Explain responsibilities, collaborators, and threading/EDT expectations where visible. Call out invariants and surprising behavior.
If callers matter for understanding, request CALLERS one level via requestedViews. Analysis only — keep modifications and commands empty.
