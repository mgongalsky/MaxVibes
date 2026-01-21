# MaxVibes: MVP 1 Roadmap

## 🎯 Определение MVP 1

**MVP 1** — минимальный работающий продукт, который позволяет:

1. ✅ Модифицировать Kotlin код через AI (добавление/изменение функций, классов, свойств)
2. ✅ Анализировать код и получать ответы на вопросы
3. 🚧 Использовать реальный LLM (OpenAI/Anthropic) вместо мока
4. 🚧 Настраивать API ключи через UI

---

## 📊 Текущий прогресс

```
Overall MVP 1 Progress: ████████░░ 80%

✅ Domain Layer:       ██████████ 100%
✅ Application Layer:  ██████████ 100%
✅ PSI Adapter:        ██████████ 100%
✅ Plugin UI:          ████████░░ 80%
🚧 LLM Integration:    █░░░░░░░░░ 10%
✅ Tests:              ███████░░░ 70%
```

---

## ✅ Выполненные задачи

### Sprint 1: Foundation (DONE)
- [x] Настроить multi-module Gradle проект
- [x] Создать Domain модели (ElementPath, CodeElement, Modification)
- [x] Создать Shared utilities (Result type)

### Sprint 2: Application Layer (DONE)
- [x] Определить порты (CodeRepository, LLMService, NotificationPort)
- [x] Реализовать Use Cases (ModifyCodeService, AnalyzeCodeService)
- [x] Создать DTOs для request/response

### Sprint 3: PSI Adapter (DONE)
- [x] Реализовать PsiToDomainMapper
- [x] Реализовать PsiNavigator
- [x] Реализовать PsiModifier
- [x] Реализовать KotlinElementFactory
- [x] Реализовать PsiCodeRepository

### Sprint 4: Plugin MVP (DONE)
- [x] Создать ModifyCodeAction
- [x] Создать AnalyzeCodeAction
- [x] Создать Tool Window
- [x] Создать MockLLMService
- [x] Настроить plugin.xml

### Sprint 5: Testing (DONE)
- [x] Unit тесты для Domain
- [x] Unit тесты для Shared
- [x] Unit тесты для Application (с MockK)
- [x] Integration тесты для PSI Mapper

---

## 🚧 Оставшиеся задачи

### Sprint 6: LLM Integration (NEXT)

**Приоритет: HIGH**  
**Время: ~12-14 часов**

#### 6.1 LLM Provider Abstraction
- [ ] Создать `LLMProvider` interface
- [ ] Реализовать `OpenAIProvider`
- [ ] Реализовать `AnthropicProvider`
- [ ] Создать `LLMProviderFactory`

#### 6.2 CoderAgent
- [ ] Определить Tools (create_element, replace_element, delete_element)
- [ ] Создать `CoderAgent` с system prompt
- [ ] Реализовать парсинг tool calls → Modification

#### 6.3 KoogLLMService
- [ ] Реализовать `generateModifications()`
- [ ] Реализовать `analyzeCode()`
- [ ] Добавить error handling и retry logic

#### 6.4 Integration
- [ ] Обновить `MaxVibesService` для использования реального LLM
- [ ] Добавить fallback на MockLLMService

### Sprint 7: Settings & Configuration

**Приоритет: HIGH**  
**Время: ~3-4 часа**

- [ ] Создать `MaxVibesSettings` (PersistentStateComponent)
- [ ] Создать Settings UI panel
- [ ] Добавить выбор провайдера (OpenAI/Anthropic)
- [ ] Добавить поля для API ключей
- [ ] Добавить выбор модели

### Sprint 8: Polish & Testing

**Приоритет: MEDIUM**  
**Время: ~4-5 часов**

- [ ] Unit тесты для LLM Adapter
- [ ] Integration тесты end-to-end
- [ ] Error messages improvements
- [ ] Progress indicators improvements
- [ ] README обновление

---

## 📅 Timeline

```
Week 1 (Current):
├── Day 1-2: Sprint 6.1-6.2 (LLM Provider + CoderAgent)
├── Day 3: Sprint 6.3-6.4 (KoogLLMService + Integration)
└── Day 4: Sprint 7 (Settings)

Week 2:
├── Day 1-2: Sprint 8 (Testing & Polish)
├── Day 3: Final testing & bug fixes
└── Day 4: MVP 1 Release 🎉
```

---

