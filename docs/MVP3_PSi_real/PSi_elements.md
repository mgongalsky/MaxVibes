# План: Element-Level PSI Editing

## Проблема

Сейчас MaxVibes работает преимущественно на уровне **целых файлов**:
- **Контекст → LLM**: отправляем полный текст файлов (много входных токенов)
- **LLM → Модификации**: получаем `CREATE_FILE` / `REPLACE_FILE` с полным контентом (много выходных токенов)
- **Применение**: перезаписываем файл целиком через `replaceFileContent`

При этом в архитектуре **уже заложены** элементные операции (`REPLACE_ELEMENT`, `CREATE_ELEMENT`, `DELETE_ELEMENT`), `ElementPath`, `PsiNavigator`, `PsiModifier.addElement/replaceElement` — но они почти не используются на практике, потому что LLM не получает правильного контекста и инструкций для точечных правок.

## Цель

Научить систему работать **хирургически**:
- Отправлять в LLM только релевантные элементы (класс, функцию, property) + сигнатуры соседей для контекста
- Получать от LLM точечные модификации: заменить функцию X, добавить property Y, удалить import Z
- Экономия токенов: в 3-10x меньше на типичных задачах

## Архитектурный подход

```
Было:
  File A (500 строк) ──→ LLM ──→ File A' (500 строк, 3 строки изменились)

Станет:
  class User (50 строк)     ──→ LLM ──→ REPLACE_ELEMENT: function[validate] (10 строк)
  + сигнатуры соседей (20 строк)         CREATE_ELEMENT: function[toDTO] (8 строк)
  + imports (5 строк)                     ADD_IMPORT: com.example.DTO
```

---

## Фазы реализации

### Фаза 1: Умная сборка контекста (Context Assembly)

**Цель:** Вместо целых файлов отправлять в LLM структурированное представление с фокусом на нужные элементы.

#### 1.1 Новая доменная модель: `ElementContext`

```kotlin
// maxvibes-domain/model/context/ElementContext.kt

/**
 * Структурированный контекст одного файла для LLM.
 * Вместо полного текста файла — дерево элементов с разным уровнем детализации.
 */
data class FileElementContext(
    val filePath: String,
    val packageName: String?,
    val imports: List<String>,
    val elements: List<ElementSnapshot>
)

/**
 * Снимок элемента кода с настраиваемой глубиной.
 */
sealed interface ElementSnapshot {
    val path: ElementPath
    val name: String
    val kind: ElementKind

    /** Полный код элемента — когда нужна детальная модификация */
    data class Full(
        override val path: ElementPath,
        override val name: String,
        override val kind: ElementKind,
        val content: String,
        val children: List<ElementSnapshot> = emptyList()
    ) : ElementSnapshot

    /** Только сигнатура — для контекста соседей */
    data class Signature(
        override val path: ElementPath,
        override val name: String,
        override val kind: ElementKind,
        val signature: String  // "fun validate(input: String): Boolean"
    ) : ElementSnapshot

    /** Только упоминание — для общей структуры */
    data class Stub(
        override val path: ElementPath,
        override val name: String,
        override val kind: ElementKind
    ) : ElementSnapshot
}
```

#### 1.2 Стратегия фокуса: `ContextFocusStrategy`

```kotlin
// maxvibes-application/port/output/ProjectContextPort.kt (расширение)

/**
 * Определяет уровень детализации для каждого элемента файла.
 */
data class ContextFocus(
    val filePath: String,
    /** Элементы, которые нужно отправить полностью */
    val fullElements: List<String> = emptyList(),  // ["class[User]", "function[validate]"]
    /** Отправить весь файл полностью (fallback) */
    val fullFile: Boolean = false
)
```

**Логика определения фокуса** (в `ContextAwareModifyService`):

1. Planning phase возвращает список файлов
2. Для каждого файла определяем: нужен целиком или частично
3. Если задача типа "добавь функцию в класс User" — фокус на `class[User]` (full), остальные элементы файла — signature
4. Если задача типа "создай новый файл" — тут элементный подход не нужен, используем CREATE_FILE как раньше

#### 1.3 PSI Adapter: `ElementContextBuilder`

```kotlin
// maxvibes-adapter-psi/context/ElementContextBuilder.kt

class ElementContextBuilder(private val project: Project) {

    /**
     * Строит контекст файла с указанным фокусом.
     */
    fun buildFileContext(file: KtFile, focus: ContextFocus): FileElementContext {
        val elements = mutableListOf<ElementSnapshot>()

        for (declaration in file.declarations) {
            val elementPath = buildElementPath(file, declaration)
            val elementName = (declaration as? KtNamedDeclaration)?.name ?: "anonymous"
            val kind = resolveKind(declaration)

            if (shouldBeFullDetail(elementName, kind, focus)) {
                // Полный код элемента + его дети
                elements.add(buildFullSnapshot(declaration, elementPath, kind))
            } else {
                // Только сигнатура
                elements.add(buildSignatureSnapshot(declaration, elementPath, kind))
            }
        }

        return FileElementContext(
            filePath = file.virtualFile.path,
            packageName = file.packageFqName.asString(),
            imports = file.importDirectives.map { it.text },
            elements = elements
        )
    }

    /**
     * Извлекает сигнатуру элемента без тела.
     */
    private fun buildSignatureSnapshot(element: KtDeclaration, ...): ElementSnapshot.Signature {
        val signature = when (element) {
            is KtNamedFunction -> buildFunctionSignature(element)  // "fun name(params): ReturnType"
            is KtClass -> buildClassSignature(element)             // "class Name(params) : SuperTypes"
            is KtProperty -> buildPropertySignature(element)       // "val name: Type"
            else -> element.text.lines().first()
        }
        // ...
    }
}
```

