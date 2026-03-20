# Step 6 — Тесты

**Место в плане:** Шаг 6 из 6.После всех предыдущих шагов .

## Тест 1: `RequestedViewInfoTest`(domain)

Путь: `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/code/RequestedViewInfoTest.kt`

Проверяем:
-data class equality
-elementPath = null для не - ELEMENT гранулярностей
        -elementPath непустой для ELEMENT

```kotlin
@Test
fun `ELEMENT granularity carries elementPath`() {
    val info = RequestedViewInfo(
        path = "src/main/kotlin/Foo.kt",
        granularity = CodeGranularity.ELEMENT,
        elementPath = "class[Foo]/function[bar]"
    )
    assertEquals("class[Foo]/function[bar]", info.elementPath)
}

@Test
fun `FULL granularity has null elementPath by default`() {
    val info = RequestedViewInfo("src/Foo.kt", CodeGranularity.FULL)
    assertNull(info.elementPath)
}
```

## Тест 2: `AppliedModInfoTest`(domain)

Путь: `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/modification/AppliedModInfoTest.kt`

Проверяем `toCategory()` для каждого типа `Modification` :

```kotlin
@Test
fun `CreateFile maps to FILE_LEVEL`() {
    val mod = Modification.CreateFile(ElementPath("file:src/Foo.kt"), "content")
    assertEquals(ModificationCategory.FILE_LEVEL, mod.toCategory())
}

@Test
fun `ReplaceElement maps to ELEMENT_LEVEL`() {
    val mod = Modification.ReplaceElement(ElementPath("file:src/Foo.kt/class[Foo]/function[bar]"), "fun bar() {}")
    assertEquals(ModificationCategory.ELEMENT_LEVEL, mod.toCategory())
}

@Test
fun `AddImport maps to IMPORT`() {
    val mod = Modification.AddImport(ElementPath("file:src/Foo.kt"), "com.example.Bar")
    assertEquals(ModificationCategory.IMPORT, mod.toCategory())
}
// ... аналогично для всех 7 типов Modification
```

## Тест 3: `ConversationRendererTest` — маппинг новых полей

Путь: `maxvibes-plugin/src/test/...ConversationRendererTest.kt`
(если тест уже существует — добавить кейсы)

Проверяем:
-`requestedViews` и `appliedModifications` из `ChatMessage` прокидываются в `DisplayMessage`
        -Для старых сообщений(пустые новые поля) — `DisplayMessage` содержит пустые списки без NPE

```kotlin
@Test
fun `render carries requestedViews into DisplayMessage`() {
    val view = RequestedViewInfo("src/Foo.kt", CodeGranularity.SIGNATURES)
    val message = ChatMessage(
        role = MessageRole.ASSISTANT,
        content = "Here is my response",
        requestedViews = listOf(view)
    )
    val result = ConversationRenderer().render(listOf(message))
    assertEquals(1, result.first().requestedViews.size)
    assertEquals(CodeGranularity.SIGNATURES, result.first().requestedViews.first().granularity)
}

@Test
fun `render on legacy message has empty requestedViews`() {
    val message = ChatMessage(
        role = MessageRole.ASSISTANT,
        content = "Old message",
        attachedFiles = listOf("src/Foo.kt")  // старый формат
    )
    val result = ConversationRenderer().render(listOf(message))
    assertTrue(result.first().requestedViews.isEmpty())
    assertEquals(listOf("src/Foo.kt"), result.first().attachedFiles)  // legacy сохранено
}
```

## Запуск тестов

```bash
    ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -application:test
# plugin tests — через IntelliJ IDEA runner(не Gradle, из - за javaagent конфликта)
```
