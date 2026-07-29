---
name: monster-to-facade
description: Refactor a monster file or class into a thin facade and isolated tested components
applies-to: file, class
attach-element: true
editor-template: |
Refactor {{elementPath}} from a monster into a thin facade.
Start with characterization, extract one responsibility at a time, verify that the original file shrinks after every cut, then add direct tests for every extracted component.
---

# Goal

Turn a god file/class into:

- a thin stable facade;
- separate components with one clear responsibility each;
- narrow dependencies and ports;
- direct unit tests for every extracted component;
- preserved characterization and regression tests.

If a file does everything, treat it as an aggregator. Do not stop at a smaller god object.

# Workflow

## 1. Map the monster

Before modifying code:

1. Request the target in FULL.
2. Request USAGES/CALLERS and relevant collaborator SIGNATURES or OUTLINE.
3. Find existing tests.
4. Record current LOC.
5. List concrete responsibilities, mutable states, side effects and hard dependencies.
6. Create or update a refactoring document with steps and Definition of Done.

## 2. Characterize behavior

Add characterization tests for current behavior before extraction.

Cover:

- happy paths;
- state and mode transitions;
- failures and cancellation;
- persistence and ordered side effects;
- invalid or missing state;
- repeated and stale callbacks for asynchronous flows.

Do not change production behavior while characterizing it.

## 3. Define cut lines

Split responsibilities into explicit component types:

- pure policy or formatter;
- state holder;
- coordinator/state machine;
- mode dispatcher;
- execution boundary;
- resolver or handler;
- router;
- session/workspace service;
- narrow UI or infrastructure port;
- composition root.

For every planned component state:

- responsibility;
- inputs and outputs;
- owned state;
- side effects;
- dependencies;
- tests to add;
- code that will leave the monster.

## 4. Extract one vertical slice

For each cut:

1. Confirm characterization coverage.
2. Create the component.
3. Move one coherent responsibility.
4. Replace old code with delegation.
5. Narrow dependencies where possible.
6. Add direct unit tests.
7. Run targeted tests plus relevant characterization tests.
8. Re-measure the original file.
9. Update the refactoring document.

Exactly one cut should be IN_PROGRESS.

## Cut gate

A cut is complete only if:

- tests are green;
- the original file is smaller or contains fewer substantial methods;
- one responsibility clearly disappeared from it;
- logic was not duplicated;
- the new component can be tested without constructing the whole facade;
- no broad helper or new god object replaced the old one.

If the file is not shrinking, stop and redraw the seam.

## 5. Finish as a facade

Continue until the original type contains only:

- stable public entry points;
- trivial accessors;
- short delegates;
- optional construction/wiring.

Move large or cyclic wiring into a separate composition root.

The facade must not retain:

- business decisions;
- state-machine internals;
- mode-specific branches;
- persistence rules;
- transport calls;
- cancellation/error policy;
- complex formatting or result handling.

## 6. Harden extracted components

After structural extraction, audit each new component directly.

Every component should have its own test class covering:

- golden path;
- empty and invalid input;
- failures;
- exact argument forwarding;
- state transitions;
- persistence and ordering;
- idempotence;
- stale or duplicate callbacks;
- absence of forbidden calls.

Keep all test layers:

1. pure/state tests;
2. direct component unit tests;
3. facade boundary or composition smoke tests;
4. facade characterization/scenario tests;
5. full regression suite.

Do not replace characterization tests with unit tests.

## 7. Finalize

Update documentation with:

- before/after structure;
- final facade responsibility;
- extracted components;
- introduced ports;
- tests added;
- behavior risks found;
- targeted and full regression results.

Do not mark the work complete until the full owning-module test suite is green.

# PSI guidance

- Use CREATE_FILE for new components.
- Prefer REPLACE_ELEMENT and CREATE_ELEMENT for existing files.
- Use ADD_IMPORT and REMOVE_IMPORT for imports.
- Request ELEMENT or SIGNATURES when FULL is unnecessary.
- Use REPLACE_FILE only when PSI limitations require it.

# Reject these outcomes

- the file is shorter but still owns several unrelated workflows;
- logic moved into generic Helpers or Utils;
- new components depend back on the facade;
- composition root contains business logic;
- extracted components have no direct tests;
- characterization tests were removed;
- the original file stopped shrinking;
- several unrelated cuts were combined into one rewrite.

# Done when

- the former monster is a thin facade;
- meaningful state and behavior live in named components;
- every new component has direct tests;
- facade boundaries remain characterized;
- documentation is current;
- targeted and full regression suites are green.
