package com.maxvibes.plugin.ui

import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.shared.result.Result

/** Collects IDE errors in background and attaches their LLM representation. */
internal class IdeErrorsAttachmentLoader(
    private val ideErrorsPort: IdeErrorsPort,
    private val backgroundTaskRunner: BackgroundTaskRunner,
    private val attachments: AttachmentCoordinator,
    private val inputStatusView: InputStatusView
) {
    fun fetch() {
        inputStatusView.setStatus("Fetching IDE errors...")
        backgroundTaskRunner.run(
            title = "Fetching IDE errors",
            cancellable = false,
            publishIndicator = false,
            action = { ideErrorsPort.getCompilerErrors() },
            onSuccess = { result ->
                when (result) {
                    is Result.Success -> attach(result.value)
                    is Result.Failure -> inputStatusView.onError(
                        "Failed to fetch IDE errors: ${result.error}"
                    )
                }
            }
        )
    }

    private fun attach(errors: List<com.maxvibes.domain.model.code.IdeError>) {
        if (errors.isEmpty()) {
            inputStatusView.setStatus("No IDE errors found in open files")
            return
        }

        attachments.attachErrors(
            errors.joinToString(separator = System.lineSeparator()) {
                it.formatForLlm()
            }
        )
        inputStatusView.setStatus("Attached ${errors.size} IDE errors")
    }
}
