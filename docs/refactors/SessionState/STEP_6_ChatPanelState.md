# STEP 6: Plugin — ChatPanelState + buildState()

## Контекст

После STEP 5 статус живёт в домене и корректно обновляется . Теперь нужно донести его до UI через снапшот `ChatPanelState`, не давая UI напрямую опрашивать сервис .

Этот шаг устраняет нарушение инкапсуляции в рендер - пути: `updateModeUI()` сейчас сам вызывает `service.clipboardService.isWaitingForResponse()`.После шага UI будет читать только `state.clipboardStatus`.См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что делаем

### 1.Добавить `clipboardStatus` в `ChatPanelState`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt`

Добавить поле :
```kotlin
/** Текущий статус clipboard-диалога активной сессии. */
val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE
```

Добавить импорт :
```kotlin
import com . maxvibes . domain . model . interaction . ClipboardSessionStatus
```

### 2.Обновить `buildState()` в `ChatPanel`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`

В методе `buildState()` добавить поле:
```kotlin
clipboardStatus = chatTreeService.getActiveSession().clipboardStatus
```

Полный `buildState()` теперь включает `clipboardStatus` — снапшот полностью описывает состояние UI без обращения к сервису.

### 3.Удалить `isWaitingForResponse: Boolean` из `ChatPanel`

        Удалить поле :
```kotlin
private var isWaitingForResponse: Boolean = false
```

Найти все места использования и заменить :
-`isWaitingForResponse = !enabled` в `setInputEnabled()` → удалить эту строку(
    флаг больше не нужен,
    состояние читается из домена
)
-`state.isWaitingResponse` в `render()` → заменить на `state.clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE`

Обновить `ChatPanelState` :
-поле `isWaitingResponse: Boolean` — оставить пока для совместимости(будет удалено в STEP 8), но заполнять через:
```kotlin
isWaitingResponse = chatTreeService.getActiveSession().clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE
```

_Альтернатива:_ можно убрать `isWaitingResponse` из `ChatPanelState` уже здесь и везде заменить на `clipboardStatus == AWAITING_PASTE`.Выбор за исполнителем.

### 4.Добавить импорт в `ChatPanel`

```kotlin
import com . maxvibes . domain . model . interaction . ClipboardSessionStatus
```

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Ручная проверка :
1.Запустить плагин
        2.В clipboard -режиме отправить сообщение
3.Кнопка Send должна стать "Paste"(updateModeUI отработала корректно)
4.`state.clipboardStatus` должен отражаться в UI правильно

## Коммит

```
refactor(plugin): move clipboard status into ChatPanelState snapshot
```
