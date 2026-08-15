package com.maxvibes.application.port.output

/** Centralized JSON field names and protocol hints for MaxVibes interactions. */
object InteractionRequestSchema {
    const val META_PROTOCOL = "_protocol"
    const val META_RESPONSE_FORMAT = "_responseFormat"

    const val PROTOCOL_MARKER =
        "MaxVibes IDE Plugin — respond with JSON only, do NOT use tools/artifacts/computer"

    const val RESPONSE_FORMAT_HINT =
        """Respond with ONLY a raw JSON object: {"message": "...", "requestedViews": [{"path": "...", "granularity": "SIGNATURES"}], "modifications": [], "checks": [{"kind": "BUILD", "scope": null, "reason": "Verify the project builds", "timeoutSec": 300}], "turnIntent": "DONE"}. For project compilation or test execution, ALWAYS use "checks" with kind "BUILD" or "TESTS" so the IDE runs it natively; NEVER put build, compile, or test invocations in "commands". Set "turnIntent" to "CONTINUE" only when this task is genuinely unfinished and you want another turn without the user; use "DONE" when you are finished or need the user to decide."""

    const val FIELD_SYSTEM_INSTRUCTION = "systemInstruction"
    const val FIELD_CURRENT_MESSAGE = "current_message"
    const val FIELD_PROJECT_NAME = "projectName"
    const val FIELD_PLAN_ONLY = "planOnly"
    const val FIELD_FILE_TREE = "fileTree"
    const val FIELD_FILES = "files"
    const val FIELD_PREVIOUSLY_GATHERED = "previouslyGatheredFiles"
    const val FIELD_CHAT_HISTORY = "chatHistory"
    const val FIELD_ERROR_TRACE = "errorTrace"
    const val FIELD_IDE_ERRORS = "ideErrors"
    const val FIELD_SPECIFIC_PROMPT = "specificPrompt"

    const val HISTORY_ROLE = "role"
    const val HISTORY_CONTENT = "content"

    const val RESP_MESSAGE = "message"
    const val RESP_REASONING = "reasoning"
    const val RESP_REQUESTED_FILES = "requestedFiles"
    const val RESP_MODIFICATIONS = "modifications"
    const val RESP_COMMIT_MESSAGE = "commitMessage"
    const val RESP_COMMANDS = "commands"
    const val RESP_QUESTIONS = "questions"
    const val RESP_TURN_INTENT = "turnIntent"

    const val CMD_COMMAND = "command"
    const val CMD_REASON = "reason"
    const val CMD_TIMEOUT_SEC = "timeoutSec"
    const val FIELD_COMMAND_RESULTS = "commandResults"

    const val Q_ID = "id"
    const val Q_QUESTION = "question"
    const val Q_OPTIONS = "options"

    const val MOD_TYPE = "type"
    const val MOD_PATH = "path"
    const val MOD_CONTENT = "content"
    const val MOD_ELEMENT_KIND = "elementKind"
    const val MOD_POSITION = "position"
    const val MOD_IMPORT_PATH = "importPath"
    const val MOD_NEW_NAME = "newName"
    const val MOD_DESTINATION = "destination"

    const val DEFAULT_ELEMENT_KIND = "FILE"
    const val DEFAULT_POSITION = "LAST_CHILD"

    const val REQUESTED_VIEWS = "requestedViews"
    const val VIEW_PATH = "path"
    const val VIEW_GRANULARITY = "granularity"
    const val VIEW_ELEMENT_PATH = "elementPath"

    const val RESP_PLAN = "plan"
    const val FIELD_CURRENT_PLAN = "currentPlan"
    const val PLAN_TITLE = "title"
    const val PLAN_DOC_PATH = "docPath"
    const val PLAN_STEPS = "steps"
    const val PLAN_STEP_ID = "id"
    const val PLAN_STEP_STATUS = "status"

    const val RESP_DIAGRAM = "diagram"
    const val DIAG_TITLE = "title"
    const val DIAG_NODES = "nodes"
    const val DIAG_EDGES = "edges"
    const val DIAG_GROUPS = "groups"
    const val DIAG_SEAMS = "seams"
    const val DIAG_ID = "id"
    const val DIAG_KIND = "kind"
    const val DIAG_NAME = "name"
    const val DIAG_SIGNATURE = "signature"
    const val DIAG_FILE_PATH = "filePath"
    const val DIAG_LOC = "loc"
    const val DIAG_FROM = "from"
    const val DIAG_TO = "to"
    const val DIAG_LABEL = "label"
    const val DIAG_NODE_IDS = "nodeIds"
    const val DIAG_PARENT_ID = "parentId"
    const val DIAG_FROM_GROUP_ID = "fromGroupId"
    const val DIAG_TO_GROUP_ID = "toGroupId"
    const val DIAG_RATIONALE = "rationale"
    const val DIAG_CROSSING_EDGE_IDS = "crossingEdgeIds"

    const val RESP_CHECKS = "checks"
    const val CHECK_KIND = "kind"
    const val CHECK_SCOPE = "scope"
    const val CHECK_REASON = "reason"
    const val CHECK_TIMEOUT_SEC = "timeoutSec"
    const val FIELD_CHECK_RESULTS = "checkResults"
}