# ClipboardCodec Refactoring Plan

## Цель

Выделить логику сериализации / десериализации JSON -протокола Clipboard mode в отдельный, тестируемый, декларативно описанный слой.

## Проблемы текущей архитектуры

1.* * Хардкод полей * * — строки `"_protocol"`, `"message"`, `"requestedFiles"` и т.д.разбросаны по `ClipboardAdapter.serializeRequest()` и `parseUnifiedResponse()`.Добавить поле = трогать два места .

2.* * Не тестируется * * — `ClipboardAdapter` зависит от `Toolkit.getDefaultToolkit()`(системный буфер), что делает unit - тест невозможным без мока IDE.3.* * Умная логика парсинга не покрыта * * — `findEmbeddedJson`, regex для ` ```json ` блоков, fallback на plain text — именно там всплывают баги.4.* * Дублирование промптов * * — дефолтный системный промпт живёт в трёх местах : `PromptService.DEFAULT_CHAT_SYSTEM`, `chat-system.md` в resources, и `ClipboardInteractionService.buildChatInstruction()` строкой в коде.

## Архитектура после рефакторинга

```
[Domain]
ClipboardProtocol.kt          — модели(ClipboardRequest, ClipboardResponse, ...)

[Application]
ClipboardProtocolCodec.kt     — NEW: интерфейс кодека
        ClipboardRequestSchema.kt     — NEW: константы полей протокола

[Plugin / adapter]
JsonClipboardProtocolCodec.kt — NEW: реализация(чистая, без IDE -зависимостей)
ClipboardAdapter.kt           — THIN: только буфер обмена, делегирует кодеку
        ClipboardInteractionService.kt — CLEAN: промпты только из PromptService

        [Tests]
JsonClipboardProtocolCodecTest.kt — NEW: покрывает encode / decode все случаи
```

## Шаги

| Шаг | Файл | Что делаем | После шага |
|-----|------|------------|------------|
| 1 | STEP_1_Schema.md | `ClipboardRequestSchema` + `ClipboardProtocolCodec` интерфейс | Компилируется, запускается |
| 2 | STEP_2_Codec.md | `JsonClipboardProtocolCodec` реализация +тесты | Компилируется, тесты зелёные |
| 3 | STEP_3_Adapter.md | Переключить `ClipboardAdapter` на кодек | Smoke test: clipboard mode работает |
| 4 | STEP_4_Prompts.md | Убрать дублирование промптов | Smoke test : JSON отправляется с корректным system prompt |

## Принципы

-После каждого шага плагин должен * * компилироваться и запускаться * *
        -Каждый шаг — отдельный коммит
-Шаги 1 - 2 не ломают ничего существующего(только новые файлы)
-Шаги 3 - 4 — замена поведения с сохранением контракта

## Файлы затронутые рефакторингом

### Новые файлы
        -`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClipboardProtocolCodec.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClipboardRequestSchema.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/clipboard/JsonClipboardProtocolCodec.kt`
-`src/test/kotlin/.../JsonClipboardProtocolCodecTest.kt`

### Изменяемые файлы
        -`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/clipboard/ClipboardAdapter.kt`
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/PromptService.kt`
