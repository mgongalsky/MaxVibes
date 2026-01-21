# MaxVibes: Архитектура

## 🏗️ Обзор

MaxVibes построен по принципам **Clean Architecture** с чётким разделением на слои. Зависимости направлены только внутрь — от внешних слоёв к ядру (Domain).

```
┌─────────────────────────────────────────────────────────────────┐
│                     INFRASTRUCTURE                              │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │   Plugin    │ │  PSI API    │ │  LLM APIs   │               │
│  │  (IntelliJ) │ │  (IntelliJ) │ │(LangChain4j)│               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                    INTERFACE ADAPTERS                           │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ PsiCode     │ │ LangChain   │ │ IdeNotif    │               │
│  │ Repository  │ │ LLMService  │ │ Service     │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                      APPLICATION                                │
│  ┌─────────────┐ ┌─────────────┐                               │
│  │ ModifyCode  │ │ AnalyzeCode │   ← Use Cases                 │
│  │ Service     │ │ Service     │                               │
│  └─────────────┘ └─────────────┘                               │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ CodeRepo    │ │ LLMService  │ │ Notification│  ← Ports      │
│  │ (port)      │ │ (port)      │ │ Port        │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                        DOMAIN                                   │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ CodeElement │ │ Modification│ │ ElementPath │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

## 📦 Модули

### maxvibes-domain (Core)

**Зависимости:** Никаких (только Kotlin stdlib + kotlinx.serialization)

Содержит чистые доменные модели без привязки к фреймворкам.

```
maxvibes-domain/
└── src/main/kotlin/com/maxvibes/domain/
    └── model/
        ├── code/
        │   ├── ElementPath.kt      # Адресация элементов кода
        │   └── CodeElement.kt      # CodeFile, CodeClass, CodeFunction, CodeProperty
        └── modification/
            └── Modification.kt     # CreateFile, ReplaceElement, DeleteElement...
```

**Ключевые классы:**

| Класс | Описание |
|-------|----------|
| `ElementPath` | Value class для адресации: `file:src/User.kt/class[User]/function[greet]` |
| `CodeElement` | Sealed interface: CodeFile, CodeClass, CodeFunction, CodeProperty |
| `Modification` | Sealed interface для операций: CreateFile, ReplaceElement, DeleteElement... |
| `ModificationResult` | Success/Failure результат операции |

---

### maxvibes-shared

**Зависимости:** kotlinx.coroutines

Общие утилиты, используемые всеми модулями.

```
maxvibes-shared/
└── src/main/kotlin/com/maxvibes/shared/
    └── result/
        └── Result.kt    # Either-like тип: Success<T> | Failure<E>
```

---

### maxvibes-application

**Зависимости:** domain, shared

Use Cases и порты (интерфейсы для адаптеров).

```
maxvibes-application/
└── src/main/kotlin/com/maxvibes/application/
    ├── port/
    │   ├── input/                    # Driving ports (Use Cases)
    │   │   ├── ModifyCodeUseCase.kt
    │   │   └── AnalyzeCodeUseCase.kt
    │   └── output/                   # Driven ports (SPIs)
    │       ├── CodeRepository.kt     # Работа с кодом
    │       ├── LLMService.kt         # Работа с LLM
    │       └── NotificationPort.kt   # UI уведомления
    └── service/
        ├── ModifyCodeService.kt      # Реализация ModifyCodeUseCase
        └── AnalyzeCodeService.kt     # Реализация AnalyzeCodeUseCase
```

**Порты (интерфейсы):**

```kotlin
interface CodeRepository {
    suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError>
    suspend fun applyModification(modification: Modification): ModificationResult
    suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult>
    // ...
}

interface LLMService {
    suspend fun generateModifications(instruction: String, context: LLMContext): Result<List<Modification>, LLMError>
    suspend fun analyzeCode(question: String, codeElements: List<CodeElement>): Result<AnalysisResponse, LLMError>
}

interface NotificationPort {
    fun showProgress(message: String, fraction: Double?)
    fun showSuccess(message: String)
    fun showError(message: String)
    // ...
}
```

---

### maxvibes-adapter-psi

**Зависимости:** domain, application, shared, IntelliJ Platform, Kotlin Plugin

Адаптер для работы с кодом через IntelliJ PSI API.

```
maxvibes-adapter-psi/
└── src/main/kotlin/com/maxvibes/adapter/psi/
    ├── PsiCodeRepository.kt         # Реализует CodeRepository
    ├── mapper/
    │   └── PsiToDomainMapper.kt     # KtFile → CodeFile, KtClass → CodeClass...
    ├── operation/
    │   ├── PsiNavigator.kt          # Навигация по PSI дереву
    │   └── PsiModifier.kt           # Модификация PSI элементов
    └── kotlin/
        └── KotlinElementFactory.kt  # Создание Kotlin PSI элементов
