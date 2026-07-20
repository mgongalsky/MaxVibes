package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.application.port.output.InteractionRequestSchema
import com.maxvibes.domain.model.interaction.*
import kotlinx.serialization.json.*
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.planning.TaskPlan
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.PlanStepStatus
import com.maxvibes.domain.model.planning.PlanDiagram
import com.maxvibes.domain.model.planning.DiagramNode
import com.maxvibes.domain.model.planning.DiagramEdge
import com.maxvibes.domain.model.planning.DiagramGroup
import com.maxvibes.domain.model.planning.DiagramSeam
import com.maxvibes.domain.model.planning.DiagramNodeKind
import com.maxvibes.domain.model.planning.DiagramEdgeKind

/**
 * Pure [InteractionProtocolCodec] implementation backed by kotlinx.serialization.
 *
 * Encodes [ClipboardRequest] → pretty-printed JSON and decodes raw LLM
 * response text → [InteractionResponse]. Contains zero IntelliJ Platform SDK
 * imports — only stdlib and kotlinx.serialization — so it is directly
 * unit-testable without an IDE environment.
 *
 * Field name constants are sourced exclusively from [InteractionRequestSchema];
 * never hardcode string keys here.
 */
class JsonInteractionProtocolCodec : InteractionProtocolCodec {

    /** Strict pretty-print encoder for outgoing requests. */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Lenient decoder for incoming LLM responses (unquoted keys, trailing commas, etc.). */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Encode ────────────────────────────────────────────────────────

