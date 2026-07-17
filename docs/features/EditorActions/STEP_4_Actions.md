# STEP 4 — Группа экшенов и регистрация

        Цель: подменю в EditorPopupMenu с рецептами.Классы грузятся ТОЛЬКО при наличии Kotlin -плагина.

## 4.1 Новый файл
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/action/MaxVibesOnElementGroup.kt`

```kotlin
package com.maxvibes.plugin.action

import com . intellij . openapi . actionSystem . ActionGroup
        import com . intellij . openapi . actionSystem . ActionUpdateThread
        import com . intellij . openapi . actionSystem . AnAction
        import com . intellij . openapi . actionSystem . AnActionEvent
        import com . intellij . openapi . actionSystem . CommonDataKeys
        import com . intellij . openapi . application . runReadAction
        import com . intellij . openapi . ui . Messages
        import com . maxvibes . adapter . psi . operation . ElementAtCaretResolver
        import com . maxvibes . application . service . EditorRecipe
        import com . maxvibes . application . service . EditorRecipeCatalog

/**
 * Popup group with one action per recipe. Registered in maxvibes-kotlin.xml
 * (optional Kotlin dependency): these classes reference Kotlin PSI through the
 * resolver and must not be loadable on IDEs without the Kotlin plugin.
 */
class MaxVibesOnElementGroup : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = CHILDREN

    override fun update(e: AnActionEvent) {
        val isKotlin = runReadAction {
            e.getData(CommonDataKeys.PSI_FILE)?.language?.id == "kotlin"
        }
        e.presentation.isEnabledAndVisible = e.project != null && isKotlin
    }

    companion object {
        private val CHILDREN: Array<AnAction> =
            EditorRecipeCatalog.recipes.map<EditorRecipe, AnAction> { RecipeAction(it) }.toTypedArray()
    }
}

private class Resolved(val filePath: String, val elementPath: String?, val elementName: String?)

class RecipeAction(private val recipe: EditorRecipe) : AnAction(recipe.title) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val resolved = runReadAction {
            val basePath = project.basePath ?: ""
            val filePath = psiFile.virtualFile?.path?.removePrefix(basePath)?.removePrefix("/")
                ?: psiFile.name
            val caret = ElementAtCaretResolver.resolve(psiFile, editor.caretModel.offset, filePath)
            Resolved(filePath, caret.elementPath, caret.elementName)
        }

        if (recipe.requiresElement && resolved.elementPath == null) {
            Messages.showInfoMessage(
                project,
                "Put the caret on a named declaration (function, class, property)",
                "MaxVibes"
            )
            return
        }

        val text = EditorRecipeCatalog.compose(
            recipe = recipe,
            elementPath = resolved.elementPath,
            filePath = resolved.filePath,
            elementName = resolved.elementName
        )
        ChatPrefill.publish(project, text)
    }
}
```

## 4.2 Регистрация — maxvibes -kotlin.xml(Максим правит вручную)
XML — ручная правка по твоей практике.Перед правкой посмотреть текущее содержимое; добавить блок actions внутрь существующего idea -plugin:

```xml
<actions >
<group id ="MaxVibes.OnElement"
class="com.maxvibes.plugin.action.MaxVibesOnElementGroup"
text = "Vibe: On Element"
description = "MaxVibes recipes for the element at caret"
popup = "true"
icon = "AllIcons.Actions.Lightning" >
<add - to - group group -id = "MaxVibes.ActionGroup" anchor ="first" / >
</group >
</actions >
```

## Заметки
-update() делает только дешёвую проверку языка; резолв элемента строго в actionPerformed(EDT + runReadAction — как в старых экшенах).
-Дети группы создаются один раз из каталога — рецепт добавили в STEP_1, он сам появился в меню.
-Хоткеи не назначаем в MVP(открытый вопрос в PLAN).

## Проверка шага
        Rebuild + sandbox: в Kotlin -файле правый клик → MaxVibes → Vibe: On Element — 8 пунктов .
