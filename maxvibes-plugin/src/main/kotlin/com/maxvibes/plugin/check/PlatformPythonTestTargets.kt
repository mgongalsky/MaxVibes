package com.maxvibes.plugin.check

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.maxvibes.domain.model.check.TestTarget

/** Resolves Python package directories without linking to Java or Python plugin classes. */
internal class PlatformPythonTestTargets(private val project: Project) {
    fun resolve(target: TestTarget): List<Pair<String, PsiElement>>? {
        val name = when (target) {
            is TestTarget.TestPackage -> target.packageName
            is TestTarget.TestClass -> target.fqn
            is TestTarget.TestMethod -> target.classFqn
            else -> return null
        }
        val roots = roots()
        val relative = name.replace('.', '/')
        val directories = roots.mapNotNull { it.findFileByRelativePath(relative) }
            .filter { it.isDirectory && containsPython(it) }
            .distinctBy { it.path }
        if (target is TestTarget.TestPackage && directories.isNotEmpty()) {
            requirePythonSupport(target.description)
            if (!target.recursive) {
                throw UnsupportedTestTargetException(
                    "Non-recursive Python package scope '${target.description}' is not supported by the directory adapter. " +
                            "Specify individual .py test files, or use '$name.**' to include subpackages."
                )
            }
            val manager = PsiManager.getInstance(project)
            return directories.map { directory ->
                val psi = manager.findDirectory(directory)
                    ?: throw UnsupportedTestTargetException("Python directory PSI is unavailable for '${directory.path}'.")
                directory.path to psi
            }
        }

        // A Python module or class must never fall through to Java lookup in a mixed IDE.
        val segments = name.split('.')
        val pythonModule = (1..segments.size).any { count ->
            val modulePath = segments.take(count).joinToString("/") + ".py"
            roots.any { it.findFileByRelativePath(modulePath)?.isDirectory == false }
        }
        if (pythonModule || directories.isNotEmpty()) {
            throw UnsupportedTestTargetException(
                "Python symbol scope '${target.description}' is not supported by the directory adapter. " +
                        "Specify its .py test file or its containing package directory."
            )
        }
        return null
    }

    fun allTests(): List<Pair<String, PsiElement>> {
        val manager = PsiManager.getInstance(project)
        val candidates = ProjectRootManager.getInstance(project).contentRoots
            .filter { containsPython(it) }
        if (candidates.isEmpty()) return emptyList()
        requirePythonSupport("all tests")
        return candidates.filter { candidate ->
            candidates.none { other -> other != candidate && VfsUtilCore.isAncestor(other, candidate, true) }
        }.mapNotNull { root -> manager.findDirectory(root)?.let { root.path to it } }
    }

    private fun roots(): List<VirtualFile> {
        val manager = ProjectRootManager.getInstance(project)
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return (manager.contentSourceRoots.toList() + manager.contentRoots.toList() + listOfNotNull(base))
            .distinctBy { it.path }
    }

    private fun containsPython(directory: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        var found = false
        VfsUtilCore.iterateChildrenRecursively(
            directory,
            { file -> !index.isExcluded(file) && !file.name.startsWith(".") && file.name != "__pycache__" },
            { file ->
                if (!file.isDirectory && file.extension.equals("py", ignoreCase = true)) found = true
                !found
            }
        )
        return found
    }

    private fun requirePythonSupport(label: String) {
        if (Language.findLanguageByID("Python") == null) {
            throw UnsupportedTestTargetException(
                "Python test support is unavailable for '$label'. Enable the Python plugin and configure a project interpreter and test runner."
            )
        }
    }
}
