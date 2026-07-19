---
name: write-kdoc
description: Write KDoc for an element in the project's existing doc style
applies-to: function, property, class
attach-element: true
editor-template: |
Write KDoc for {{elementPath}} matching the project's existing KDoc style.
---
Return exactly one REPLACE_ELEMENT: the element UNCHANGED plus KDoc on top. Document parameters, return value, and threading notes when relevant.
Match the tone and depth of the project's existing documentation. No behavioral changes.