```

**Ключевые классы:**

| Класс | Описание |
|-------|----------|
| `PsiCodeRepository` | Реализует `CodeRepository`, координирует mapper/navigator/modifier |
| `PsiToDomainMapper` | Конвертирует PSI → Domain модели |
| `PsiNavigator` | Находит PSI элементы по `ElementPath` |
| `PsiModifier` | Выполняет модификации (add/replace/delete) |
| `KotlinElementFactory` | Создаёт PSI элементы из текста |

**Как работает ElementPath → PSI:**

```
ElementPath: "file:src/User.kt/class[User]/function[greet]"
                    ↓
PsiNavigator.findFile("src/User.kt")  → KtFile
                    ↓
findChildByKindAndName(file, "class", "User")  → KtClass
                    ↓
findChildByKindAndName(class, "function", "greet")  → KtNamedFunction
```

---

### maxvibes-adapter-llm

**Зависимости:** domain, application, shared, LangChain4j

Адаптер для работы с LLM через LangChain4j фреймворк.

```
maxvibes-adapter-llm/
└── src/main/kotlin/com/maxvibes/adapter/llm/
    ├── LangChainLLMService.kt      # Реализует LLMService
    ├── LLMServiceFactory.kt        # Фабрика для создания сервисов
    └── config/
        └── LLMProviderConfig.kt    # Конфигурация провайдера
```

**Поддерживаемые провайдеры:**

| Провайдер | Модели | Описание |
|-----------|--------|----------|
| OpenAI | gpt-4o, gpt-4o-mini, gpt-4-turbo | Облачный API |
| Anthropic | claude-sonnet-4, claude-opus-4 | Облачный API |
| Ollama | llama3.2, codellama, mistral | Локальный сервер |

**Как работает LangChain4j:**

```
┌─────────────────────────────────────────────────────────────────┐
│                         Твой код                                │
│                             │                                   │
│                             ▼                                   │
│                   ChatLanguageModel                             │
│                   (единый интерфейс)                            │
│                             │                                   │
│              ┌──────────────┼──────────────┐                    │
│              ▼              ▼              ▼                    │
│      OpenAiChatModel  AnthropicModel  OllamaChatModel           │
│              │              │              │                    │
│              ▼              ▼              ▼                    │
│      api.openai.com  api.anthropic.com  localhost:11434         │
└─────────────────────────────────────────────────────────────────┘
```

---

### maxvibes-plugin

**Зависимости:** Все модули

IntelliJ плагин — точка входа, UI, DI.

```
maxvibes-plugin/
├── src/main/kotlin/com/maxvibes/plugin/
│   ├── action/
│   │   ├── ModifyCodeAction.kt      # Action: правый клик → Modify Code
│   │   └── AnalyzeCodeAction.kt     # Action: правый клик → Analyze Code
│   ├── ui/
│   │   ├── MaxVibesToolWindowFactory.kt  # Tool Window справа
│   │   └── AnalysisResultDialog.kt       # Диалог с результатом анализа
│   ├── service/
│   │   ├── MaxVibesService.kt       # Service Locator / DI
│   │   ├── MockLLMService.kt        # Мок LLM для тестирования
│   │   └── IdeNotificationService.kt # Реализует NotificationPort
│   └── settings/
│       ├── MaxVibesSettings.kt           # Persistent settings
│       ├── MaxVibesSettingsPanel.kt      # Settings UI
│       └── MaxVibesSettingsConfigurable.kt # Settings integration
└── src/main/resources/META-INF/
    └── plugin.xml                   # Конфигурация плагина
```

---

## 🔄 Поток данных

### Modify Code Flow

```
User: "add function toString"
         │
         ▼
┌─────────────────────┐
│  ModifyCodeAction   │  ← Plugin layer
└─────────────────────┘
         │ ModifyCodeRequest
         ▼
┌─────────────────────┐
│  ModifyCodeService  │  ← Application layer
└─────────────────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐ ┌────────┐
│CodeRepo│ │LLMSvc  │  ← Ports
└────────┘ └────────┘
    │         │
    ▼         ▼
