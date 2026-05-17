# STEP 10 — Smoke Test

## Goal
Manually verify that MaxVibes works end -to - end in PyCharm with a Python project, covering the same scenarios as the existing Kotlin smoke test .

## Prerequisites
-PyCharm(Community or Professional) installed
        -A Python project open (any project with `.py` files)
-MaxVibes plugin built and installed from `build/distributions/`
-Claude or other LLM configured in MaxVibes settings

## Test Checklist

### 1.Plugin loads without errors
        -[] MaxVibes tool window appears in PyCharm
-[] No `ClassNotFoundException` or `PluginException` in IDE log (`Help → Show Log`)
-[] Settings panel opens (`MaxVibes` in Preferences)

### 2.File reading (requestedViews)
Open a `.py` file, e.g.:
```python
class Greeter :
    def hello(self, name: str) -> str:
return f"Hello, {name}"
```
Send message : "show me the content of this file"
-[] Plugin sends file to Claude
        -[] Claude receives file content(verify in `message` response)
-[] No PSI - related exceptions in log

### 3.Code view — SIGNATURES granularity
Send: "give me the signatures of all functions in Greeter"
-[] Response shows `def hello(self, name: str) -> str:` without body
        -[] Class signature `class Greeter:` is present

### 4.REPLACE_ELEMENT — function replacement
        Send: "rename hello to greet and add a default name='World'"
Expected modification :
```python
def greet (self, name: str = 'World') -> str:
return f"Hello, {name}"
```
-[] Plugin applies change via PSI (no manual file edit)
-[] Original function replaced, not duplicated
        -[] File still parses correctly(no red syntax errors in PyCharm)

### 5.CREATE_ELEMENT — add a new method
        Send: "add a method goodbye(self, name) that returns 'Bye, {name}'"
-[] New method appears inside `Greeter` class
-[] Indentation correct(4 spaces)
-[] No existing methods disturbed

### 6.Decorator preservation
        Add a decorator to `greet`:
```python
@staticmethod
def greet (name: str = 'World') -> str:
return f"Hello, {name}"
```
Then ask Claude to change the return value.
-[] `@staticmethod` decorator survives the replacement
        -[] Only the body /return changes

### 7.DELETE_ELEMENT
Send: "delete the goodbye method"
-[] Method removed cleanly
        -[] Class structure intact

### 8.IDE errors integration
Introduce a syntax error manually, then ask Claude to fix it :
```python
def broken (
        pass
```
-[] MaxVibes sends IDE error to Claude in next turn
        -[] Claude proposes a fix
-[] Fix applies correctly

### 9.Kotlin project unchanged
Switch to an IntelliJ IDEA window with a Kotlin project :
-[] `PsiCodeRepository` is used (not `PyCodeRepository`)
-[] All existing Kotlin tests still pass
-[] No regression

## Known edge cases to watch
| Scenario | Expected behaviour |
|----------|------------------ - |
| Empty Python file | `getChildren` returns empty list, no crash |
| File with only imports | Top -level functions / classes list is empty |
| Nested classes | Only top - level classes returned by navigator in v1 |
| `__init__.py` | Treated as a regular PyFile |
| Type annotation with complex generics | `fn.annotation.text` returns raw string — OK |

## Log locations
        -IntelliJ / PyCharm log : `Help → Show Log in Explorer`
        -MaxVibes plugin log: `.maxvibes/logs/maxvibes.log` in project root

## Plan complete
        All 10 steps documented.Implementation order :
```
STEP_1 → STEP_2 → STEP_3 → STEP_4 → STEP_5 → STEP_6 → STEP_7 → STEP_8 → STEP_9 → STEP_10
plugin.xml build mapper navigator factory modifier renderer repository DI test
```
