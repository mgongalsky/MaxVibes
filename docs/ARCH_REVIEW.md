
# Architecture Review

## Общая оценка

Проект **хорошо структурирован для IntelliJ-плагина**.
Гексагональная архитектура соблюдается примерно на **80–85%**, что является высоким показателем для проекта такого типа.

Основные слои в целом соблюдают **Dependency Rule**:

```
domain
  ↑
application
  ↑
adapters
  ↑
framework (IntelliJ)
```

Ниже — разбор того, что сделано хорошо, и где есть архитектурные проблемы.

---

# ✅ Что сделано правильно

## 1. Domain layer — чистый

Доменные модели реализованы корректно:

* `ChatSession`
* `ChatMessage`
* `Modification`

Характеристики:

* `data class`
* иммутабельные операции (`withMessage()`, `cleared()`, `addPlanningTokens()`)
* **нет зависимостей от IntelliJ**

Это **настоящие доменные объекты**, как и должно быть в hexagonal architecture.

---

## 2. ChatTreeService — хорошая бизнес-логика

В `ChatTreeService` вынесена логика работы с деревом сессий:

* `create`
* `branch`
* `delete`
* `trimOldSessions`
* `recalculateChildDepths`

Плюсы:

* находится в **application layer**
* **тестируется без IntelliJ**
* не зависит от UI

Это хороший результат последнего рефакторинга.

---

## 3. ChatHistoryService — честный persistence adapter

`ChatHistoryService` (~130 строк) реализует:

```
ChatSessionRepository
```

Он делает только:

* XML-сериализацию
* работу с `PersistentStateComponent`

Важно:

* **нет бизнес-логики**
* чистый **persistence adapter**

Это **правильная реализация hexagonal architecture**.

---

## 4. Output ports — правильные интерфейсы

В `application/port/output/` определены:

* `CodeRepository`
* `LLMService`
* `ChatSessionRepository`
* `ProjectContextPort`

Плюсы:

* **нет IntelliJ зависимостей**
* адаптеры реализуют интерфейсы
* соблюдается **Dependency Rule**

---

## 5. ContextAwareModifyService — оркестратор

Это **application-service**, который управляет флоу:

```
plan → gather → chat → apply
```

Особенности:

* принимает зависимости через **конструктор**
* использует **ports**
* обрабатывает ошибки через `Result`

Это хороший **оркестратор use-case уровня**.

---

# ⚠️ Архитектурные проблемы

## 1. LLMService.kt — DTO находятся в неправильном слое

**Серьёзная проблема.**

Файл:

```
application/port/output/LLMService.kt
```

Содержит не только интерфейс, но и множество DTO:

* `ChatMessageDTO`
* `ChatRole`
* `TokenUsage`
* `ChatContext`
* `ChatResponse`
* `LLMContext`
* `ProjectInfo`
* `AnalysisResponse`
* `LLMError`

### Основная проблема

`ChatMessageDTO` и `ChatRole` **дублируют доменные типы**:

```
ChatMessage
MessageRole
```

В результате возникает **ручная конвертация**.

Пример:

```
fun ChatMessage.toChatMessageDTO()
```

И она дублируется в:

* `ChatPanel`
* `ChatMessageController`
* `ChatHistoryService`

Это признак того, что **тип находится не в том месте архитектуры**.

### Вероятная причина

`ChatMessageDTO` появился **раньше**, чем `ChatMessage` был перенесён в domain layer.

Сейчас они существуют **параллельно**.

---

## 2. MaxVibesService — Service Locator вместо DI

`MaxVibesService` реализует **Service Locator**.

Он:

* создаёт все зависимости
* использует lazy-инициализацию
* имеет `getInstance()`.

Для **IntelliJ-плагинов это стандартная практика**, потому что IntelliJ не предоставляет DI-контейнер.

Поэтому это скорее **вынужденный компромисс**, а не архитектурная ошибка.

Но есть нюансы:

### Проблемы

**1. Logger**

`MaxVibesLogger` передаётся напрямую как singleton.

**2. Lazy-инициализация через void-метод**

```
ensureCheapLLMService()
```

Это анти-паттерн:

```
lazy initialization через side-effect метод
```

Лучше использовать:

* lazy property
* factory
* explicit initialization.

---

## 3. PsiCodeRepository.findOrCreateDirectory() — fallback-костыль

Метод содержит fallback-цепочку:

```kotlin
val possiblePaths = listOf(
    "$projectBasePath/$dirPath",
    "$projectBasePath/src/main/kotlin",
    "$projectBasePath/src",
    projectBasePath
)
```

Проблема:

если путь не найден, файл может быть создан **не там**, где ожидается.

