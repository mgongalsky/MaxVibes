# Step 0 — Rename Clipboard * protocol types to Interaction *

## Цель

Переименовать существующие `Clipboard*` классы, которые на самом деле * * protocol - agnostic * *(
    используются только за свою JSON - семантику,
    не за привязку к буферу обмена
), в `Interaction*` . Это подготовит почву для Step 5, где те же типы будут использоваться Claude Code режимом.

**Этот шаг — опциональный . * * Можно его пропустить и идти сразу к Step 1: имя `Clipboard*` корректно описывает текущий и новый потребитель, если воспринимать его как историческое.Но если хочется чистоты прямо сейчас — этот файл проведёт через переименования безопасно.

## Принципы

1.* * Только через `Refactor → Rename`(Shift + F6) * * — никаких find & replace в файлах . IDEA атомарно обновит все references, imports, KDoc, имена файлов .
2.* * По одному классу за раз.* * Между переименованиями : компиляция → ручной просмотр diff → коммит .
3.* * Все галочки в диалоге Rename — ON:**
-✅ Search in comments and strings
        -✅ Search for text occurrences
        -✅ Rename file accordingly (если top -level класс — обычно стоит по умолчанию)
4.* * Чистый git перед каждым переименованием.* * Если что -то ломается — `git reset --hard HEAD` и разбираемся, что пошло не так .
5.* * Запускать `./gradlew build` * * (без `-x test`) после каждого rename . Если красное — откат.

## Что переименовываем

### Этап A — domain types(5 классов в одном файле)

Файл `maxvibes-domain/.../interaction/ClipboardProtocol.kt` . Содержит несколько классов одной семьи . Переименовываем по очереди :

| # | Старое имя | Новое имя | Где(внутри `ClipboardProtocol.kt`) |
|---|------------|-----------|------------------------------------ - |
| 1 | `ClipboardPhase` | `InteractionPhase` | enum |
| 2 | `ClipboardHistoryEntry` | `InteractionHistoryEntry` | data class |
| 3 | `ClipboardRequest` | `InteractionRequest` | data class |
| 4 | `ClipboardResponse` | `InteractionResponse` | data class |
| 5 | `ClipboardModification` | `InteractionModification` | data class |

**После всех пяти * * — переименовать сам файл:
-В Project view → правый клик на `ClipboardProtocol.kt` → Refactor → Rename → `InteractionProtocol.kt`
        -IDEA спросит подтверждение и переименует файл .

**Коммит:**
```
refactor(domain): rename Clipboard * protocol types to Interaction *
```

### Этап B — application port + schema + codec

| # | Файл | Старое имя | Новое имя |
|---|------|------------|---------- - |
| 6 | `application/port/output/ClipboardRequestSchema.kt` | `ClipboardRequestSchema`(object) | `InteractionRequestSchema` |
| 7 | `application/port/output/ClipboardProtocolCodec.kt` | `ClipboardProtocolCodec`(interface) | `InteractionProtocolCodec` |

Переименовать класс /object → IDEA предложит rename файл вместе с ним → согласиться.

**Не переименовываем : * *
-`ClipboardPort` — реально про системный буфер обмена.

**Коммит:**
```
refactor(application): rename codec and schema to Interaction *
```

### Этап C — application service: builder + validator

| # | Файл | Старое имя | Новое имя |
|---|------|------------|---------- - |
| 8 | `application/service/ClipboardRequestBuilder.kt` | `ClipboardRequestBuilder`(object) | `InteractionRequestBuilder` |
| 9 | `application/service/ClipboardResponseValidator.kt` | `ClipboardResponseValidator`(class) | `InteractionResponseValidator` |

**Не переименовываем : * *
-`ClipboardInteractionService` — оркестратор именно clipboard - flow.
-`ClipboardSessionManager`, `ClipboardSessionState`, `ClipboardSessionStatus`, `ClipboardEvent`, `ClipboardStepResult` — оставляем как есть.В Step 6 расширим `ClipboardSessionStatus` новым литералом `AWAITING_APPROVE`, и enum останется в общем виде, что соответствует решению из `PLAN.md`.

**Коммит:**
```
refactor(application): rename request builder and response validator
```

### Этап D — adapter implementation(опционально)

