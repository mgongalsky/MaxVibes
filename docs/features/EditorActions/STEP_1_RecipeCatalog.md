# STEP 1 — SkillMdParser + editorSpec

Цель: домен и чистый парсинг фронтматтера с новыми ключами. Всё тестируется Gradle без IDE.

## 1.1 Domain: SkillEditorSpec (новый файл)
`maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/SkillEditorSpec.kt`

```kotlin
package com.maxvibes.domain.model.interaction

/** Editor-integration spec parsed from SKILL.md frontmatter. Null on a skill = not editor-visible. */
data class SkillEditorSpec(
/** Kind labels the skill applies to; "any" matches every element. */
val appliesTo: Set<String>,
/** Prefill template; null = default. Placeholders: {{elementPath}}, {{elementName}}, {{filePath}}. */
val template: String? = null,
/** Attach the element body as one-shot context on invoke. */
val attachElement: Boolean = false
) {
companion object {
/** Vocabulary = kind labels produced by ElementAtCaretResolver (STEP_2) + "any". */
val KNOWN_KINDS = setOf(
"function", "property", "class", "interface",
"object", "companion_object", "enum_entry", "any"
)
}
}
```

## 1.2 Domain: поле в SpecificPrompt
SpecificPrompt.kt переписать через REPLACE_FILE (маленький файл; data class с новым дефолтным полем — все существующие call-sites компилируются):

```kotlin
data class SpecificPrompt(
val name: String,
val content: String,
val description: String = "",
val filePath: String? = null,
val source: PromptSource = PromptSource.LEGACY,
/** Editor-menu spec from frontmatter; null = chat-only skill. */
val editorSpec: SkillEditorSpec? = null
)
```
(enum PromptSource в файле остаётся без изменений.)

## 1.3 Application: SkillMdParser (новый файл)
`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/SkillMdParser.kt`

База — текущая логика parseSkillMd из FileSpecificPromptRepository (однострочный key: value), расширенная минимальным блочным скаляром `key: |` (последующие строки с отступом 2 пробела; пустые строки внутри блока допустимы). Неизвестные ключи игнорируются.

```kotlin
package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.SkillEditorSpec

/**
* Pure SKILL.md frontmatter parser. Single-line `key: value` plus a minimal
* YAML-style block scalar `key: |` (following lines indented by two spaces).
* Unknown keys are ignored — forward/backward compatible. No I/O, Gradle-testable.
*/
object SkillMdParser {

data class Parsed(
val name: String?,
val description: String,
val editorSpec: SkillEditorSpec?,
val body: String
)

fun parse(text: String): Parsed {
if (!text.startsWith("---")) return Parsed(null, "", null, text)
val end = text.indexOf("\n---", startIndex = 3)
if (end <= 0) return Parsed(null, "", null, text)
val frontmatter = text.substring(3, end)
val body = text.substring(end + 4).trimStart('\n', '\r')

var name: String? = null
var description = ""
var appliesTo: Set<String>? = null
var template: String? = null
var attach = false

val lines = frontmatter.lines()
var i = 0
while (i < lines.size) {
val line = lines[i]
val idx = line.indexOf(':')
if (idx <= 0 || line.startsWith(" ")) { i++; continue }
val key = line.substring(0, idx).trim()
var value = line.substring(idx + 1).trim()
if (value == "|") {
val block = StringBuilder()
i++
while (i < lines.size && (lines[i].startsWith("  ") || lines[i].isBlank())) {
block.append(lines[i].removePrefix("  ")).append('\n')
i++
}
value = block.toString().trimEnd('\n')
} else {
value = value.trim('"', '\'')
i++
}
when (key) {
"name" -> if (value.isNotBlank()) name = value
"description" -> description = value
"applies-to" -> appliesTo = value.split(',')
.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
"attach-element" -> attach = value.equals("true", ignoreCase = true)
"editor-template" -> template = value.ifBlank { null }
}
}
val spec = appliesTo?.takeIf { it.isNotEmpty() }?.let { SkillEditorSpec(it, template, attach) }
return Parsed(name, description, spec, body)
}
}
```

## 1.4 Репозиторий: делегация
REPLACE_ELEMENT на function[parseSkillMd] в FileSpecificPromptRepository:

```kotlin
private fun parseSkillMd(file: File, source: PromptSource, fallbackName: String): SpecificPrompt? = try {
val parsed = SkillMdParser.parse(file.readText(Charsets.UTF_8))
SpecificPrompt(
name = parsed.name ?: fallbackName,
content = parsed.body,
description = parsed.description,
filePath = file.absolutePath,
source = source,
editorSpec = parsed.editorSpec
)
} catch (e: Exception) {
null
}
```
+ ADD_IMPORT com.maxvibes.application.service.SkillMdParser. readLegacyFile не трогаем — легаси всегда editorSpec = null.

## 1.5 SpecificPromptService: два новых метода (CREATE_ELEMENT)

```kotlin
/** Skills visible in the editor menu for an element of [kind]; empty when kind is null. */
fun editorSkillsFor(kind: String?): List<SpecificPrompt> {
if (kind == null) return emptyList()
return repository.loadAll().filter { p ->
val spec = p.editorSpec ?: return@filter false
"any" in spec.appliesTo || kind in spec.appliesTo
}
}
```

```kotlin
/** Renders the prefill text for an editor skill invocation. */
fun renderEditorTemplate(prompt: SpecificPrompt, elementPath: String, elementName: String, filePath: String): String {
val template = prompt.editorSpec?.template
?: "Apply the '${prompt.name}' skill to {{elementPath}}."
return template
.replace("{{elementPath}}", elementPath)
.replace("{{elementName}}", elementName)
.replace("{{filePath}}", filePath)
}
```

## 1.6 Тесты (Gradle)
`maxvibes-application/src/test/kotlin/com/maxvibes/application/service/SkillMdParserTest.kt`:
1. Без фронтматтера → body = весь текст, spec = null.
2. Однострочные name/description (с кавычками и без) — как раньше.
3. applies-to: "function, class" → set из двух; editorSpec не null.
4. editor-template блочный скаляр: многострочный, с пустой строкой внутри, плейсхолдеры выживают дословно.
5. attach-element: true/false/мусор.
6. Неизвестный ключ игнорируется; фронтматтер без закрывающего --- → весь текст = body.
7. applies-to отсутствует, но editor-template есть → spec = null (не editor-скилл).
Плюс тесты editorSkillsFor (fake-репозиторий: фильтрация по kind, "any", kind=null) и renderEditorTemplate (дефолтный шаблон, подстановки).

## Проверка шага
`gradlew.bat :maxvibes-domain:compileKotlin ; gradlew.bat :maxvibes-application:test` — зелёный; плагин собирается.