Это **тихий fallback без явного логирования**.

Даже если проблема уже исправлена — сам паттерн **опасный**.

---

## 4. ChatPanel — God Object

Размер:

```
~610 строк
```

Он содержит:

### UI-логику

* `buildUI`
* `setupListeners`

### Бизнес-логику

* `sendApiMessage`
* `sendClipboardMessage`
* `sendCheapApiMessage`

### Управление состоянием

* `currentMode`
* `attachedTrace`
* `attachedErrors`
* `elementNavRegistry`

### Операции с сессиями

* `deleteCurrentChat`
* branching

### Форматирование

```
formatMarkdown()
```

Часть уже вынесена в:

```
ChatMessageController
```

Но **ChatPanel всё ещё слишком большой**.

---

## 5. LangChainLLMService — слишком толстый адаптер

Размер:

```
~700 строк
```

Он делает слишком много:

### Создание моделей

* OpenAI
* Claude
* Gemini
* другие

### ClassLoader hack

```
withPluginClassLoader
```

(решение конфликта IntelliJ / Jackson)

### Два режима работы

* AiServices
* raw fallback

### Prompt building

несколько версий

### JSON parsing

через:

* regex
* kotlinx.serialization

### Legacy методы

```
generateModifications
```

Всё это делает адаптер **чрезмерно сложным**.

---

## 6. Дублирование конвертации ChatMessage ↔ DTO

Одинаковый код существует в нескольких местах:

```kotlin
fun ChatMessage.toChatMessageDTO() = ChatMessageDTO(
    role = when (role) {
        MessageRole.USER -> ChatRole.USER
        ...
    },
    content = content
)
```

Повторяется в:

* `ChatPanel`
* `ChatMessageController`
* `ChatHistoryService`

Это нужно вынести **в одно место**.

---

## 7. src/ в корне проекта — мусор

В корне проекта существует папка:

```
src/main/kotlin/
```

Она содержит:

* `STEP_1_MessageRole.md`
* `STEP_9_Cleanup.md`
* `IntellijIdeErrorsAdapter.kt`

Это **артефакт рефакторинга**, который нужно удалить.

---

## 8. formatMarkdown — заглушка

Текущая реализация:

```
override fun formatMarkdown(text: String): String = text
```

Это **no-op**.

Если markdown-рендеринг не планируется — интерфейс лучше упростить.

---

# 📊 Итоговая карта качества

| Компонент                 | Оценка              | Комментарий                    |
| ------------------------- | ------------------- | ------------------------------ |
| domain                    | ✅ Отлично           | Чистые модели                  |
| ChatHistoryService        | ✅ Отлично           | Чистый persistence adapter     |
| ChatTreeService           | ✅ Хорошо            | Хорошая бизнес-логика          |
| ContextAwareModifyService | ✅ Хорошо            | Правильный orchestrator        |
| output ports              | ⚠️ Хорошо           | LLMService перегружен DTO      |
| PsiCodeRepository         | ⚠️ Хорошо           | findOrCreateDirectory fallback |
| MaxVibesService           | ⚠️ Норм             | Service Locator                |
| ChatPanel                 | ⚠️ Требует внимания | God Object                     |
| LangChainLLMService       | ⚠️ Требует внимания | слишком большой                |
| root src                  | ❌ Плохо             | мусор                          |

---

# 🔧 Приоритетные улучшения

## 1. Убрать дублирование конвертации

Создать **единый mapper**:

```
ChatMessageMapper
```

или extension в одном месте.

---

## 2. Решить судьбу ChatMessageDTO

Есть два варианта:

### Вариант A (лучше)

Удалить `ChatMessageDTO` и использовать:

```
ChatMessage
```

### Вариант B

Оставить DTO, но **задокументировать причину**.

---

## 3. Удалить src/ в корне

Простой cleanup.

---

## 4. Разделить LangChainLLMService

Вынести:

```
LLMModelFactory
```

и логику создания моделей.

---

## 5. Убрать ensureCheapLLMService()

Заменить на:

```
lazy property
или factory
```

---

# Итог

Архитектура **в целом здоровая**.

Основные проблемы:

* дублирование DTO
* слишком толстые адаптеры
* крупные UI-классы

Это **типичные проблемы проектов на стадии MVP**, и они легко исправляются по мере развития.

---

Если хочешь, я могу ещё сделать **вторую версию этого файла**, но уже как **реальный architectural RFC-документ уровня senior engineering review**, который обычно кладут в `docs/architecture/ARCH_REVIEW.md` (там будет ещё: diagrams, dependency graph, tech debt severity). Это будет прям **уровень Google / JetBrains архитектурных документов**.
