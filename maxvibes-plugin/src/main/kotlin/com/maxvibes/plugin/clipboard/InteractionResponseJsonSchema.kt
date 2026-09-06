package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.InteractionRequestSchema as S
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.planning.DiagramEdgeKind
import com.maxvibes.domain.model.planning.DiagramNodeKind
import com.maxvibes.domain.model.planning.PlanStepStatus
import com.maxvibes.domain.model.turn.TurnIntent
import kotlinx.serialization.json.*

/** Shared structured-output contract. Semantic operation validation remains in the application. */
internal object InteractionResponseJsonSchema {
    private fun scalar(type: String) = buildJsonObject { put("type", type) }
    private val text = scalar("string")
    private val integer = scalar("integer")
    private fun nullable(schema: JsonObject) = buildJsonObject {
        put("anyOf", JsonArray(listOf(schema, scalar("null"))))
    }

    private fun array(items: JsonObject) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun choice(values: List<String>) = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

    private fun obj(vararg fields: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(linkedMapOf(*fields)))
        put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }

    private val view = obj(
        S.VIEW_PATH to text,
        S.VIEW_GRANULARITY to choice(CodeGranularity.values().map { it.name }),
        S.VIEW_ELEMENT_PATH to nullable(text)
    )
    private val modification = obj(
        S.MOD_TYPE to choice(
            listOf(
                "CREATE_FILE", "REPLACE_FILE", "DELETE_FILE", "CREATE_ELEMENT",
                "REPLACE_ELEMENT", "DELETE_ELEMENT", "ADD_IMPORT", "REMOVE_IMPORT", "RENAME_ELEMENT",
                "SAFE_DELETE", "MOVE_ELEMENT"
            )
        ),
        S.MOD_PATH to text,
        S.MOD_CONTENT to nullable(text),
        S.MOD_ELEMENT_KIND to nullable(choice(ElementKind.values().map { it.name })),
        S.MOD_POSITION to nullable(choice(listOf("FIRST_CHILD", "LAST_CHILD", "BEFORE", "AFTER"))),
        S.MOD_IMPORT_PATH to nullable(text),
        S.MOD_NEW_NAME to nullable(text),
        S.MOD_DESTINATION to nullable(text)
    )
    private val command = obj(
        S.CMD_COMMAND to text, S.CMD_REASON to text, S.CMD_TIMEOUT_SEC to integer
    )
    private val check = obj(
        S.CHECK_KIND to choice(listOf("BUILD", "TESTS")),
        S.CHECK_SCOPE to nullable(text), S.CHECK_REASON to text, S.CHECK_TIMEOUT_SEC to integer
    )
    private val question = obj(
        S.Q_ID to text, S.Q_QUESTION to text, S.Q_OPTIONS to array(text)
    )
    private val step = obj(
        S.PLAN_STEP_ID to text, S.PLAN_TITLE to text,
        S.PLAN_STEP_STATUS to choice(PlanStepStatus.values().map { it.name }),
        S.PLAN_DOC_PATH to nullable(text)
    )
    private val plan = obj(
        S.PLAN_TITLE to text, S.PLAN_DOC_PATH to nullable(text), S.PLAN_STEPS to array(step)
    )
    private val node = obj(
        S.DIAG_ID to text, S.DIAG_KIND to choice(DiagramNodeKind.values().map { it.name }),
        S.DIAG_NAME to text, S.DIAG_SIGNATURE to nullable(text),
        S.DIAG_FILE_PATH to nullable(text), S.DIAG_LOC to nullable(integer)
    )
    private val edge = obj(
        S.DIAG_ID to text, S.DIAG_FROM to text, S.DIAG_TO to text,
        S.DIAG_KIND to choice(DiagramEdgeKind.values().map { it.name }), S.DIAG_LABEL to nullable(text)
    )
    private val group = obj(
        S.DIAG_ID to text, S.DIAG_LABEL to text,
        S.DIAG_NODE_IDS to array(text), S.DIAG_PARENT_ID to nullable(text)
    )
    private val seam = obj(
        S.DIAG_FROM_GROUP_ID to text, S.DIAG_TO_GROUP_ID to text,
        S.DIAG_RATIONALE to nullable(text), S.DIAG_CROSSING_EDGE_IDS to array(text)
    )
    private val diagram = obj(
        S.DIAG_TITLE to nullable(text), S.DIAG_NODES to array(node),
        S.DIAG_EDGES to array(edge), S.DIAG_GROUPS to array(group), S.DIAG_SEAMS to array(seam)
    )

    val schema: JsonObject = obj(
        S.RESP_MESSAGE to text,
        S.RESP_REASONING to nullable(text),
        S.RESP_REQUESTED_FILES to array(text),
        S.REQUESTED_VIEWS to array(view),
        S.RESP_MODIFICATIONS to array(modification),
        S.RESP_COMMIT_MESSAGE to nullable(text),
        S.RESP_CHAT_TITLE to nullable(text),
        S.RESP_COMMANDS to array(command),
        S.RESP_CHECKS to array(check),
        S.RESP_QUESTIONS to array(question),
        S.RESP_PLAN to nullable(plan),
        S.RESP_DIAGRAM to nullable(diagram),
        S.RESP_TURN_INTENT to choice(TurnIntent.values().map { it.name })
    )
}
