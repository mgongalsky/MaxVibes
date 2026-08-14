package com.maxvibes.application.service

import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.shared.result.Result
import com.maxvibes.domain.model.chat.CodingAgentProvider

internal class CodingAgentViewResolver(
    private val contextProvider: ProjectContextPort,
    private val codeRepository: CodeRepository,
    private val specificPromptService: SpecificPromptService?,
    private val notificationPort: NotificationPort,
    private val logger: LoggerPort? = null,
    private val provider: CodingAgentProvider = CodingAgentProvider.CLAUDE_CODE
) {
    suspend fun resolve(
        requests: List<CodeViewRequest>,
        state: ClipboardSessionState
    ): Map<String, String>? {
        val skillRequests = requests.filter {
            it.granularity == CodeGranularity.SKILL
        }
        val fullPaths = requests
            .filter { it.granularity == CodeGranularity.FULL }
            .map { it.filePath }
        val partialRequests = requests.filter {
            it.granularity != CodeGranularity.FULL &&
                    it.granularity != CodeGranularity.SKILL
        }

        val fullFiles = gatherFullFiles(fullPaths, state) ?: return null
        val renderedViews = mutableListOf<Pair<CodeViewRequest, String>>()
        fullFiles.forEach { (path, content) ->
            renderedViews += CodeViewRequest(path, CodeGranularity.FULL) to content
        }

        partialRequests.forEach { request ->
            val content = try {
                val view = codeRepository.getCodeView(request)
                log("Rendered ${request.granularity} view for ${request.filePath} (${view.content.length} chars)")
                view.content
            } catch (exception: Exception) {
                log("ERROR: Failed to render ${request.granularity} view for ${request.filePath}: ${exception.message}")
                "// ERROR: Could not render ${request.granularity} view: ${exception.message}"
            }
            renderedViews += request to content
        }

        val skillFiles = skillRequests.associate { request ->
            val body = specificPromptService?.resolveSkillBody(request.filePath)
            log(
                if (body != null) {
                    "Rendered skill '${request.filePath}' (${body.length} chars)"
                } else {
                    "Unknown skill '${request.filePath}'"
                }
            )
            "skill:${request.filePath}" to (
                    body
                        ?: "// ERROR: Unknown skill '${request.filePath}'. Use one of the names from the Skills section."
                    )
        }

        return CodeViewPayloadAssembler.merge(renderedViews) + skillFiles
    }

    suspend fun gatherFullFiles(
        paths: List<String>,
        state: ClipboardSessionState
    ): Map<String, String>? {
        if (paths.isEmpty()) return emptyMap()

        log("Gathering ${paths.size} files (fresh read)...")
        notificationPort.showProgress(
            "Gathering ${paths.size} files...",
            0.4
        )

        return when (val result = contextProvider.gatherFiles(paths)) {
            is Result.Failure -> {
                log("ERROR: Failed to gather files: ${result.error.message}")
                null
            }

            is Result.Success -> {
                val gathered = result.value
                state.allGatheredFiles.putAll(gathered.files)
                log("Gathered ${gathered.files.size} files, total tracked: ${state.allGatheredFiles.size}")
                gathered.files
            }
        }
    }

    private fun log(message: String) {
        val policy = CodingAgentProviderPolicy.forProvider(provider)
        println("[MaxVibes ${policy.logTag}] $message")
        logger?.info(policy.logTag, message)
    }
}

internal typealias ClaudeCodeViewResolver = CodingAgentViewResolver
