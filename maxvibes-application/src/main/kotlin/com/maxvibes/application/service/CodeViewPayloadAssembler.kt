package com.maxvibes.application.service

import com.maxvibes.domain.model.code.CodeViewRequest

internal object CodeViewPayloadAssembler {

    fun merge(renderedViews: List<Pair<CodeViewRequest, String>>): Map<String, String> =
        renderedViews
            .groupBy(keySelector = { it.first.filePath }, valueTransform = { it })
            .mapValues { (_, views) ->
                if (views.size == 1) {
                    views.single().second
                } else {
                    views.joinToString("\n\n") { (request, content) ->
                        buildString {
                            append("// ===== VIEW: ")
                            append(request.granularity.name)
                            request.elementPath?.takeIf { it.isNotBlank() }?.let {
                                append(" ")
                                append(it)
                            }
                            append(" =====\n")
                            append(content)
                        }
                    }
                }
            }

    /** Keeps every requested path visible to the agent, including failed reads. */
    fun withMissingFileErrors(
        requestedPaths: List<String>,
        gatheredFiles: Map<String, String>
    ): Map<String, String> {
        if (requestedPaths.isEmpty()) return gatheredFiles
        val result = LinkedHashMap<String, String>()
        requestedPaths.distinct().forEach { path ->
            result[path] = gatheredFiles[path]
                ?: "// ERROR: Requested file was not found or could not be read: $path"
        }
        gatheredFiles.forEach { (path, content) -> result.putIfAbsent(path, content) }
        return result
    }
}