#### 1.4 Формат контекста для LLM

Вместо:
```
=== FILE: src/main/kotlin/User.kt ===
package com.example
import ...
class User(val name: String, val email: String) {
    fun validate(): Boolean { ... 20 строк ... }
    fun toDTO(): UserDTO { ... 15 строк ... }
    fun sendNotification() { ... 30 строк ... }
    companion object { ... 40 строк ... }
}
```

Отправляем:
```
=== FILE: src/main/kotlin/User.kt ===
package com.example
imports: [com.example.dto.UserDTO, com.example.notification.NotificationService]

// FULL ELEMENT — file:src/main/kotlin/User.kt/class[User]/function[validate]
fun validate(): Boolean {
    ... полный код 20 строк ...
}

// SIGNATURE ONLY:
// fun toDTO(): UserDTO
// fun sendNotification()
// companion object { fun fromDTO(dto: UserDTO): User; fun create(name: String, email: String): User }
```

**Экономия**: ~105 строк → ~30 строк (3.5x)

---

### Фаза 2: Расширение планирования (Smart Planning)

**Цель:** Planning phase возвращает не просто список файлов, а элементный фокус.

#### 2.1 Расширение `PlanningResultDTO`

```kotlin
class PlanningResultDTO {
    @Description("List of files to examine")
    var requestedFiles: List<FileFocusDTO> = emptyList()

    @Description("Brief reasoning")
    var reasoning: String? = null
}

class FileFocusDTO {
    @Description("File path relative to project root")
    var path: String = ""

    @Description("true = need entire file content, false = focused view")
    var fullFile: Boolean = false

    @Description("Element names to examine in detail, e.g. ['class[User]', 'function[main]']")
    var focusElements: List<String> = emptyList()
}
```

#### 2.2 Обновлённый planning промпт

```
When specifying files, indicate the level of detail needed:
- fullFile: true — for files you need to see entirely (new dependencies, config files)
- focusElements: ["class[ClassName]", "function[funcName]"] — specific elements to see in full
- Elements not in focusElements will be shown as signatures only

This saves context and lets you focus on what matters.
```

---

### Фаза 3: Элементные модификации от LLM

**Цель:** LLM возвращает точечные операции вместо целых файлов.

#### 3.1 Обновлённый `ModificationDTO`

```kotlin
class ModificationDTO {
    @Description("""
        Type of modification:
        - CREATE_FILE: new file (content = full file)
        - REPLACE_ELEMENT: replace specific element (path must point to element, e.g. file:path/File.kt/class[Name]/function[method])
        - CREATE_ELEMENT: add new element to parent (path = parent, e.g. file:path/File.kt/class[Name])
        - DELETE_ELEMENT: remove element
        - ADD_IMPORT: add import statement (content = import path)
        - REMOVE_IMPORT: remove import statement
        
        PREFER element-level operations over file-level when modifying existing files.
        Only use CREATE_FILE for genuinely new files.
    """)
    var type: String = ""

    @Description("Element path using format: file:path/to/File.kt/class[ClassName]/function[funcName]")
    var path: String = ""

    @Description("Code content. For REPLACE_ELEMENT: complete element code. For CREATE_ELEMENT: new element code.")
    var content: String = ""

    @Description("Kind: FILE, CLASS, FUNCTION, PROPERTY, IMPORT, INIT_BLOCK, COMPANION_OBJECT")
    var elementKind: String = "FILE"

    @Description("Insert position for CREATE_ELEMENT: LAST_CHILD, FIRST_CHILD, BEFORE, AFTER")
    var position: String = "LAST_CHILD"
}
```

#### 3.2 Обновлённый coding промпт

```
## Modification Rules

1. **Prefer element-level operations** over file replacement:
   - To change a function → REPLACE_ELEMENT with path .../function[name]
   - To add a function → CREATE_ELEMENT with path to parent class/file
   - To fix a property → REPLACE_ELEMENT with path .../property[name]
   - Only CREATE_FILE for genuinely new files

2. **Element paths** must match what you see in context:
   - file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
   - file:src/main/kotlin/com/example/User.kt/class[User]/property[name]

3. **Content for REPLACE_ELEMENT** must be the complete element:
   - For a function: full function including annotations, modifiers, signature, body
   - For a class: full class with all its members (or use nested element operations)

4. **Imports**: Use ADD_IMPORT/REMOVE_IMPORT for import changes
```

