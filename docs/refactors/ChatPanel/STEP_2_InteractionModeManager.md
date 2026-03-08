# Step 2: Extract InteractionModeManager

## Контекст задачи

        Это второй шаг рефакторинга `ChatPanel.kt`.Шаг 1(ConversationRenderer) должен быть уже выполнен и закоммичен.

### Что сейчас не так

        В `ChatPanel.kt` живёт логика переключения режимов взаимодействия — **state machine с тремя состояниями * *: API, Clipboard, CheapAPI(
    дешёвый API режим
).Конкретно это методы:
-`switchMode(newMode: InteractionMode)` — переключает режим, обновляет UI
        -`syncModeFromSettings()` — читает режим из настроек при старте
        -`updateModeIndicator()` — обновляет визуальный индикатор
-Поле `currentMode: InteractionMode` — хранит текущий режим

        Эта логика * * не зависит от Swing -компонентов * * — она про состояние, а не про отрисовку . Значит, её можно и нужно вынести.

### Доменные типы

        `InteractionMode` — это enum из `maxvibes-domain` :
```kotlin
// maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/InteractionMode.kt
enum class InteractionMode { API, CLIPBOARD, CHEAP_API }
```

`MaxVibesSettings` — настройки плагина из `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettings.kt` .

---

## Задача

### 1.Создать файл `InteractionModeManager.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/InteractionModeManager.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . interaction . InteractionMode
        import com . maxvibes . plugin . settings . MaxVibesSettings

/**
 * Управляет состоянием режима взаимодействия (API / Clipboard / CheapAPI).
 * Инкапсулирует логику переключения и синхронизации с настройками.
 *
 * @param settings настройки плагина для чтения сохранённого режима
 * @param onModeChanged колбек, вызываемый при каждом изменении режима
 */
