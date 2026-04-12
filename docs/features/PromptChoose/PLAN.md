# Feature: PromptChoose — Контекстуальные промпты задачи

## Цель

Добавить систему переключаемых специфических промптов(task - scoped prompts), которые:
-Лежат в файлах проекта в `.maxvibes/prompts/specific/` (в git, не в настройках IDE)
-Выбираются в UI через дропдаун в нижней панели ChatPanel
-Инжектируются в JSON Clipboard -запроса как поле `specificPrompt`
        -Могут меняться в рамках одного диалога
        -Сохраняются per -session в XML(восстанавливаются при рестарте IDE)
-Поддерживают fallback « Just Code» (нет специфического промпта = поле отсутствует в JSON)

## Контекст

Системный промпт (chat - system.md / planning - system.md) остаётся как есть — это « конституция ».
        `specificPrompt` — это уточнение под конкретную задачу(
    например: рефакторинг по Физерсу,
    характеризационные тесты, анализ кодобазы без изменений
).

**Поддерживаемые режимы : * * только Clipboard.API - режим в данной фиче не трогаем .

## Структура файлов промптов

```
.maxvibes / prompts / specific /
        Just Code . md ← НЕ создаётся, это sentinel -значение
Analyze Only . md
        Refactor(Feathers) - Extract & Override.md
Characterization Tests . md
        Unit Tests . md
```

-Формат: `.md` или `.txt`
-Имя файла без расширения = имя промпта
-Содержимое — чистый текст, без парсинга
        -« Just Code» — специальный sentinel (null), всегда первый в дропдауне, папка может не существовать

## Архитектура по слоям

```
maxvibes - domain
└── model / interaction / SpecificPrompt.kt          ← data class(name, content)
└── model / interaction / ClipboardRequest.kt        ← добавить specificPrompt : String ?

maxvibes - application
└── port / output / SpecificPromptRepository.kt      ← интерфейс loadAll () / loadByName()
└── service / SpecificPromptService.kt             ← resolvePrompt(name?): String?

maxvibes - plugin
└── service / FileSpecificPromptRepository.kt      ← читает.maxvibes / prompts / specific /
└── clipboard / JsonClipboardProtocolCodec.kt      ← сериализует specificPrompt в JSON
└── chat / ChatHistoryService.kt                   ← XmlChatSession.selectedSpecificPromptName
└── ui / ChatPanelState.kt                         ← availablePrompts, selectedSpecificPromptName
└── ui / ChatMessageController.kt                  ← selectSpecificPrompt(), dispatch
└── ui / ChatPanel.kt                              ← UI - панель с дропдауном
└── service / MaxVibesService.kt                   ← DI wiring
```

## Шаги реализации

| Шаг | Файл | Описание |
|-----|------|----------|
| 1 | STEP_1_Domain.md | SpecificPrompt + ClipboardRequest.specificPrompt |
| 2 | STEP_2_Port.md | SpecificPromptRepository порт |
| 3 | STEP_3_Service.md | SpecificPromptService + тесты |
| 4 | STEP_4_FileAdapter.md | FileSpecificPromptRepository |
| 5 | STEP_5_Persistence.md | ChatSession + XmlChatSession + ChatHistoryService |
| 6 | STEP_6_RequestBuilder.md | ClipboardRequestBuilder.build() + specificPromptContent |
| 7 | STEP_7_ClipboardService.md | ClipboardInteractionService.handleUserInput() |
| 8 | STEP_8_PanelState.md | ChatPanelState + ChatMessageController |
| 9 | STEP_9_DI.md | MaxVibesService wiring |
| 10 | STEP_10_UI.md | ChatPanel — UI панель выбора промпта |

## Принципы

-Каждый шаг оставляет плагин компилируемым
-Тесты в шаге 3 запускаются через Gradle (не IntelliJ runner)
-Конвенции тестирования : `runBlocking`, `MockK`, data classes напрямую
-Backward compatibility XML: новые `@Attribute` поля с дефолтами → старые XML читаются без ошибок
-`FileSpecificPromptRepository` использует `java.io.File`(не IntelliJ VFS) — тестируем без IDE
