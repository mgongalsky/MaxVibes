package com.maxvibes.application.port.output

/**
 * JSON protocol constants for Clipboard mode LLM communication.
 *
 * All field name strings are centralized here. When adding or renaming
 * a protocol field — add/update the constant here, then reference it
 * in [JsonClipboardProtocolCodec]. Never hardcode field names elsewhere.
 */
object ClipboardRequestSchema {

    // ── Meta fields (LLM behavior markers) ────────────────────────────

    /** JSON key for the protocol marker meta-field. */
    const val META_PROTOCOL = "_protocol"

    /** JSON key for the response format hint meta-field. */
    const val META_RESPONSE_FORMAT = "_responseFormat"

    /** Value for [META_PROTOCOL] — instructs LLM to respond as JSON only. */
    const val PROTOCOL_MARKER =
        "MaxVibes IDE Plugin — respond with JSON only, do NOT use tools/artifacts/computer"

    /** Value for [META_RESPONSE_FORMAT] — describes the expected JSON shape. */
    const val RESPONSE_FORMAT_HINT =
        """Respond with ONLY a raw JSON object: {"message": "...", "requestedFiles": [...], "modifications": []}"""

    // ── Request fields ────────────────────────────────────────────────

    /** System prompt / role instructions for the LLM. */
    const val FIELD_SYSTEM_INSTRUCTION = "systemInstruction"

    /** The developer's task description. */
    const val FIELD_TASK = "task"

    /** IntelliJ project name, for context. */
    const val FIELD_PROJECT_NAME = "projectName"

    /** When true, LLM should plan rather than implement immediately. */
    const val FIELD_PLAN_ONLY = "planOnly"

    /** Project file tree snapshot (string). */
    const val FIELD_FILE_TREE = "fileTree"

    /** Map of file path → file contents attached to the request. */
    const val FIELD_FILES = "files"

    /** List of file paths already gathered in a previous round-trip. */
    const val FIELD_PREVIOUSLY_GATHERED = "previouslyGatheredFiles"

    /** Conversation history to include for multi-turn context. */
    const val FIELD_CHAT_HISTORY = "chatHistory"

    /** Exception stack trace when sending an error to the LLM. */
    const val FIELD_ERROR_TRACE = "errorTrace"

    /** IDE-reported errors (compiler, inspections) attached to request. */
    const val FIELD_IDE_ERRORS = "ideErrors"

    // ── Chat history entry fields ──────────────────────────────────────

    /** Role of the speaker in a history entry ("user" / "assistant"). */
    const val HISTORY_ROLE = "role"

    /** Text content of a history entry. */
    const val HISTORY_CONTENT = "content"

    // ── Response fields ───────────────────────────────────────────────

    /** Human-readable reply or explanation from the LLM. */
    const val RESP_MESSAGE = "message"

    /** Optional chain-of-thought / reasoning from the LLM. */
    const val RESP_REASONING = "reasoning"

    /** Files the LLM requests to see before proceeding. */
    const val RESP_REQUESTED_FILES = "requestedFiles"

    /** List of code modifications to apply in the IDE. */
    const val RESP_MODIFICATIONS = "modifications"

    /** Optional conventional-commit message suggested by the LLM. */
    const val RESP_COMMIT_MESSAGE = "commitMessage"

    // ── Modification entry fields ──────────────────────────────────────

    /** Operation type (CREATE_FILE, REPLACE_ELEMENT, etc.). */
    const val MOD_TYPE = "type"

    /** PSI element path or file path target of the operation. */
    const val MOD_PATH = "path"

    /** Source code content to write / replace. */
    const val MOD_CONTENT = "content"

    /** PSI element kind hint (FUNCTION, PROPERTY, CLASS, …). */
    const val MOD_ELEMENT_KIND = "elementKind"

    /** Insertion position for CREATE_ELEMENT (FIRST_CHILD, LAST_CHILD, AFTER, BEFORE). */
    const val MOD_POSITION = "position"

    /** Fully-qualified import path for ADD_IMPORT / REMOVE_IMPORT. */
    const val MOD_IMPORT_PATH = "importPath"

    // ── Fallback defaults ──────────────────────────────────────────────

    /** Default element kind when the field is absent in a modification entry. */
    const val DEFAULT_ELEMENT_KIND = "FILE"

    /** Default insertion position when the field is absent in a modification entry. */
    const val DEFAULT_POSITION = "LAST_CHILD"
}
