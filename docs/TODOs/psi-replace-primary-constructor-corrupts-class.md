# REPLACE_ELEMENT on a primary constructor corrupts the class

## Symptom

Applying `REPLACE_ELEMENT` to `file:.../X.kt/class[X]/constructor[primary]` reports success,
but the resulting file no longer parses as a class. The compiler emits a cascade of

```
Property must be initialized .
Syntax error : Property getter or setter expected .
Modifier 'private' is not applicable to 'local variable'.Modifier 'private' is not applicable to 'local function'.Unresolved reference 'init'.
```

plus `No parameter with name '...' found.` at every call site . The class header is broken,
so the whole class body is reparsed as top - level declarations .

## Reproduction

2026 - 07 - 30, `ChatHeaderPanel.kt`.Widening one parameter type from `(JComponent) -> Unit`
        to `(JButton) -> Unit` :

```json
{
    "type": "REPLACE_ELEMENT",
    "path": "file:.../ChatHeaderPanel.kt/class[ChatHeaderPanel]/constructor[primary]",
    "content": "(\n    private val onModeSelected: (InteractionMode) -> Unit,\n    ...\n)",
    "elementKind": "FUNCTION"
}
```

## Cause

A primary constructor is not a standalone declaration — it is part of the class header,
together with modifiers, type parameters and the supertype list . Replacing it as if it were
an element detaches it from the header instead of substituting it in place.

## Workaround

Use `REPLACE_FILE` for anything in a class header : primary constructor, type parameters,
supertype list . `REPLACE_ELEMENT` stays correct for functions, properties and nested
declarations.

## Cost when ignored

        Two wasted build runs, and the file had to be resent in full anyway — the same payload the
pointless optimisation was trying to avoid .
