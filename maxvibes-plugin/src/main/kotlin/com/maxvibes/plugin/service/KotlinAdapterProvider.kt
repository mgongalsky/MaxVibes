package com.maxvibes.plugin.service

import com.intellij.openapi.project.Project
import com.maxvibes.adapter.psi.PsiCodeRepository
import com.maxvibes.application.port.output.CodeRepository

/**
 * Constructs Kotlin/IDEA PSI adapters.
 *
 * Isolation contract: this is the ONLY place in the plugin module that may
 * reference classes from maxvibes-adapter-psi — they link against the Kotlin
 * plugin, which is an OPTIONAL dependency (plugin.xml). [MaxVibesService] must
 * touch this object only after confirming Kotlin support is present via
 * `Language.findLanguageByID("kotlin")`; otherwise class loading fails with
 * NoClassDefFoundError on IDEs without the Kotlin plugin (e.g. PyCharm).
 */
object KotlinAdapterProvider {

    /** Declared return type is the port on purpose — callers must not see the concrete class. */
    fun createCodeRepository(project: Project): CodeRepository = PsiCodeRepository(project)
}
