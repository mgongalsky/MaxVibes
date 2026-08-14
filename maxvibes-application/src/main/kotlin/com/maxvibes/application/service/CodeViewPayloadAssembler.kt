package com.maxvibes.application.service

import com.maxvibes.domain.model.code.CodeViewRequest

/** Combines rendered code views without losing multiple requests for the same file. */
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
}
