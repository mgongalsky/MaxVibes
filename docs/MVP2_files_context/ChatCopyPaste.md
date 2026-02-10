# MaxVibes: Multi-Mode Interaction System

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          UI (Tool Window)                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                          │
│  │ 🔌 API    │  │ 📋 Clip   │  │ 💰 Cheap  │  ← Mode Selector      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                          │
│       │              │              │                                │
├───────┼──────────────┼──────────────┼────────────────────────────────┤
│       ▼              ▼              ▼                                │
│  ContextAware   Clipboard      ContextAware                         │
│  ModifyService  Interaction    ModifyService    ← Application Layer │
│  (existing)     Service (NEW)  (cheap LLM)                          │
│       │              │              │                                │
│       ▼              │              ▼                                │
│  LangChainLLM   ClipboardPort  LangChainLLM                        │
│  Service         (NEW)         Service (cheap)  ← Adapter Layer     │
│  (existing)          │              │                                │
│       │              ▼              │                                │
│       │         ClipboardAdapter    │                                │
│       │         (NEW)              │                                │
│       ▼                            ▼                                │
│  Anthropic/OpenAI API     DeepSeek/Haiku/Ollama API                 │
└─────────────────────────────────────────────────────────────────────┘
```

## File Placement

### NEW FILES (create these):

| File | Target Path |
|------|-------------|
| `domain/InteractionMode.kt` | `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/InteractionMode.kt` |
| `domain/ClipboardProtocol.kt` | `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/ClipboardProtocol.kt` |
| `application/ClipboardPort.kt` | `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClipboardPort.kt` |
| `application/ClipboardInteractionService.kt` | `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt` |
| `plugin/ClipboardAdapter.kt` | `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/clipboard/ClipboardAdapter.kt` |

### REPLACE FILES (full replacements):

| File | Target Path |
|------|-------------|
| `plugin/MaxVibesSettings.kt` | `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettings.kt` |
| `plugin/MaxVibesToolWindowFactory.kt` | `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/MaxVibesToolWindowFactory.kt` |

### PATCH FILES (add code to existing):

| File | What to do |
|------|------------|
| `adapter-llm/LLMProviderConfig.kt` | Add `DEEPSEEK` to `LLMProviderType` enum |
| `adapter-llm/LangChainLLMService_PATCH.kt` | Add `DEEPSEEK` case to `createChatModel()` |
| `plugin/MaxVibesService_ADDITIONS.kt` | Add fields and methods to existing `MaxVibesService` |

## Implementation Order

1. **Domain** — create `interaction/` package with `InteractionMode.kt` and `ClipboardProtocol.kt`
2. **Application** — create `ClipboardPort.kt` and `ClipboardInteractionService.kt`
3. **Adapter** — update `LLMProviderType` with DEEPSEEK, patch `LangChainLLMService`
4. **Plugin** — create `ClipboardAdapter.kt`, replace `MaxVibesSettings.kt` and `MaxVibesToolWindowFactory.kt`
5. **Plugin** — update `MaxVibesService.kt` with additions from guide
6. **Test** — run plugin, switch modes in UI

## What's NOT Changed

- `ContextAwareModifyService` — untouched, works exactly as before
- `LangChainLLMService` — only DEEPSEEK case added to createChatModel()
- `ChatHistoryService` — untouched
- All existing tests — should pass without changes
- Plugin descriptor (plugin.xml) — no changes needed (services are already registered)

## Clipboard Mode Workflow

```
User types: "Add logging to UserService"
  ↓
MaxVibes generates Planning JSON → copies to clipboard
  ↓
User pastes JSON into Claude chat → Claude responds with file list
  ↓  
User copies Claude's response → pastes into MaxVibes input
  ↓
MaxVibes parses → gathers files → generates Chat JSON → copies to clipboard
  ↓
User pastes into Claude → Claude responds with code + modifications
  ↓
User copies response → pastes into MaxVibes
  ↓
MaxVibes parses → applies modifications → done!
```

## Commit Message

```
feat: add multi-mode interaction system (API / Clipboard / Cheap API)

Add smart mode switching to save costs while maintaining functionality:

Domain layer:
- InteractionMode enum (API, CLIPBOARD, CHEAP_API)
- ClipboardProtocol models for JSON copy-paste workflow

Application layer:
- ClipboardPort interface for clipboard abstraction
- ClipboardInteractionService for multi-step clipboard workflow

Adapter layer:
- DEEPSEEK provider type (OpenAI-compatible API)
- ClipboardAdapter implementing JSON serialization/parsing

Plugin layer:
- Mode selector ComboBox in Tool Window
- Clipboard mode with phase indicators and paste detection
- Cheap API mode routing to budget-friendly LLM
- MaxVibesSettings extended with mode + cheap LLM config

Existing API mode is completely unchanged. No breaking changes.
```