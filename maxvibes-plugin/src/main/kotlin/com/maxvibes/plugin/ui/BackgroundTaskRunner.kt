package com.maxvibes.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

/**
 * Boundary for running suspend work in an IntelliJ background task and returning
 * its result to the EDT.
 */
internal interface BackgroundTaskRunner {
    fun <T> run(
        title: String,
        cancellable: Boolean,
        publishIndicator: Boolean = true,
        action: suspend () -> T,
        onSuccess: (T) -> Unit,
        onCancel: () -> Unit = {},
        onError: (Throwable) -> Unit = { onCancel() }
    )
}

/** IntelliJ implementation of [BackgroundTaskRunner]. */
internal class IntellijBackgroundTaskRunner(
    private val project: Project,
    private val progressIndicatorSink: (ProgressIndicator) -> Unit
) : BackgroundTaskRunner {

    override fun <T> run(
        title: String,
        cancellable: Boolean,
        publishIndicator: Boolean,
        action: suspend () -> T,
        onSuccess: (T) -> Unit,
        onCancel: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, cancellable) {
                override fun run(indicator: ProgressIndicator) {
                    if (publishIndicator) progressIndicatorSink(indicator)
                    val result = runBlocking { action() }
                    ApplicationManager.getApplication().invokeLater {
                        onSuccess(result)
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater(onCancel)
                }

                // Без этой ветки исключение из action() гасит задачу молча: onSuccess
                // не вызывается, и ввод остаётся заблокированным до перезапуска IDE.
                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater { onError(error) }
                }
            }
        )
    }
}