class InteractionModeManager(
    private val settings: MaxVibesSettings,
    private val onModeChanged: (InteractionMode) -> Unit
) {

    var currentMode: InteractionMode = InteractionMode.API
        private set

    /**
     * Переключает режим на указанный. Вызывает onModeChanged если режим изменился.
     */
    fun switchMode(newMode: InteractionMode) {
        if (currentMode == newMode) return
        currentMode = newMode
        onModeChanged(newMode)
    }

    /**
     * Читает сохранённый режим из настроек и применяет его.
     * Вызывается при инициализации панели.
     */
    fun syncFromSettings() {
        val savedMode = readModeFromSettings()
        currentMode = savedMode
        onModeChanged(savedMode)
    }

    /**
     * Возвращает true если текущий режим — Clipboard.
     */
    fun isClipboardMode(): Boolean = currentMode == InteractionMode.CLIPBOARD

    /**
     * Возвращает true если текущий режим — API (обычный или дешёвый).
     */
    fun isApiMode(): Boolean = currentMode == InteractionMode.API || currentMode == InteractionMode.CHEAP_API

    /**
     * Возвращает true если текущий режим — CheapAPI.
     */
    fun isCheapApiMode(): Boolean = currentMode == InteractionMode.CHEAP_API

    private fun readModeFromSettings(): InteractionMode {
        // Читаем сохранённый режим из настроек.
        // Адаптировать под реальное поле в MaxVibesSettings.
        return when {
            settings.useClipboardMode -> InteractionMode.CLIPBOARD
            settings.useCheapApi -> InteractionMode.CHEAP_API
            else -> InteractionMode.API
        }
    }
}
```

> **Примечание:** Посмотри реальный `MaxVibesSettings.kt` и адаптируй метод `readModeFromSettings()` под фактические поля настроек.Поля могут называться иначе .

### 2.Изменить `ChatPanel.kt`

**Добавить поле * * `modeManager` в класс `ChatPanel` :
```kotlin
private val modeManager = InteractionModeManager(
    settings = service.settings,  // адаптировать под реальный способ получения settings
    onModeChanged = { mode -> updateModeIndicator(mode) }
)
```

**Заменить * * использование поля `currentMode` на `modeManager.currentMode` .

**Заменить * * вызовы `switchMode(...)` на `modeManager.switchMode(...)`.

**Заменить * * вызовы `syncModeFromSettings()` на `modeManager.syncFromSettings()`.

**Метод `updateModeIndicator()` * * остаётся в `ChatPanel` — это UI -логика.Но теперь он вызывается через колбек из `InteractionModeManager` .

Если метод `updateModeIndicator()` сейчас не принимает аргументов и читает `currentMode` напрямую — поменяй сигнатуру :
```kotlin
private fun updateModeIndicator(mode: InteractionMode) {
    // обновляем UI индикатор по переданному mode
}
```

**Удалить * * поле `currentMode` из `ChatPanel`(оно переехало в `InteractionModeManager`).
**Удалить * * методы `switchMode()`, `syncModeFromSettings()` из `ChatPanel`(они переехали в `InteractionModeManager`).

### 3.Проверить компиляцию

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

---

## Ручное тестирование

        -[] Запустить плагин — режим по умолчанию соответствует настройкам
-[] Переключить режим кнопкой / кнопками в UI — индикатор режима меняется
        -[] Открыть Settings, изменить режим, закрыть — плагин применяет новый режим
        -[] В режиме Clipboard отправить сообщение — уходит в буфер обмена
-[] В режиме API отправить сообщение — уходит через API
        -[] Перезапустить IDE — режим восстанавливается из настроек

        ---

## Автоматические тесты

**Создать файл : * * `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/InteractionModeManagerTest.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . interaction . InteractionMode
        import com . maxvibes . plugin . settings . MaxVibesSettings
        import io . mockk . every
        import io . mockk . mockk
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class InteractionModeManagerTest {

    private val settings = mockk<MaxVibesSettings>(relaxed = true)
    private val modeChanges = mutableListOf<InteractionMode>()
    private fun createManager() = InteractionModeManager(
        settings = settings,
        onModeChanged = { modeChanges.add(it) }
    )

    @Test
    fun `initial mode is API`() {
        val manager = createManager()
        assertEquals(InteractionMode.API, manager.currentMode)
    }

    @Test
    fun `switchMode changes currentMode`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertEquals(InteractionMode.CLIPBOARD, manager.currentMode)
    }

    @Test
    fun `switchMode calls onModeChanged callback`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertEquals(1, modeChanges.size)
        assertEquals(InteractionMode.CLIPBOARD, modeChanges[0])
    }

    @Test
    fun `switchMode does not call callback if mode unchanged`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.API) // same as initial
        assertEquals(0, modeChanges.size)
    }

    @Test
    fun `isClipboardMode returns true only for CLIPBOARD`() {
        val manager = createManager()
        assertFalse(manager.isClipboardMode())
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertTrue(manager.isClipboardMode())
    }

    @Test
    fun `isApiMode returns true for API and CHEAP_API`() {
        val manager = createManager()
        assertTrue(manager.isApiMode()) // API by default
        manager.switchMode(InteractionMode.CHEAP_API)
        assertTrue(manager.isApiMode())
        manager.switchMode(InteractionMode.CLIPBOARD)
        assertFalse(manager.isApiMode())
    }

    @Test
    fun `syncFromSettings applies CLIPBOARD mode from settings`() {
        every { settings.useClipboardMode } returns true
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.CLIPBOARD, manager.currentMode)
    }

    @Test
    fun `syncFromSettings applies API mode when no special settings`() {
        every { settings.useClipboardMode } returns false
        every { settings.useCheapApi } returns false
        val manager = createManager()
        manager.syncFromSettings()
        assertEquals(InteractionMode.API, manager.currentMode)
    }

    @Test
    fun `switchMode from API to CLIPBOARD then back to API`() {
        val manager = createManager()
        manager.switchMode(InteractionMode.CLIPBOARD)
        manager.switchMode(InteractionMode.API)
        assertEquals(InteractionMode.API, manager.currentMode)
        assertEquals(2, modeChanges.size)
    }
}
```

> **Примечание:** Имена полей `settings.useClipboardMode` и `settings.useCheapApi` — адаптировать под реальные поля `MaxVibesSettings`.Запустить тесты :
```bash
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.InteractionModeManagerTest"
```

---

## Коммит

```
refactor: extract InteractionModeManager from ChatPanel
```

## Следующий шаг

        `STEP_3_ChatPanelState.md`
