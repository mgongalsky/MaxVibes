Отличная идея с самобутстрапингом! Давай сначала посмотрю нашу историю чатов, чтобы освежить контекст и понять паттерны работы.Отлично, вижу полную картину! Давай структурирую.

---

## 📊 Текущее состояние (MVP1)

**Что работает:**
- LangChain4j интеграция (OpenAI, Anthropic, Ollama)
- Генерация целых приложений (Snake, Arkanoid работают)
- PSI операции: создание файлов, модификация кода
- Базовый UI: контекстное меню, Tool Window

**Чего не хватает для "нашего workflow":**
- Автоматический сбор контекста (сейчас это делаем мы руками)
- Plan-based workflow (анализ → план → запрос файлов → выполнение)
- Работа с несколькими файлами в одном запросе

---

## 🎯 MVP2: Context-Aware Workflow

По сути нужно заложить в MaxVibes то, как мы сейчас работаем:

```
┌────────────────────────────────────────────────────────────────┐
│  Наш текущий workflow (ручной)                                │
├────────────────────────────────────────────────────────────────┤
│  1. Формулируем задачу                                        │
│  2. Даём архитектуру/описание проекта (ARCHITECTURE.md)       │
│  3. Даём список файлов                                        │
│  4. Claude: "Дай мне файлы X, Y, Z"                           │
│  5. Копипастим файлы → Claude генерит код                     │
│  6. Вставляем код обратно в проект                            │
└────────────────────────────────────────────────────────────────┘
                           ↓
┌────────────────────────────────────────────────────────────────┐
│  MVP2 workflow (автоматический)                                │
├────────────────────────────────────────────────────────────────┤
│  1. User: описывает задачу в Tool Window                      │
│  2. MaxVibes: читает project description, file tree           │
│  3. LLM (Planning): "Для этого нужны файлы X, Y, Z"           │
│  4. MaxVibes: собирает контент этих файлов через PSI/VFS      │
│  5. LLM (Coding): генерит модификации                         │
│  6. MaxVibes: применяет изменения через PSI                   │
└────────────────────────────────────────────────────────────────┘
```

**Ключевые компоненты для MVP2:**

| Компонент | Что делает |
|-----------|------------|
| `ProjectContextProvider` | Собирает метаданные: структура, README, architecture docs |
| `FileTreeProvider` | Генерит дерево файлов с фильтрацией (.gitignore, build/) |
| `ContextGatherer` | По списку путей собирает содержимое файлов |
| `PlanningAgent` | Анализирует задачу → выдаёт список нужных файлов |
| `CodingAgent` | Получает контекст → генерит модификации |

---

## 🔄 Стратегия самобутстрапинга

**Идея:** MVP2 умеет собирать контекст → MVP3 делаем с помощью MVP2

**Как это работает с GitHub:**

```
┌─────────────────────────────────────────────────────────────────┐
│  Итерация N: Разработка фичи F                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Создаём ветку: feature/F                                   │
│                                                                 │
│  2. Запускаем MaxVibes на САМОМ СЕБЕ:                          │
│     - Открываем проект MaxVibes в IntelliJ                     │
│     - Даём задачу: "Добавь компонент X"                        │
│     - MaxVibes собирает контекст своего же кода                │
│     - Генерит модификации                                      │
│                                                                 │
│  3. Review + тестирование вручную                              │
│                                                                 │
│  4. Коммитим: "feat: add component X (self-generated)"         │
│                                                                 │
│  5. Merge в main                                                │
│                                                                 │
│  6. Следующая итерация использует обновлённый MaxVibes         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Практические шаги:**

1. **Разделить работу на "безопасные" и "рискованные" части:**
    - Безопасные (MVP2 пишет): новые файлы, изолированные компоненты
    - Рискованные (мы пишем): core изменения, breaking changes

2. **Версионирование через Git tags:**
   ```
   v0.1.0 - MVP1 (текущий)
   v0.2.0 - MVP2 (context gathering)
   v0.3.0 - MVP3 (частично self-generated)
   ```

3. **Фидбек-луп:**
    - MaxVibes генерит код
    - Мы review'им, фиксим баги
    - Баги → improvements в промптах/логике
    - Следующая генерация лучше

---

## 📋 Конкретный план MVP2

**Фаза 1: Context Infrastructure (1-2 недели)**

```kotlin
// Новые классы:
maxvibes-application/
  └── port/output/
      └── ProjectContextPort.kt     // Интерфейс сбора контекста

maxvibes-adapter-psi/
  └── context/
      ├── PsiProjectContextProvider.kt  // Читает структуру проекта
      ├── FileTreeBuilder.kt            // Строит дерево файлов
      └── ContentGatherer.kt            // Собирает содержимое файлов
```

**Фаза 2: Two-Phase LLM (1 неделя)**

```kotlin
// Planning phase
interface PlanningService {
    suspend fun analyzeTask(
        task: String,
        projectContext: ProjectContext,  // структура, docs
        fileTree: FileTree                // список файлов
    ): ContextRequest  // какие файлы нужны
}

// Coding phase  
interface CodingService {
    suspend fun generateModifications(
        task: String,
        context: GatheredContext  // содержимое файлов
    ): List<Modification>
}
```

**Фаза 3: Integration (1 неделя)**

```kotlin
// Объединённый workflow
class ContextAwareModifyUseCase(
    private val contextProvider: ProjectContextPort,
    private val planner: PlanningService,
    private val coder: CodingService,
    private val repository: CodeRepository
) {
    suspend fun execute(task: String): Result<ModificationResult> {
        // 1. Собираем метаданные
        val projectContext = contextProvider.getProjectContext()
        val fileTree = contextProvider.getFileTree()
        
        // 2. Planning phase
        val contextRequest = planner.analyzeTask(task, projectContext, fileTree)
        
        // 3. Собираем нужные файлы
        val gatheredContext = contextProvider.gatherFiles(contextRequest.filePaths)
        
        // 4. Coding phase
        val modifications = coder.generateModifications(task, gatheredContext)
        
        // 5. Применяем
        return repository.applyModifications(modifications)
    }
}
```

---

## 🚀 Практические первые шаги

**Сейчас (чтобы начать):**

1. Скинь мне текущую структуру `maxvibes-application` и `maxvibes-adapter-psi`
2. Определим интерфейс `ProjectContextPort`
3. Начнём с `FileTreeBuilder` — это изолированная задача

**Для самобутстрапинга:**

1. Создай в проекте файл `PROJECT_CONTEXT.md`:
   ```markdown
   # MaxVibes Project Context
   
   ## Architecture
   [краткое описание модулей]
   
   ## Key Files
   - maxvibes-domain/: модели
   - maxvibes-application/: use cases
   ...
   
   ## Current Task
   Implementing context gathering for MVP2
   ```

2. Этот файл MaxVibes будет читать первым при анализе задач

**Хочешь:**
- Начать с кода для context gathering?
- Обсудить детали промптов для Planning/Coding phases?
- Что-то другое уточнить в стратегии?