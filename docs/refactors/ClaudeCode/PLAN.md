# Рефакторинг Claude Code ядра

План по итогам аудита кодовой базы (2026 - 07 - 26). Приоритет — режим Claude Code.

## Диагноз

**Архитектура:** границы слоёв Clean Architecture соблюдаются — application не тянет IntelliJ, порты / адаптеры честные . Проблема не в границах, а в размере классов внутри слоёв и в устаревшем `docs/ARCHITETURE.md` .

**Monster - файлы(LOC на момент аудита):**

| # | Файл | LOC | Вердикт |
|---|------|-----|-------- - |
| 1 | `plugin/ui/ChatMessageController.kt` | 1534 | распил: command - turn, question - turn, dispatchers |
| 2 | `plugin/ui/ChatPanel.kt` | 1485 | распил: toolbar / combos, prompt - CRUD, limits |
| 3 | `application/service/ClaudeCodeInteractionService.kt` | 984 | распил: ProtocolConverter, PendingModificationsStore |
| 4 | `plugin/ui/ConversationPanel.kt` | 956 | распил: bubble - фабрики |
| 5 | `adapter-llm/LangChainLLMService.kt` | 880 | вне приоритета — отложен |
| 6 | `application/service/ClipboardInteractionService.kt` | 827 | объединить общий код с №3 |
| 7 | `plugin/claudecode/ClaudeCodeProcessAdapter.kt` | 757 | мягкий распил : SpawnConfig, buildUserEvent |

**Тесты:** 34 тестовых файла, всё «сломано» по двум причинам:
1.`maxvibes-application` не компилируется: анонимный фейк `PromptPort` в `ClipboardMinimalModeTest.kt:122` не реализует `claudeCodeSystem` → блокированы все 17 тестовых файлов модуля .
2.`maxvibes-plugin`: 21 / 80 падают с одним и тем же `MockKException: Can't instantiate proxy for ChatPanelCallbacks` .

Системная причина : рукописные анонимные фейки портов в каждом тесте — любое изменение порта ломает их россыпью.Решение: переиспользуемые фейки (один на порт).

**Покрытие перекошено : * * у Claude Code режима один тест(`ClaudeCodeInteractionServiceThinkingTest`), у `ChatMessageController` — только Attachment / Session; command - turn / question - turn state machines не покрыты вовсе .

## Шаги

| Шаг | Что | Документ |
|-----|---- - |----------|
| 0 | Починить тесты (2 фикса +фундамент фейков) | [STEP_0_FixTests.md](STEP_0_FixTests.md) |
| 1 | Распил `ChatMessageController` |[STEP_1_ChatMessageController.md](STEP_1_ChatMessageController.md) |
| 2 | Распил `ClaudeCodeInteractionService` +общий `ProtocolConverter` |[STEP_2_ClaudeCodeInteractionService.md](
STEP_2_ClaudeCodeInteractionService.md
) |
| 2A | Глубокая нарезка `ClaudeCodeInteractionService` (971 → 865; ResponseProcessor, RequestFactory, WorkspaceHolder) — ВЫПОЛНЕН | [STEP_2A_ClaudeCodeInteractionService_DeepCut.md](STEP_2A_ClaudeCodeInteractionService_DeepCut.md) |
| 3 | UI: `ChatPanel`, `ConversationPanel` | [STEP_3_UI.md](STEP_3_UI.md) |
| 4 | Обновить `ARCHITETURE.md`, убрать println |[STEP_4_Docs.md](STEP_4_Docs.md) |

## Порядок и правила

-Шаг 0 обязателен ПЕРЕД любым распилом: пилить монстров без работающих тестов опасно .
-Каждый шаг завершается зелёным `gradlew test` и коммитом.
-Поведение не меняем — только структуру . Любое изменение поведения выносится в отдельную задачу .
-Клиппборд - режим — вторичен, но общий код(ProtocolConverter) обязан покрываться тестами с обеих сторон.

## Что НЕ делаем

-Не трогаем `LangChainLLMService`(880 LOC) — не Claude Code приоритет .
-Не переписываем `LiveTurnPanel`, `StreamJsonEventParser`, `ChatPanelState` — признаны образцовыми .
-Не вводим DI - фреймворк — `MaxVibesService` как service locator остаётся.