    override fun encode(request: ClipboardRequest, omitMetaFields: Boolean): String {
        val obj = buildJsonObject {
            // Meta-fields: instruct the LLM how to behave and format its response.
            // Suppressed in Claude Code mode to avoid tripping its prompt-injection
            // classifier — there the same info is supplied via --append-system-prompt.
            if (!omitMetaFields) {
                put(InteractionRequestSchema.META_PROTOCOL, InteractionRequestSchema.PROTOCOL_MARKER)
                put(InteractionRequestSchema.META_RESPONSE_FORMAT, InteractionRequestSchema.RESPONSE_FORMAT_HINT)
            }

            // System prompt — omitted when blank to save tokens, and unconditionally
            // suppressed when omitMetaFields is true (same reason as meta-fields above).
            if (!omitMetaFields && request.systemInstruction.isNotBlank()) {
                put(InteractionRequestSchema.FIELD_SYSTEM_INSTRUCTION, request.systemInstruction)
            }

            // Task-scoped specific prompt — omitted when null ("Just Code" mode)
            request.specificPrompt?.takeIf { it.isNotBlank() }?.let {
                put(InteractionRequestSchema.FIELD_SPECIFIC_PROMPT, it)
            }

            // Current user message for this turn (always present)
            put(InteractionRequestSchema.FIELD_CURRENT_MESSAGE, request.currentMessage)
            put(InteractionRequestSchema.FIELD_PROJECT_NAME, request.projectName)

            // Optional flags
            if (request.planOnly) {
                put(InteractionRequestSchema.FIELD_PLAN_ONLY, true)
            }

            // Project file tree snapshot
            if (request.fileTree.isNotBlank()) {
                put(InteractionRequestSchema.FIELD_FILE_TREE, request.fileTree)
            }

            // Full file contents for freshly-requested files
            if (request.freshFiles.isNotEmpty()) {
                putJsonObject(InteractionRequestSchema.FIELD_FILES) {
                    request.freshFiles.forEach { (path, content) -> put(path, content) }
                }
            }

            // Paths already gathered in a previous round-trip (no content, just references)
            if (request.previouslyGatheredPaths.isNotEmpty()) {
                putJsonArray(InteractionRequestSchema.FIELD_PREVIOUSLY_GATHERED) {
                    request.previouslyGatheredPaths.forEach { add(it) }
                }
            }

            // Multi-turn conversation history
            if (request.chatHistory.isNotEmpty()) {
                putJsonArray(InteractionRequestSchema.FIELD_CHAT_HISTORY) {
                    request.chatHistory.forEach { entry ->
                        addJsonObject {
                            put(InteractionRequestSchema.HISTORY_ROLE, entry.role)
                            put(InteractionRequestSchema.HISTORY_CONTENT, entry.content)
                        }
                    }
                }
            }

            // Optional error context (stack traces, IDE diagnostics)
            request.attachedContext?.takeIf { it.isNotBlank() }?.let {
                put(InteractionRequestSchema.FIELD_ERROR_TRACE, it)
            }
            request.ideErrors?.takeIf { it.isNotBlank() }?.let {
                put(InteractionRequestSchema.FIELD_IDE_ERRORS, it)
            }
            request.commandResults?.takeIf { it.isNotBlank() }?.let {
                put(InteractionRequestSchema.FIELD_COMMAND_RESULTS, it)
            }

            // Current plan snapshot (planner panel) — lets the model see the live state,
            // including checkboxes the user toggled manually since the last turn.
            request.currentPlan?.let { plan ->
                putJsonObject(InteractionRequestSchema.FIELD_CURRENT_PLAN) {
                    put(InteractionRequestSchema.PLAN_TITLE, plan.title)
                    plan.docPath?.let { put(InteractionRequestSchema.PLAN_DOC_PATH, it) }
                    putJsonArray(InteractionRequestSchema.PLAN_STEPS) {
                        plan.steps.forEach { step ->
                            addJsonObject {
                                put(InteractionRequestSchema.PLAN_STEP_ID, step.id)
                                put(InteractionRequestSchema.PLAN_TITLE, step.title)
                                put(InteractionRequestSchema.PLAN_STEP_STATUS, step.status.name)
                                step.docPath?.let { put(InteractionRequestSchema.PLAN_DOC_PATH, it) }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ── Decode ────────────────────────────────────────────────────────

    /**
     * Decodes a raw LLM response into a [InteractionResponse].
     *
     * Tries the following extraction strategies in order of preference:
     * 1. ` ```json ` … ` ``` ` fenced block
     * 2. Plain ` ``` ` code block whose content starts with `{`
     * 3. Raw text that starts directly with `{`
     * 4. JSON embedded anywhere inside free-form text ([findEmbeddedJson])
     *
     * Falls back to a plain-text [ClipboardResponse] when no JSON is found
     * but the input is non-blank. Returns `null` for blank input or when
     * parsing fails completely.
     */
    override fun decode(rawText: String): InteractionResponse? {
        val text = rawText.trim()
        if (text.isBlank()) return null

        // Strategy 1: ```json ... ``` block
        val jsonBlockMatch = Regex("`{3}json\\s*\\n([\\s\\S]*?)\\n\\s*`{3}").find(text)
        val jsonBlock = jsonBlockMatch?.groupValues?.get(1)?.trim()

        // Strategy 2: plain ``` ... ``` block that starts with '{'
        val codeBlockMatch = Regex("`{3}\\s*\\n([\\s\\S]*?)\\n\\s*`{3}")
            .findAll(text)
            .firstOrNull { it.groupValues[1].trim().startsWith("{") }
        val codeBlock = codeBlockMatch?.groupValues?.get(1)?.trim()

        // Strategy 3: whole text is raw JSON
        val rawJson = if (text.startsWith("{")) text else null

        // Strategy 4: JSON embedded somewhere in the text
        val embedded = findEmbeddedJson(text)

        val jsonText = jsonBlock ?: codeBlock ?: rawJson ?: embedded

        // No JSON found — treat non-empty text without braces as a plain message
        if (jsonText == null) {
            return if (text.isNotBlank() && !text.contains("{")) {
                InteractionResponse(message = text)
            } else null
        }

        // Extract any human-readable text surrounding the JSON block
        val surroundingText = extractSurroundingText(text, jsonBlockMatch ?: codeBlockMatch)

        return try {
            val response = parseUnifiedResponse(jsonText)
            // If the JSON message field is blank, use surrounding prose as the message
            if (response.message.isBlank() && surroundingText.isNotBlank()) {
                response.copy(message = surroundingText)
            } else response
        } catch (e: Exception) {
            // Primary parse failed — attempt fallback on embedded JSON
            try {
                embedded?.let { parseUnifiedResponse(it) }
            } catch (_: Exception) {
                if (surroundingText.isNotBlank()) InteractionResponse(message = surroundingText) else null
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun parseUnifiedResponse(jsonText: String): InteractionResponse {
        val obj = lenientJson.parseToJsonElement(jsonText).jsonObject

        // Legacy requestedFiles — kept as-is for backward compatibility
        val legacyFiles: List<String> = obj[InteractionRequestSchema.RESP_REQUESTED_FILES]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        // 1. Legacy requestedFiles → CodeViewRequest(path, FULL)
        val fromFiles: List<CodeViewRequest> = legacyFiles
            .map { CodeViewRequest(it, CodeGranularity.FULL) }

        // 2. New requestedViews → CodeViewRequest with explicit granularity
        val fromViews: List<CodeViewRequest> = obj[InteractionRequestSchema.REQUESTED_VIEWS]?.jsonArray
            ?.toCodeViewRequests() ?: emptyList()

        // 3. Merge: requestedViews wins on duplicate path
        val mergedRequests: List<CodeViewRequest> = (fromViews + fromFiles)
            .distinctBy { it.filePath }

        return InteractionResponse(
            message = obj[InteractionRequestSchema.RESP_MESSAGE]?.jsonPrimitive?.contentOrNull ?: "",
            reasoning = obj[InteractionRequestSchema.RESP_REASONING]?.jsonPrimitive?.contentOrNull,
            requestedFiles = legacyFiles,
            codeViewRequests = mergedRequests,
            modifications = obj[InteractionRequestSchema.RESP_MODIFICATIONS]?.jsonArray
                ?.mapNotNull { parseModification(it.jsonObject) } ?: emptyList(),
            commitMessage = obj[InteractionRequestSchema.RESP_COMMIT_MESSAGE]?.jsonPrimitive?.contentOrNull,
            commands = obj[InteractionRequestSchema.RESP_COMMANDS]?.jsonArray
                ?.mapNotNull { parseCommand(it.jsonObject) } ?: emptyList(),
            questions = obj[InteractionRequestSchema.RESP_QUESTIONS]?.jsonArray
                ?.mapNotNull { parseQuestion(it.jsonObject) } ?: emptyList(),
            plan = parsePlan(obj),
            diagram = parseDiagram(obj)
        )
    }

    /**
     * Parses a single `modifications[]` entry.
     *
     * Returns `null` (and silently skips the entry) if mandatory fields
     * [InteractionRequestSchema.MOD_TYPE] or [InteractionRequestSchema.MOD_PATH] are absent.
     */
    private fun parseModification(obj: JsonObject): InteractionModification? {
        val type = obj[InteractionRequestSchema.MOD_TYPE]?.jsonPrimitive?.contentOrNull ?: return null
        val path = obj[InteractionRequestSchema.MOD_PATH]?.jsonPrimitive?.contentOrNull ?: return null
        return InteractionModification(
            type = type,
            path = path,
            content = obj[InteractionRequestSchema.MOD_CONTENT]?.jsonPrimitive?.contentOrNull ?: "",
            elementKind = obj[InteractionRequestSchema.MOD_ELEMENT_KIND]?.jsonPrimitive?.contentOrNull
                ?: InteractionRequestSchema.DEFAULT_ELEMENT_KIND,
            position = obj[InteractionRequestSchema.MOD_POSITION]?.jsonPrimitive?.contentOrNull
                ?: InteractionRequestSchema.DEFAULT_POSITION,
            importPath = obj[InteractionRequestSchema.MOD_IMPORT_PATH]?.jsonPrimitive?.contentOrNull ?: "",
            newName = obj[InteractionRequestSchema.MOD_NEW_NAME]?.jsonPrimitive?.contentOrNull ?: "",
            destination = obj[InteractionRequestSchema.MOD_DESTINATION]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    /**
     * Parses a single `commands[]` entry.
     * Returns `null` (skips the entry) if the mandatory `command` field is absent or blank.
     */
    private fun parseCommand(obj: JsonObject): InteractionCommand? {
        val command = obj[InteractionRequestSchema.CMD_COMMAND]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        return InteractionCommand(
            command = command,
            reason = obj[InteractionRequestSchema.CMD_REASON]?.jsonPrimitive?.contentOrNull ?: "",
            timeoutSec = obj[InteractionRequestSchema.CMD_TIMEOUT_SEC]?.jsonPrimitive?.intOrNull ?: 120
        )
    }

    /**
     * Parses a single `questions[]` entry.
     * Returns `null` (skips the entry) if the mandatory `question` field is absent or blank.
     * A missing `id` falls back to a hash-derived value so the entry survives when the
     * model forgets the field. Parsing is deliberately lenient: the 1-4 questions /
     * 2-4 options limits are enforced prompt-side, not here.
     */
    private fun parseQuestion(obj: JsonObject): InteractionQuestion? {
        val question = obj[InteractionRequestSchema.Q_QUESTION]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val id = obj[InteractionRequestSchema.Q_ID]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: ("q" + question.hashCode().toString(16))
        val options = obj[InteractionRequestSchema.Q_OPTIONS]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?: emptyList()
        return InteractionQuestion(id = id, question = question, options = options)
    }

    /**
     * Parses the optional `plan` response field into a [TaskPlan].
     *
     * Tolerant by design (a malformed plan must never fail the whole response):
     * - unknown/broken step `status` → [PlanStepStatus.PENDING];
     * - missing step `id` → 1-based ordinal;
     * - steps without a `title` are skipped; a blank plan title becomes "Plan";
     * - any parsing error yields `null` (plan unchanged) instead of throwing.
     *
     * Returns `null` when the field is absent. A present plan with an empty `steps`
     * array is returned as-is — the service layer treats it as "clear the plan".
     */
    private fun parsePlan(obj: JsonObject): TaskPlan? = runCatching {
        val planObj = obj[InteractionRequestSchema.RESP_PLAN] as? JsonObject ?: return null
        val steps = planObj[InteractionRequestSchema.PLAN_STEPS]?.jsonArray
            ?.mapIndexedNotNull { index, element ->
                val stepObj = element as? JsonObject ?: return@mapIndexedNotNull null
                val title = stepObj[InteractionRequestSchema.PLAN_TITLE]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val id = stepObj[InteractionRequestSchema.PLAN_STEP_ID]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: (index + 1).toString()
                val status = stepObj[InteractionRequestSchema.PLAN_STEP_STATUS]?.jsonPrimitive?.contentOrNull
                    ?.let { raw -> runCatching { PlanStepStatus.valueOf(raw.uppercase()) }.getOrNull() }
                    ?: PlanStepStatus.PENDING
                val docPath = stepObj[InteractionRequestSchema.PLAN_DOC_PATH]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                PlanStep(id = id, title = title, status = status, docPath = docPath)
            } ?: emptyList()
        TaskPlan(
            title = planObj[InteractionRequestSchema.PLAN_TITLE]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "Plan",
            docPath = planObj[InteractionRequestSchema.PLAN_DOC_PATH]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() },
            steps = steps
        )
    }.getOrNull()

    /**
     * Parses the optional `diagram` response field into a [PlanDiagram].
     *
     * Tolerant by design (a malformed diagram must never fail the whole response):
     * - nodes without a non-blank `id` or `name` are skipped;
     * - edges without a non-blank `id`, `from` or `to` are skipped (seams reference edges by id);
     * - unknown node `kind` → CLASS, unknown edge `kind` → USES;
     * - a group without a `label` falls back to its id; groups without an `id` are skipped;
     * - seams without both group ids are skipped;
     * - any parsing error yields `null` (no diagram) instead of throwing.
     *
     * Returns `null` when the field is absent or not an object.
     */
    private fun parseDiagram(obj: JsonObject): PlanDiagram? {
        val diagramObj = obj[InteractionRequestSchema.RESP_DIAGRAM] as? JsonObject ?: return null
        return try {
            fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.strList(key: String): List<String> =
                (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

            fun JsonObject.objList(key: String): List<JsonObject> =
                (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

            val nodes = diagramObj.objList(InteractionRequestSchema.DIAG_NODES).mapNotNull { o ->
                val id = o.str(InteractionRequestSchema.DIAG_ID)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name =
                    o.str(InteractionRequestSchema.DIAG_NAME)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = o.str(InteractionRequestSchema.DIAG_KIND)?.trim()?.uppercase()
                    ?.let { k -> DiagramNodeKind.values().firstOrNull { it.name == k } } ?: DiagramNodeKind.CLASS
                DiagramNode(
                    id = id,
                    kind = kind,
                    name = name,
                    signature = o.str(InteractionRequestSchema.DIAG_SIGNATURE),
                    filePath = o.str(InteractionRequestSchema.DIAG_FILE_PATH),
                    loc = o.str(InteractionRequestSchema.DIAG_LOC)?.toIntOrNull()
                )
            }

            val edges = diagramObj.objList(InteractionRequestSchema.DIAG_EDGES).mapNotNull { o ->
                val id = o.str(InteractionRequestSchema.DIAG_ID)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val from =
                    o.str(InteractionRequestSchema.DIAG_FROM)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val to = o.str(InteractionRequestSchema.DIAG_TO)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = o.str(InteractionRequestSchema.DIAG_KIND)?.trim()?.uppercase()
                    ?.let { k -> DiagramEdgeKind.values().firstOrNull { it.name == k } } ?: DiagramEdgeKind.USES
                DiagramEdge(
                    id = id,
                    from = from,
                    to = to,
                    kind = kind,
                    label = o.str(InteractionRequestSchema.DIAG_LABEL)
                )
            }

            val groups = diagramObj.objList(InteractionRequestSchema.DIAG_GROUPS).mapNotNull { o ->
                val id = o.str(InteractionRequestSchema.DIAG_ID)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DiagramGroup(
                    id = id,
                    label = o.str(InteractionRequestSchema.DIAG_LABEL)?.takeIf { it.isNotBlank() } ?: id,
                    nodeIds = o.strList(InteractionRequestSchema.DIAG_NODE_IDS),
                    parentId = o.str(InteractionRequestSchema.DIAG_PARENT_ID)?.takeIf { it.isNotBlank() }
                )
            }

            val seams = diagramObj.objList(InteractionRequestSchema.DIAG_SEAMS).mapNotNull { o ->
                val fromGroupId = o.str(InteractionRequestSchema.DIAG_FROM_GROUP_ID)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val toGroupId = o.str(InteractionRequestSchema.DIAG_TO_GROUP_ID)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                DiagramSeam(
                    fromGroupId = fromGroupId,
                    toGroupId = toGroupId,
                    rationale = o.str(InteractionRequestSchema.DIAG_RATIONALE),
                    crossingEdgeIds = o.strList(InteractionRequestSchema.DIAG_CROSSING_EDGE_IDS)
                )
            }

            PlanDiagram(
                title = diagramObj.str(InteractionRequestSchema.DIAG_TITLE),
                nodes = nodes,
                edges = edges,
                groups = groups,
                seams = seams
            )
        } catch (e: Exception) {
            null
        }
    }

    internal fun findEmbeddedJson(text: String): String? {
        // Look for the leftmost occurrence of any known indicator key
        val indicators = listOf(
            "\"${InteractionRequestSchema.RESP_REQUESTED_FILES}\"",
            "\"${InteractionRequestSchema.RESP_MODIFICATIONS}\"",
            "\"${InteractionRequestSchema.RESP_MESSAGE}\"",
            "\"${InteractionRequestSchema.RESP_QUESTIONS}\""
        )
        val startIndex = indicators
            .mapNotNull { indicator ->
                val idx = text.indexOf(indicator)
                if (idx >= 0) {
                    // Walk back to find the opening '{' of the enclosing object
                    var braceIdx = idx
                    while (braceIdx > 0 && text[braceIdx] != '{') braceIdx--
                    if (text[braceIdx] == '{') braceIdx else null
                } else null
            }
            .minOrNull() ?: return null

        // Walk forward to find the matching closing '}'
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIndex until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return text.substring(startIndex, i + 1)
            }
        }
        return null
    }

    /**
     * Extracts human-readable prose before and after a matched JSON block.
     *
     * Used to populate [InteractionResponse.message] when the LLM places
     * an explanation outside the JSON object.
     *
     * @param fullText  the complete raw LLM response
     * @param jsonMatch the regex match that identified the JSON block
     * @return trimmed surrounding text, or empty string if [jsonMatch] is null
     */
    private fun extractSurroundingText(fullText: String, jsonMatch: MatchResult?): String {
        if (jsonMatch == null) return ""
        val before = fullText.substring(0, jsonMatch.range.first).trim()
        val after = fullText.substring(jsonMatch.range.last + 1).trim()
        return buildString {
            if (before.isNotBlank()) append(before)
            if (before.isNotBlank() && after.isNotBlank()) append("\n\n")
            if (after.isNotBlank()) append(after)
        }
            .replace(Regex("^`{3}\\w*\\s*"), "")
            .replace(Regex("\\s*`{3}$"), "")
            .trim()
    }

    /**
     * Parses a [JsonArray] of `requestedViews` entries into a list of [CodeViewRequest].
     *
     * Each entry is expected to have the shape:
     * `{ "path": "...", "granularity": "SIGNATURES", "elementPath": "..." }`
     *
     * Rules:
     * - `granularity` is optional — defaults to [CodeGranularity.FULL]
     * - `elementPath` is optional — defaults to `null`
     * - An unknown `granularity` value falls back to [CodeGranularity.FULL] without throwing
     * - Entries with a blank or absent `path` are silently skipped
     */
    private fun JsonArray.toCodeViewRequests(): List<CodeViewRequest> =
        mapNotNull { element ->
            val obj = element.jsonObject

            // path is mandatory — skip entry if blank or absent
            val path = obj[InteractionRequestSchema.VIEW_PATH]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // granularity is optional; unknown values fall back to FULL
            val granularity = obj[InteractionRequestSchema.VIEW_GRANULARITY]?.jsonPrimitive?.contentOrNull
                ?.let { raw -> runCatching { CodeGranularity.valueOf(raw.uppercase()) }.getOrElse { CodeGranularity.FULL } }
                ?: CodeGranularity.FULL

            // elementPath is optional (required only for ELEMENT granularity)
            val elementPath = obj[InteractionRequestSchema.VIEW_ELEMENT_PATH]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            CodeViewRequest(path, granularity, elementPath)
        }
}
