# STEP 5 — Реализация getCodeView () в PsiCodeRepository

## Цель

Заменить временную заглушку(из STEP 2) реальной реализацией:
вызвать `PsiCodeViewRenderer` с нужной гранулярностью и вернуть `CodeView` .

## Модуль

`maxvibes-adapter-psi`

## Предварительные условия

        -STEP 2: метод `getCodeView()` объявлен в порту, заглушка в PsiCodeRepository
-STEP 4: `PsiCodeViewRenderer` реализован

## Изменения в `PsiCodeRepository.kt`

Заменить заглушку на реальную реализацию:

```kotlin
/**
 * Возвращает содержимое файла с заданной гранулярностью.
 * Все PSI-операции выполняются в read action.
 *
 * @param request параметры запроса: путь, гранулярность, elementPath
 * @return [CodeView] с текстом для промпта
 * @throws IllegalArgumentException если файл или элемент не найден
 */
override fun getCodeView(request: CodeViewRequest): CodeView {
    return ApplicationManager.getApplication().runReadAction(Computable {
        val content = when (request.granularity) {

            // Полный файл — существующий путь
            CodeGranularity.FULL ->
                getFileContent(request.filePath)

            // Только сигнатуры всех деклараций
            CodeGranularity.SIGNATURES -> {
                val ktFile = findKtFile(request.filePath)
                    ?: error("File not found: ${request.filePath}")
                renderer.renderSignatures(ktFile)
            }

            // Outline первого класса в файле (или конкретного если path указывает на класс)
            CodeGranularity.OUTLINE -> {
                val ktFile = findKtFile(request.filePath)
                    ?: error("File not found: ${request.filePath}")
                val ktClass = ktFile.declarations
                    .filterIsInstance<KtClass>()
                    .firstOrNull()
                    ?: error("No class found in: ${request.filePath}")
                renderer.renderOutline(ktClass)
            }

            // Конкретный элемент по elementPath
            CodeGranularity.ELEMENT -> {
                val elementPath = request.elementPath
                    ?: error("elementPath is required for ELEMENT granularity")
                val element = navigator.findElement(request.filePath, elementPath)
                    ?: error("Element not found: $elementPath in ${request.filePath}")
                renderer.renderElement(element as KtNamedDeclaration)
            }
        }
        CodeView(request.filePath, request.granularity, content)
    })
}
```

### Инжектировать `PsiCodeViewRenderer`

        Добавить в конструктор или поле `PsiCodeRepository` :
```kotlin
private val renderer = PsiCodeViewRenderer()
```

## После шага

### Проверка компиляции
```bash
    ./ gradlew : maxvibes -adapter - psi:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Smoke test в IDE

        1.Запустить плагин (`Run Plugin`)
2.В чате, в следующем сообщении после запроса с `requestedViews`, убедиться:
-`SIGNATURES`: файл пришёл без тел функций
-`ELEMENT`: пришёл только запрошенный метод
        -`FULL` и старый `requestedFiles` : работают как раньше
        3.Проверить что старый flow (без `requestedViews`) не сломан
