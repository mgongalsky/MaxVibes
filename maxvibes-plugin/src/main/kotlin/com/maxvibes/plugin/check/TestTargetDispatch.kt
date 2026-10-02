package com.maxvibes.plugin.check

/** A missing language adapter is different from a misspelled target. */
internal class UnsupportedTestTargetException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Platform targets take precedence even when Java support is also installed. */
internal object TestTargetDispatch {
    fun <T> resolve(
        label: String,
        platformTargets: () -> List<T>?,
        javaAvailable: () -> Boolean,
        javaTargets: () -> List<T>
    ): List<T> {
        platformTargets()?.let { return it }
        if (!javaAvailable()) {
            throw UnsupportedTestTargetException(
                "No compatible test target adapter for '$label'. Java PSI is unavailable in this IDE. " +
                        "For Python tests, use a package directory or a .py file with Python test runner support enabled."
            )
        }
        return try {
            javaTargets()
        } catch (e: LinkageError) {
            throw UnsupportedTestTargetException(
                "Java test target support could not be loaded for '$label': ${e.message}", e
            )
        }
    }

    fun javaPsiAvailable(): Boolean = try {
        val loader = TestTargetDispatch::class.java.classLoader
        listOf(
            "com.intellij.psi.JavaPsiFacade",
            "com.intellij.psi.PsiClass",
            "com.intellij.psi.search.PsiShortNamesCache"
        ).forEach { Class.forName(it, false, loader) }
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
