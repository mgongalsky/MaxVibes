package com.maxvibes.plugin.action

import com.intellij.psi.PsiFile

/**
 * Language gate for editor actions.
 *
 * Deliberately free of references to language-plugin PSI classes (KtFile, PyFile):
 * the Kotlin and Python plugins are OPTIONAL dependencies (plugin.xml), so those
 * classes may be absent at runtime — a direct `is KtFile` check inside
 * `AnAction.update()` crashes with NoClassDefFoundError on IDEs without the
 * Kotlin plugin (e.g. PyCharm). Comparing platform-level language IDs is
 * classloading-safe on any IDE.
 *
 * The IDs match the adapter dispatch in `MaxVibesService.createCodeRepository()`:
 * "kotlin" (KotlinLanguage) and "Python" (PythonLanguage). Known mixed-IDE
 * limitation: on an IDE where both languages are present, a file of the
 * non-dispatched language passes this gate and fails gracefully downstream
 * (documented in docs/TODOs/pycharm-platform-safety-and-dispatch.md).
 */
internal object SupportedLanguages {

    private val supportedIds = setOf("kotlin", "Python")

    fun isSupported(file: PsiFile?): Boolean =
        file != null && file.language.id in supportedIds
}
