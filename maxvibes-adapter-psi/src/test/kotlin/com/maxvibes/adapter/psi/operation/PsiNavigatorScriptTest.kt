package com.maxvibes.adapter.psi.operation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

class PsiNavigatorScriptTest : BasePlatformTestCase() {
    fun testScriptExposesTopLevelDeclarations() {
        val file = myFixture.configureByText(
            "build.gradle.kts",
            "val outputDir = \"out\"\nfun registerImagePackingTask() {}\nprintln(outputDir)"
        ) as KtFile
        val script = file.script
        assertNotNull(script)
        val navigator = PsiNavigator(project)
        val children = navigator.getChildren(file)
        assertEquals(
            listOf("outputDir", "registerImagePackingTask"),
            children.map { (it as KtNamedDeclaration).name }
        )
        assertEquals(children, navigator.getChildren(script!!))
        assertTrue(children[1] is KtNamedFunction)
    }

    fun testScriptDoesNotExposeLocalDeclarationsAsTopLevel() {
        val file = myFixture.configureByText(
            "nested.kts",
            "fun outer() { fun local() {} }\nrun { val hidden = 1 }"
        ) as KtFile
        val children = PsiNavigator(project).getChildren(file)
        assertEquals(listOf("outer"), children.map { (it as KtNamedDeclaration).name })
    }

    fun testRegularKotlinFileStillExposesDeclarations() {
        val file = myFixture.configureByText(
            "Regular.kt",
            "class Example { fun member() {} }\nfun topLevel() {}"
        ) as KtFile
        assertNull(file.script)
        val children = PsiNavigator(project).getChildren(file)
        assertEquals(listOf("Example", "topLevel"), children.map { (it as KtNamedDeclaration).name })
        assertEquals(
            listOf("member"),
            PsiNavigator(project).getChildren(children[0]).map { (it as KtNamedDeclaration).name }
        )
    }
}
