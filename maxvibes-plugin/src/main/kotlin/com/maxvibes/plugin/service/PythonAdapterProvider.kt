package com.maxvibes.plugin.service

import com.intellij.openapi.project.Project
import com.maxvibes.adapter.psi.python.PyCodeRepository
import com.maxvibes.application.port.output.CodeRepository

/**
 * Constructs Python (PyCharm) PSI adapters.
 *
 * Isolation contract: this is the ONLY place in the plugin module that may
 * reference classes from maxvibes-adapter-psi-python — they link against the
 * Python plugin, which is an OPTIONAL dependency (plugin.xml). [MaxVibesService]
 * must touch this object only after confirming Python support is present via
 * `Language.findLanguageByID("Python")`; otherwise class loading fails with
 * NoClassDefFoundError on IDEs without the Python plugin (e.g. plain IntelliJ IDEA).
 */
object PythonAdapterProvider {

    /** Declared return type is the port on purpose — callers must not see the concrete class. */
    fun createCodeRepository(project: Project): CodeRepository = PyCodeRepository(project)
}
