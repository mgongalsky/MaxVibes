# STEP 2 — Расширение Application Port

## Цель

1.Добавить метод `getCodeView(request: CodeViewRequest): CodeView` в порт `CodeRepository`
        2.Добавить константы `requestedViews` в `ClipboardRequestSchema`

Конкретная реализация метода — в STEP 5(PSI - адаптер).После этого шага проект * * не компилируется * * до добавления stub - реализации в адаптере —
см.секцию «Временный заглушка » ниже .

## Модуль

`maxvibes-application`

## Предварительные условия

        -STEP 1 выполнен : `CodeGranularity`, `CodeViewRequest`, `CodeView` существуют в domain

## Изменения

### 1.`CodeRepository.kt` — добавить метод

        Найти интерфейс `CodeRepository` в
        `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/CodeRepository.kt`

Добавить после существующих методов :

```kotlin
/**
 * Возвращает содержимое файла с заданной гранулярностью.
 * Позволяет LLM запрашивать только нужный уровень детализации
 * (сигнатуры, outline, конкретный элемент) вместо полного файла.
 *
 * @param request описание запрашиваемого вида
 * @return [CodeView] с текстом, готовым для вставки в промпт
 * @throws IllegalArgumentException если файл не найден или elementPath невалиден
 */
fun getCodeView(request: CodeViewRequest): CodeView
```

Добавить import :
```kotlin
import com . maxvibes . domain . model . code . CodeView
        import com . maxvibes . domain . model . code . CodeViewRequest
```

### 2.`ClipboardRequestSchema.kt` — добавить константы

        Найти объект -компаньон(или object) `ClipboardRequestSchema` и добавить :

```kotlin
// --- Частичная выдача файлов ---

/** Поле в JSON-ответе LLM: структурированные запросы с гранулярностью. */
const val REQUESTED_VIEWS = "requestedViews"

/** Поле внутри элемента requestedViews: путь к файлу. */
const val VIEW_PATH = "path"

/** Поле внутри элемента requestedViews: гранулярность (FULL / SIGNATURES / OUTLINE / ELEMENT). */
const val VIEW_GRANULARITY = "granularity"

/** Поле внутри элемента requestedViews: путь к элементу (только для ELEMENT). */
const val VIEW_ELEMENT_PATH = "elementPath"
```

## Временная заглушка в PSI -адаптере

Чтобы проект компилировался до STEP 5, добавить в
        `PsiCodeRepository.kt` временную реализацию:

```kotlin
override fun getCodeView(request: CodeViewRequest): CodeView {
    // TODO: реализовать в STEP 5 (CodeGranularity feature)
    val fullContent = getFileContent(request.filePath) // существующий метод
    return CodeView(request.filePath, request.granularity, fullContent)
}
```

Эта заглушка возвращает полный файл для любой гранулярности — функционально корректно,
просто не оптимизирует токены . Заменяется реальной реализацией в STEP 5.

## После шага

### Проверка компиляции
```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -adapter - psi:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Smoke test
        Запустить плагин в IDE (Run Plugin).Убедиться что всё работает как раньше —
новый метод пока не вызывается.
