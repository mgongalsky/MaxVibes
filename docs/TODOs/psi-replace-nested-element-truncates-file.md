# Critical: REPLACE_ELEMENT for a nested declaration truncates the whole file

## Severity

Critical / data - loss.The operation reports success, but replaces the complete Kotlin file with the source text of one nested declaration . All imports, package declaration, enclosing classes / objects / interfaces, sibling declarations and remaining members are lost.This is distinct from `psi-replace-element-breaks-class-structure.md` : no multi - operation batch, stale offset or `CREATE_ELEMENT` interaction is required.A single `REPLACE_ELEMENT` is sufficient .

## Observed incidents

        All three incidents happened while implementing the IDE - native `checks` channel.

### 1.Nested property inside an object

Target :

    ```text
file: maxvibes - application / ... / InteractionRequestSchema.kt
elementPath: object[InteractionRequestSchema]/property[RESPONSE_FORMAT_HINT]
operation: REPLACE_ELEMENT
```

Expected: replace only `RESPONSE_FORMAT_HINT` inside `InteractionRequestSchema`.Actual file content after the operation :

```kotlin
const val RESPONSE_FORMAT_HINT = "..."
```

The package declaration, `object InteractionRequestSchema`, and every other protocol constant were deleted.Downstream symptom :

```text
Unresolved reference 'InteractionRequestSchema'
```

### 2.Nested data class inside a sealed interface inside an object

Target :

    ```text
file: maxvibes - application / ... / CodingAgentResponseProcessor.kt
elementPath: object[CodingAgentResponseProcessor]/interface[Intent]/class[HoldPending]
operation: REPLACE_ELEMENT
```

Expected: replace only `Intent.HoldPending`.Actual file content after the operation :

```kotlin
data class HoldPending(...) : Intent
```

The package, imports, outer object,
sealed interface, sibling intents and `process` function were deleted.Downstream symptom : every use of `CodingAgentResponseProcessor` became unresolved.

### 3.Private member function inside a class

Target :

    ```text
file: maxvibes - application / ... / ClaudeCodeApprovalService.kt
elementPath: class[CodingAgentApprovalService]/function[approvePendingModifications]
operation: REPLACE_ELEMENT
```

Expected: replace only the private member function .

Actual file content after the operation :

```kotlin
private suspend fun approvePendingModifications(...) {
    ...
}
```

The package, imports, enclosing class, constructor dependencies, sibling functions and result types were deleted.The remaining top - level function produced a cascade of unresolved references such as `pendingStore`, `sessionManager`, `notificationPort` and `ClaudeCodeStepResult`.

## Common pattern

        -Kotlin source file.
-A single `REPLACE_ELEMENT` operation .
-Target is below the file root and has at least one enclosing declaration .
-Replacement content is one valid declaration of the expected local kind.
-The resulting file contains exactly the replacement declaration .
-No syntax error is required in the replacement content.
-No concurrent or earlier modification in the same batch is required.

The failure affects multiple declaration kinds and nesting depths :

-property in object;
-class in interface in
object;
-suspend function in class.

Therefore this is not specific to `suspend`, properties, nested classes, primary constructors or stale offsets.

## Likely fault area

Inspect the complete path from `PsiNavigator` / `PsiFinderSupport` to `PsiModifier` / `PsiRefactoringExecutor` .

The observed result strongly suggests one of these failures:

1.The nested element is resolved correctly, but the replacement range is taken from the containing `KtFile` .
2.Resolution returns a wrapper / domain element whose PSI anchor is the file rather than the leaf declaration .
3.The replacement declaration is parsed as a file and `replace()` is invoked on the file - level node .
4.A fallback path silently switches from element replacement to whole -file replacement when the target or parent kind is unexpected .

The fact that the final file is exactly the submitted declaration is stronger evidence for a wrong replacement receiver / range than for malformed Kotlin parsing.

## Required reproduction tests

Add integration tests against real Kotlin PSI, not only mocked repositories .

For each case below, assert the complete resulting file, not merely operation success :

1.Replace a property in an object.
2.Replace a function in a class.
3.Replace a nested class inside an interface inside an object.
4.Replace a companion - object member.5.Replace declarations with and without modifiers (`private`, `suspend`, `const`).

Each test must assert that:

-package and imports remain unchanged;
-every enclosing declaration remains present;
-all siblings remain present and in the same order;
-exactly the target declaration changes;
-the resulting Kotlin file parses without errors;
-the reported affected path identifies the leaf target, not the file.

## Runtime safety guards

Even before the root cause is fixed, `REPLACE_ELEMENT` must fail safely instead of destroying a file.Recommended preconditions :

1.Resolve the target to a concrete PSI declaration and reject a `KtFile` receiver for element operations .
2.Verify that the resolved declaration kind matches the final elementPath segment and requested element kind.3.Verify that the target text range is strictly contained in the file range for a nested declaration.4.Parse replacement content as exactly one declaration compatible with the target.Recommended postconditions before committing the document :

1.Re - resolve every ancestor segment from the original elementPath .
2.Verify that package / imports and ancestor declarations still exist .
3.Verify that non - target top -level declaration identities / counts are unchanged.4.Verify that the resulting PSI file has no parse errors introduced by the operation .
5.If any postcondition fails, roll back the document / command and return a structured modification failure .

A successful return without these checks is unacceptable because this bug silently converts a narrow, user - approved edit into whole -file data loss.

## Temporary protocol guidance

Until fixed :

-Do not use `REPLACE_ELEMENT` for nested Kotlin declarations when the target path contains an enclosing `object`, `interface` or `class`.
-Prefer `REPLACE_FILE` after requesting a fresh FULL view .
-Treat a resulting file whose first token is `const`, `data`, `private` or `suspend` where a package/class was expected as immediate corruption; restore from the last full view before doing any other work.

## Relationship to existing TODOs

        -`psi-replace-element-breaks-class-structure.md` concerns structural corruption caused by multi - operation batches and stale PSI offsets .
-`psi-replace-primary-constructor-corrupts-class.md` concerns a class-header construct that is not independently replaceable .
-This issue concerns ordinary independently replaceable nested declarations and occurs with one isolated operation . It therefore needs a separate root - cause fix and regression suite.
