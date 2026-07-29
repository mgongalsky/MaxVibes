package com.maxvibes.plugin.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.maxvibes.plugin.service.MaxVibesLogger

/** Flushes unsaved editor documents before project files are read by a turn. */
internal fun interface DocumentSaver {
    fun saveAllDocuments()
}

internal class IntellijDocumentSaver : DocumentSaver {
    override fun saveAllDocuments() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
        } catch (e: Exception) {
            MaxVibesLogger.warn(
                "DocumentSaver",
                "saveAllDocuments failed",
                data = mapOf("msg" to (e.message ?: "?"))
            )
        }
    }
}