┌────────┐ ┌──────────┐
│PsiCode │ │LangChain │  ← Adapters
│Repo    │ │LLMService│
└────────┘ └──────────┘
    │         │
    ▼         │
┌────────┐    │
│PSI API │    │  List<Modification>
└────────┘    │
    │         │
    ▼         ▼
┌─────────────────────┐
│  Apply Modifications │
└─────────────────────┘
         │
         ▼
    Code Modified!
```

### Детальный flow ModifyCodeService

```kotlin
suspend fun execute(request: ModifyCodeRequest): ModifyCodeResponse {
    // 1. Читаем код через CodeRepository (→ PsiCodeRepository)
    val codeElement = codeRepository.getElement(request.targetPath)
    
    // 2. Создаём контекст для LLM
    val context = LLMContext(relevantCode = listOf(codeElement))
    
    // 3. Генерируем модификации через LLMService (→ LangChainLLMService)
    val modifications = llmService.generateModifications(request.instruction, context)
    
    // 4. Применяем модификации через CodeRepository
    val results = codeRepository.applyModifications(modifications)
    
    // 5. Уведомляем UI
    notificationPort.showSuccess("Applied ${results.size} modifications")
    
    return ModifyCodeResponse(success = true, results = results)
}
```

---

## 🧪 Тестирование

### Уровни тестов

| Уровень | Модуль | Фреймворк | Что тестируем |
|---------|--------|-----------|---------------|
| Unit | domain | JUnit 5 | ElementPath, CodeElement, Modification |
| Unit | shared | JUnit 5 | Result |
| Unit | application | JUnit 5 + MockK | Services с мок-зависимостями |
| Integration | adapter-psi | IntelliJ Test Framework | PsiToDomainMapper, PsiNavigator |

### Запуск тестов

```bash
# Все тесты
./gradlew test

# Конкретный модуль
./gradlew :maxvibes-domain:test
./gradlew :maxvibes-application:test
```

---

## 📐 Принципы дизайна

### 1. Dependency Rule

Зависимости направлены только внутрь:
- Domain не зависит ни от чего
- Application зависит только от Domain
- Adapters зависят от Application и Domain
- Plugin зависит от всех

### 2. Port/Adapter Pattern

- **Ports** — интерфейсы в Application layer
- **Adapters** — реализации в отдельных модулях
- Легко заменять реализации (Mock → Real LLM)

### 3. Immutable Domain Models

Все доменные модели — `data class` или `value class`, immutable.

### 4. Result Type

Вместо исключений используем `Result<T, E>` для явной обработки ошибок.

---

## 🔮 Точки расширения

### Добавление нового языка

```kotlin
// 1. Создать адаптер в maxvibes-adapter-psi
class JavaElementFactory(project: Project) { ... }
class JavaToDomainMapper { ... }

// 2. Расширить PsiCodeRepository
when (psiFile) {
    is KtFile -> kotlinMapper.mapFile(psiFile)
    is PsiJavaFile -> javaMapper.mapFile(psiFile)
}
```

### Добавление нового LLM провайдера

```kotlin
// В LangChainLLMService.createChatModel()
when (config.providerType) {
    LLMProviderType.OPENAI -> OpenAiChatModel.builder()...
    LLMProviderType.ANTHROPIC -> AnthropicChatModel.builder()...
    LLMProviderType.OLLAMA -> OllamaChatModel.builder()...
    LLMProviderType.NEW_PROVIDER -> NewProviderChatModel.builder()...
}
```

---

## 📁 Конфигурация

### build.gradle.kts (корневой)

```kotlin
plugins {
    kotlin("jvm") version "1.9.21" apply false
    kotlin("plugin.serialization") version "1.9.21" apply false
    id("org.jetbrains.intellij") version "1.16.1" apply false
}

subprojects {
    // Java 17 для совместимости с IntelliJ Platform
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### plugin.xml

```xml
<idea-plugin>
    <id>com.maxvibes.plugin</id>
    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.kotlin</depends>

    <extensions>
        <projectService serviceImplementation="...MaxVibesService"/>
        <toolWindow id="MaxVibes" factoryClass="...MaxVibesToolWindowFactory"/>
        <applicationConfigurable instance="...MaxVibesSettingsConfigurable"/>
    </extensions>

    <actions>
        <action id="MaxVibes.ModifyCode" class="...ModifyCodeAction"/>
        <action id="MaxVibes.AnalyzeCode" class="...AnalyzeCodeAction"/>
    </actions>
</idea-plugin>
```