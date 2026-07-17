# STEP 4 — Динамическая группа экшенов из скиллов

Цель: подменю в EditorPopupMenu, наполняемое скиллами с editorSpec, отфильтрованными по kind элемента под кареткой, + две утилитарные кнопки. Классы грузятся ТОЛЬКО при наличии Kotlin-плагина (регистрация в maxvibes-kotlin.xml).

## 4.1 Новый файл
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/action/MaxVibesOnElementGroup.kt`

```kotlin
package com.maxvibes.plugin.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.maxvibes.adapter.psi.operation.ElementAtCaretResolver
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.ui.EditorPrefill

/**
* Dynamic editor submenu built from skills whose editorSpec matches the element
* at caret. Registered in maxvibes-kotlin.xml (optional Kotlin dependency):
* references Kotlin PSI via the resolver, must not load on IDEs without Kotlin.
* Reads skills from disk on every popup — accepted MVP trade-off (few small files).
*/
class MaxVibesOnElementGroup : ActionGroup() {

override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

override fun update(e: AnActionEvent) {
val isKotlin = runReadAction { e.getData(CommonDataKeys.PSI_FILE)?.language?.id == "kotlin" }
e.presentation.isEnabledAndVisible = e.project != null && isKotlin
}

override fun getChildren(e: AnActionEvent?): Array<AnAction> {
val project = e?.project ?: return EMPTY_ARRAY
val resolved = CaretResolution.from(e) // null when caret is not on a declaration
val skills = MaxVibesService.getInstance(project)
.specificPromptService.editorSkillsFor(resolved?.kind)
val actions = mutableListOf<AnAction>()
skills.forEach { actions += SkillRecipeAction(it.name) }
if (actions.isNotEmpty()) actions += Separator.getInstance()
actions += AddElementReferenceAction()
actions += AddElementToContextAction()
return actions.toTypedArray()
}
}

/** Snapshot of the element at caret, resolved in one read action. */
class CaretResolution(
val filePath: String,
val elementPath: String,
val elementName: String,
val kind: String,
private val elementText: String?
) {
fun elementContext(): String? =
elementText?.let { "--- Element: " + elementPath + " ---\n" + it }

fun chipLabel(): String = kind + " " + elementName

companion object {
fun from(e: AnActionEvent): CaretResolution? {
val project = e.project ?: return null
val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
return runReadAction {
val basePath = project.basePath ?: ""
val filePath = psiFile.virtualFile?.path
?.removePrefix(basePath)?.removePrefix("/") ?: psiFile.name
val c = ElementAtCaretResolver.resolve(psiFile, editor.caretModel.offset, filePath)
val path = c.elementPath
val name = c.elementName
val kind = c.kind
if (path == null || name == null || kind == null) null
else CaretResolution(filePath, path, name, kind, c.elementText)
}
}

fun warnNoElement(project: Project) {
Messages.showInfoMessage(
project,
"Put the caret on a named declaration (function, class, property)",
"MaxVibes"
)
}
}
}

/** One menu item per editor-visible skill. Holds only the NAME — everything is re-resolved on click. */
class SkillRecipeAction(private val skillName: String) : AnAction(skillName) {

override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

override fun actionPerformed(e: AnActionEvent) {
val project = e.project ?: return
val resolved = CaretResolution.from(e) ?: return CaretResolution.warnNoElement(project)
val service = MaxVibesService.getInstance(project)
val skill = service.specificPromptService.loadAll().firstOrNull { it.name == skillName }
val spec = skill?.editorSpec
if (skill == null || spec == null) {
Messages.showInfoMessage(project, "Skill '" + skillName + "' is gone or lost its editor spec", "MaxVibes")
return
}
val text = service.specificPromptService.renderEditorTemplate(
skill, resolved.elementPath, resolved.elementName, resolved.filePath
)
val ctx = if (spec.attachElement) resolved.elementContext() else null
ChatPrefill.publish(
project,
EditorPrefill(
text = text,
oneShotSkillName = skill.name,
elementContext = ctx,
elementLabel = resolved.chipLabel()
)
)
}
}

/** Appends the element path to the current chat input (does not overwrite). */
class AddElementReferenceAction : AnAction("Add Element Reference") {
override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
override fun actionPerformed(e: AnActionEvent) {
val project = e.project ?: return
val resolved = CaretResolution.from(e) ?: return CaretResolution.warnNoElement(project)
ChatPrefill.publish(project, EditorPrefill(text = resolved.elementPath, append = true))
}
}

/** Attaches the element body as one-shot context without any prefill text or skill. */
class AddElementToContextAction : AnAction("Add Element to Context") {
override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
override fun actionPerformed(e: AnActionEvent) {
val project = e.project ?: return
val resolved = CaretResolution.from(e) ?: return CaretResolution.warnNoElement(project)
ChatPrefill.publish(
project,
EditorPrefill(
text = "",
oneShotSkillName = null,
elementContext = resolved.elementContext(),
elementLabel = resolved.chipLabel()
)
)
}
}
```

## 4.2 Регистрация — maxvibes-kotlin.xml (Максим правит вручную)
Перед правкой глянуть текущее содержимое; добавить внутрь idea-plugin:

```xml
<actions>
<group id="MaxVibes.OnElement"
class="com.maxvibes.plugin.action.MaxVibesOnElementGroup"
text="Vibe: On Element"
description="MaxVibes skills for the element at caret"
popup="true"
icon="AllIcons.Actions.Lightning">
<add-to-group group-id="MaxVibes.ActionGroup" anchor="first"/>
</group>
</actions>
```

## Заметки
- update() — только дешёвая проверка языка; getChildren резолвит kind (walk под read action на BGT) — без этого меню не отфильтровать до клика. Полный контекст и текст элемента берутся заново в actionPerformed (меню модально — каретка не двигается).
- SkillRecipeAction хранит только имя: скилл и элемент перечитываются на клике — нет захвата stale PSI, а правка SKILL.md применяется мгновенно.
- Каретка не на объявлении → в меню только две утилитарные кнопки (обе покажут подсказку при клике в этом состоянии).

## Проверка шага
Rebuild + sandbox: правый клик в Kotlin-файле → MaxVibes → Vibe: On Element. Без установленных editor-скиллов в меню только две утилитарные кнопки — это норма до STEP_5.
