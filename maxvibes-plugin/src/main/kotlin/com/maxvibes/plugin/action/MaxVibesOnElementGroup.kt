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
 * these classes reference Kotlin PSI via [ElementAtCaretResolver] and must not
 * be loadable on IDEs without the Kotlin plugin.
 *
 * Skills are re-read from disk on every popup — accepted MVP trade-off
 * (a handful of small files); editing a SKILL.md applies instantly.
 */
class MaxVibesOnElementGroup : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val isKotlin = runReadAction {
            e.getData(CommonDataKeys.PSI_FILE)?.language?.id == "kotlin"
        }
        e.presentation.isEnabledAndVisible = e.project != null && isKotlin
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return AnAction.EMPTY_ARRAY
        val resolved = CaretResolution.from(e) // null when the caret is not on a declaration
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
    /** Labeled element body for the attachedContext channel; null when text is unavailable. */
    fun elementContext(): String? =
        elementText?.let { "--- Element: $elementPath ---\n$it" }

    /** Short chip label, e.g. "function validate". */
    fun chipLabel(): String = "$kind $elementName"

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

/**
 * One menu item per editor-visible skill. Holds only the skill NAME — the skill
 * and the element are re-resolved on click, so there is no stale PSI capture and
 * SKILL.md edits apply without a rebuild.
 */
class SkillRecipeAction(private val skillName: String) : AnAction(skillName) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val resolved = CaretResolution.from(e) ?: return CaretResolution.warnNoElement(project)
        val service = MaxVibesService.getInstance(project)
        val skill = service.specificPromptService.loadAll().firstOrNull { it.name == skillName }
        val spec = skill?.editorSpec
        if (skill == null || spec == null) {
            Messages.showInfoMessage(project, "Skill '$skillName' is gone or lost its editor spec", "MaxVibes")
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