| # | Файл | Старое имя | Новое имя |
|---|------|------------|---------- - |
| 10 | `plugin/clipboard/JsonClipboardProtocolCodec.kt` | `JsonClipboardProtocolCodec` | `JsonInteractionProtocolCodec` |

Это реализация интерфейса `InteractionProtocolCodec` (после Этапа B).Можно переименовать сразу, можно оставить — на твой выбор . Если переименовываешь — папку `plugin/clipboard/` пока * * не * * трогать(
    она содержит `ClipboardAdapter`,
    который остаётся
).

**Коммит:**
```
refactor(plugin): rename JSON codec implementation
```

## Что точно НЕ переименовывать

        Эти классы реально clipboard -специфичные:

-`ClipboardPort`(interface)
-`ClipboardAdapter`(class) — implements ClipboardPort
        -`ClipboardInteractionService` — оркестратор ручного flow «copy → paste»
-`ClipboardSessionManager` — расширяется в Step 6, оставляем имя
        -`ClipboardSessionState` — внутренняя структура сервиса
-`ClipboardSessionStatus` — enum, расширяется в Step 1
-`ClipboardEvent` — события session manager'а
-`ClipboardStepResult` — DTO результата от ClipboardInteractionService

## Пошаговая инструкция (одно переименование)

1.`git status` — должен быть clean
2.Открыть файл, поставить курсор на имя класса(например `ClipboardRequest`)
3.* * Shift + F6 * *(или правой кнопкой → Refactor → Rename)
4.Ввести новое имя(`InteractionRequest`)
5.Убедиться, что галочки * * « Search in comments and strings » * * и * * « Search for text occurrences » * * включены
        6.Нажать * * Refactor * * (или Preview, если хочешь сначала посмотреть список изменений)
7.Если IDEA спрашивает «Rename file accordingly?» — для top -level класса согласиться(
    для нашего Этапа A — отказаться,
    файл переименуем отдельно после всех
    5 классов
)
8.Дождаться завершения (на больших файлах — несколько секунд)
9.В терминале : `./gradlew build` (без `-x test`)
10.Если зелёное → `git diff --stat` посмотреть охват → коммит
        11.Если красное → `git reset --hard HEAD` → разобраться, что не так, и попробовать снова

## Если что -то пошло не так

**Симптом:** IDEA не нашла какие -то usages .
-Решение: `Build → Rebuild Project` сначала, потом Rename .

**Симптом:** В Gradle компилируется, но в IDE подсвечиваются красные импорты .
-Решение: `File → Invalidate Caches and Restart`.

**Симптом:** Сломалась сериализация (читается старый XML с именем класса).
-Это маловероятно для наших классов(они — domain types, не state holders), но если случилось:
-`ChatSession`, `ClipboardSessionState` мы НЕ переименовываем — этого риска нет .
-Если всё -таки задело — добавить `@SerialName("OldName")` через kotlinx.serialization(если применимо) или сделать миграцию state файла .

**Симптом:** Тесты упали после Rename .
-Тесты содержат строки с именами классов (например в test descriptions, mock setup) — IDEA должна была обновить с галочкой « Search in comments and strings », но мог что - то пропустить . Поправить вручную.

## Сводка коммитов

        После всех этапов в ветке `feature/claude-code-mode` будет:

```
refactor(domain): rename Clipboard * protocol types to Interaction *
        refactor(application): rename codec and schema to Interaction *
refactor(application): rename request builder and response validator
        refactor(plugin): rename JSON codec implementation (опционально)
```

После этого можно начинать Step 1.

## Acceptance criteria

        -[] `./gradlew build` зелёный после каждого этапа
        -[] `./gradlew test` зелёный после всех этапов
        -[] Никаких упоминаний `ClipboardRequest` / `ClipboardResponse` / `ClipboardModification` / `ClipboardPhase` / `ClipboardHistoryEntry` / `ClipboardRequestSchema` / `ClipboardProtocolCodec` / `ClipboardRequestBuilder` / `ClipboardResponseValidator` в коде (проверить через `Find in Files` по всему проекту)
-[] Все упоминания `Clipboard*` остались только у тех классов, которые перечислены в разделе « Что точно НЕ переименовывать»
-[] В run - IDE плагин запускается без ошибок, clipboard - режим работает как раньше (быстрый smoke : copy JSON / paste response)