---

### Фаза 4: Усиление PSI операций

**Цель:** Убедиться что PsiModifier надёжно выполняет все элементные операции.

#### 4.1 Расширение `PsiNavigator`

Текущий `PsiNavigator` уже умеет навигировать по `ElementPath`, но нужно:

- [ ] Поддержка навигации к `property[name]`
- [ ] Поддержка `companion_object` (без имени)
- [ ] Поддержка `init` блоков
- [ ] Поддержка вложенных классов: `class[Outer]/class[Inner]`
- [ ] Поддержка `object[Name]` (Kotlin objects)

#### 4.2 Расширение `PsiModifier`

- [ ] `replaceElement` — заменить конкретный PSI элемент новым (основная операция)
- [ ] `addImport` — добавить import через PSI (не текстовая вставка!)
- [ ] `removeImport` — удалить import
- [ ] `addElement` с позиционированием BEFORE/AFTER конкретного sibling
- [ ] `deleteElement` — удалить элемент с proper whitespace cleanup

#### 4.3 Новая операция: `ADD_IMPORT` / `REMOVE_IMPORT`

```kotlin
// В Modification.kt
data class AddImport(
    override val targetPath: ElementPath,  // file path
    val importPath: String                 // "com.example.dto.UserDTO"
) : Modification

data class RemoveImport(
    override val targetPath: ElementPath,
    val importPath: String
) : Modification
```

В `PsiModifier`:
```kotlin
fun addImport(file: KtFile, importPath: String) {
    val importDirective = KtPsiFactory(project).createImportDirective(
        ImportPath.fromString(importPath)
    )
    file.importList?.add(importDirective)
}
```

---

### Фаза 5: Fallback и гибридный режим

**Цель:** Не ломать существующую функциональность, graceful degradation.

#### 5.1 Стратегия выбора режима

```kotlin
enum class ModificationStrategy {
    FILE_LEVEL,      // Текущий режим — целые файлы
    ELEMENT_LEVEL,   // Новый — элементные правки
    HYBRID           // Автоматический выбор
}
```

Автоматический выбор:
- `CREATE_FILE` → всегда file-level (очевидно)
- Файл < 50 строк → file-level (не стоит оптимизировать)
- Файл > 50 строк, задача затрагивает 1-2 элемента → element-level
- Задача "перепиши весь класс" → file-level

#### 5.2 Fallback

Если element-level модификация провалилась (не нашли элемент, PSI ошибка):
1. Логируем ошибку
2. Пробуем file-level fallback (если есть достаточно контекста)
3. Показываем пользователю diff для ручного применения

---

## Порядок реализации (спринты)

### Спринт 1: Foundation (элементный контекст)
1. `ElementSnapshot`, `FileElementContext` — доменные модели
2. `ElementContextBuilder` — PSI adapter для сборки контекста
3. Формирование текстового контекста с сигнатурами
4. **Тест**: отправить элементный контекст вместо файлового, убедиться что LLM понимает

### Спринт 2: Smart Planning
1. `FileFocusDTO` — расширение planning ответа
2. Обновление planning промпта
3. Интеграция фокуса в `ContextAwareModifyService`
4. **Тест**: planning возвращает фокус на конкретные элементы

### Спринт 3: Element-Level Modifications
1. Обновление coding промпта для элементных операций
2. `ADD_IMPORT` / `REMOVE_IMPORT` операции
3. Расширение `PsiNavigator` (properties, companion, nested classes)
4. Усиление `PsiModifier.replaceElement` (robust replacement)
5. **Тест**: LLM возвращает REPLACE_ELEMENT, успешно применяется

### Спринт 4: Hardening
1. Fallback file-level при ошибках element-level
2. Гибридный автовыбор стратегии
3. UI: показать в чате какие конкретно элементы были изменены
4. Метрики: сравнить токены file-level vs element-level
5. **Тест**: edge cases, большие файлы, вложенные классы

---

## Риски и митигации

| Риск | Митигация |
|------|-----------|
| LLM путает ElementPath формат | Жёсткие примеры в промпте + валидация путей перед применением |
| LLM всё равно выдаёт REPLACE_FILE | Fallback работает, постепенно дотюниваем промпт |
| Сигнатуры недостаточны для контекста | Настраиваемая глубина: можно отправить тело ключевых соседей |
| PSI навигация ломается на edge cases | Comprehensive тесты на разных структурах файлов |
| Элементная замена портит форматирование | `CodeStyleManager.reformat()` после каждой операции |

---

## Метрики успеха

- **Токены**: среднее снижение input/output tokens на 50%+ для modify-existing-code задач
- **Точность**: element-level модификации успешно применяются в 90%+ случаев
- **Скорость**: LLM отвечает быстрее из-за меньшего контекста
- **Fallback rate**: < 10% задач уходят в file-level fallback