## 🎯 Критерии готовности MVP 1

### Функциональные требования

| # | Критерий | Статус |
|---|----------|--------|
| 1 | Пользователь может добавить функцию в класс через AI | ✅ (mock) / 🚧 (real) |
| 2 | Пользователь может изменить существующую функцию | ✅ (mock) / 🚧 (real) |
| 3 | Пользователь может удалить элемент кода | ✅ (mock) / 🚧 (real) |
| 4 | Пользователь может задать вопрос о коде | ✅ (mock) / 🚧 (real) |
| 5 | Пользователь может настроить API ключ | 🚧 |
| 6 | Работает с OpenAI GPT-4 | 🚧 |
| 7 | Работает с Anthropic Claude | 🚧 |

### Нефункциональные требования

| # | Критерий | Статус |
|---|----------|--------|
| 1 | Плагин устанавливается без ошибок | ✅ |
| 2 | Работает на IntelliJ IDEA 2023.1+ | ✅ |
| 3 | Тесты проходят | ✅ |
| 4 | Документация актуальна | ✅ |

---

## 🔮 После MVP 1 (Future)

### MVP 2: Multi-file & Refactoring
- [ ] Работа с несколькими файлами одновременно
- [ ] Рефакторинг (rename, extract method, etc.)
- [ ] Поддержка выделенного кода (selection)

### MVP 3: Agents & Workflows
- [ ] ReviewerAgent — code review
- [ ] TestWriterAgent — генерация тестов
- [ ] Graph-based workflows
- [ ] Plan → Execute → Review pipeline

### MVP 4: Advanced UI
- [ ] Graph editor для workflows (JCEF + React)
- [ ] История операций
- [ ] Diff preview перед применением
- [ ] Undo/Redo

### MVP 5: Multi-language
- [ ] Java support
- [ ] TypeScript support
- [ ] Python support (PyCharm)

---

## 📝 Заметки для продолжения

### Перед началом Sprint 6:

1. **Получить API ключи:**
    - OpenAI: https://platform.openai.com/api-keys
    - Anthropic: https://console.anthropic.com/

2. **Изучить Koog:**
    - Документация: https://koog.ai/
    - Examples: https://github.com/JetBrains/koog/tree/main/examples

3. **Проверить зависимости:**
   ```kotlin
   // maxvibes-adapter-llm/build.gradle.kts
   implementation("ai.koog:koog-agents:0.6.0")
   ```

### Команды для разработки:

```bash
# Сборка
./gradlew build

# Тесты
./gradlew test

# Запуск плагина
./gradlew :maxvibes-plugin:runIde

# Проверка зависимостей
./gradlew dependencies
```

### Файлы для редактирования в Sprint 6:

```
maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/
├── KoogLLMService.kt           # NEW
├── agent/
│   ├── CoderAgent.kt           # NEW
│   └── tools/
│       └── CodeTools.kt        # NEW
├── prompt/
│   └── PromptBuilder.kt        # NEW
└── provider/
    ├── LLMProvider.kt          # NEW
    ├── LLMProviderFactory.kt   # NEW
    ├── OpenAIProvider.kt       # NEW
    └── AnthropicProvider.kt    # NEW
```

---

## ✅ Acceptance Criteria для MVP 1

### Scenario 1: Add Function
```
GIVEN: Открыт файл User.kt с классом User
WHEN: Пользователь вызывает "MaxVibes: Modify Code" и вводит "add toString method"
THEN: В класс User добавляется override fun toString(): String { ... }
```

### Scenario 2: Analyze Code
```
GIVEN: Открыт файл Service.kt
WHEN: Пользователь вызывает "MaxVibes: Analyze Code" и вводит "What does this service do?"
THEN: Появляется диалог с осмысленным ответом от LLM
```

### Scenario 3: Configure API Key
```
GIVEN: Пользователь открывает Settings → Tools → MaxVibes
WHEN: Вводит OpenAI API Key и нажимает Apply
THEN: Ключ сохраняется и используется для следующих запросов
```

---

## 🔗 Связанные документы

- [ARCHITECTURE.md](ARCHITECTURE.md) — Архитектура проекта
- [CURRENT_STATUS.md](../CURRENT_STATUS.md) — Текущий статус
- [LLM_INTEGRATION_PLAN.md](LLM_INTEGRATION_PLAN.md) — Детальный план LLM интеграции