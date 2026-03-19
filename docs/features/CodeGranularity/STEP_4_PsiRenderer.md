# STEP 4 — PsiCodeViewRenderer

## Цель

Создать новый класс `PsiCodeViewRenderer` в `maxvibes-adapter-psi` .
Он принимает PSI - элементы(KtFile, KtClass, KtNamedDeclaration) и рендерит
«вид» нужной гранулярности в виде строки.Это самая важная и тестируемая часть фичи.

## Модуль

`maxvibes-adapter-psi`

## Предварительные условия

        -STEP 1 выполнен (CodeGranularity, CodeView, CodeViewRequest существуют)

## Новый файл

        `maxvibes-adapter-psi/src/main/kotlin/com/maxvibes/adapter/psi/renderer/PsiCodeViewRenderer.kt`

## API класса

```kotlin
/**
 * Рендерит PSI-элементы в текстовые представления заданной гранулярности.
 * Используется для минимизации токенов при передаче контекста LLM.
 *
 * Не зависит от состояния проекта — принимает готовые PSI-объекты.
 * Все методы выполняются в read action (вызывающая сторона обязана обеспечить).
 */
class PsiCodeViewRenderer {

    /**
     * Рендерит сигнатуры всех деклараций верхнего уровня в файле.
     * Тела функций заменяются на `{ ... }`. Package и imports включены.
     *
     * @param ktFile PSI-файл Kotlin
     * @return строка с сигнатурами, готовая для вставки в промпт
     */
    fun renderSignatures(ktFile: KtFile): String

    /**
     * Рендерит outline класса: суперклассы, свойства (имя + тип), сигнатуры методов.
     * Более компактен чем [renderSignatures] для классов с большими типами.
     *
     * @param ktClass PSI-класс
     * @return строка с outline
     */
    fun renderOutline(ktClass: KtClass): String

    /**
     * Возвращает полный текст PSI-элемента (функции, свойства, класса).
     * Для ELEMENT-гранулярности.
     *
     * @param element произвольная именованная декларация
     * @return текст элемента как в исходном файле
     */
    fun renderElement(element: KtNamedDeclaration): String
}
```

## Детали реализации

### renderSignatures(ktFile)

1.Взять `ktFile.packageDirective?.text` +`"\n\n"`
2.Для каждого `declaration in ktFile.declarations`:
-Если это `KtNamedFunction` → `renderFunctionSignature(fn)`
-Если это `KtClass` → заголовок класса +тело в виде `{\n    // members...\n}`
        с рекурсивным рендерингом членов (только сигнатуры)
-Если это `KtProperty` → `declaration.text`(свойства короткие, тело включаем)
-Иначе → `declaration.text`
3.Объединить через `"\n\n"`

### renderFunctionSignature(fn: KtNamedFunction)

-Взять всё до `bodyExpression` / `bodyBlockExpression`
        -Если функция имеет тело `= expr` → заменить на `= ...`
-Если функция имеет блочное тело `{ ... }` → заменить на `{ /* ... */ }`
        -KDoc сохранить

**Пример результата : * *
```kotlin
/** Обрабатывает входящий запрос. */
fun processRequest(input: String, options: ProcessOptions): Result<String> { /* ... */
}
```

### renderOutline(ktClass)

```
class MyService : BaseService(), ServiceInterface {

    // Properties
    private val repository: MyRepository
    val isActive: Boolean

    // Functions
    fun doWork(input: String): Result<Unit> { /* ... */
    }

    private fun validate(item: Item): Boolean { /* ... */
    }
}
```

### renderElement(element)

Просто `element.text` — PSI уже даёт точный исходный текст.

## После шага

### Проверка компиляции
```bash
    ./ gradlew : maxvibes -adapter - psi:compileKotlin
```

### Ручной smoke test

В unit -тесте(можно написать прямо сейчас без IntelliJ):
если планируем тестировать с fixture `.kt` файлом — создать тест с `MockPsiFile`
        или через `LightPlatformTestCase`.Полные тесты — в STEP 6.
