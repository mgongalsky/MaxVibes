package com.maxvibes.adapter.psi.python

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.jetbrains.python.psi.PyFile
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonCreateElementTest {
    @Test
    fun insertsMethodAsFirstChild() = checkMethodInsertion(
        InsertPosition.FIRST_CHILD, "class[Example]", listOf("created", "first", "last")
    )

    @Test
    fun insertsMethodAsLastChild() = checkMethodInsertion(
        InsertPosition.LAST_CHILD, "class[Example]", listOf("first", "last", "created")
    )

    @Test
    fun insertsMethodBeforeSibling() = checkMethodInsertion(
        InsertPosition.BEFORE, "class[Example]/function[last]", listOf("first", "created", "last")
    )

    @Test
    fun insertsMethodAfterSibling() = checkMethodInsertion(
        InsertPosition.AFTER, "class[Example]/function[first]", listOf("first", "created", "last")
    )

    @Test
    fun expandsInlineClassBodyWithCorrectIndentation() = withFixture { fixture ->
        val file =
            fixture.addFileToProject("inline.py", "class Example: pass\n\ndef outside():\n    return 7\n") as PyFile
        create(fixture, file, "class[Example]", ElementKind.FUNCTION, "def created(self):\n    return 42")
        val parsed = reparse(fixture, file)
        assertEquals(listOf("created"), parsed.topLevelClasses.single().methods.map { it.name })
        assertEquals(listOf("outside"), parsed.topLevelFunctions.map { it.name })
        assertTrue(parsed.topLevelFunctions.single().text.contains("return 7"))
    }

    @Test
    fun preservesDecoratorAndNestedBlockOfIndentedInput() = withFixture { fixture ->
        val file = fixture.addFileToProject("decorated.py", "class Example:\n    pass\n") as PyFile
        create(
            fixture, file, "class[Example]", ElementKind.FUNCTION,
            "    @staticmethod\n    def created(value):\n        if value:\n            return 42\n        return 0\n"
        )
        val parsed = reparse(fixture, file)
        val method = parsed.topLevelClasses.single().methods.single()
        assertEquals("created", method.name)
        assertTrue(assertNotNull(method.decoratorList).text.contains("@staticmethod"))
        assertEquals(2, method.statementList.statements.size)
        assertTrue(parsed.topLevelFunctions.isEmpty())
    }

    @Test
    fun insertsPropertyIntoClass() = withFixture { fixture ->
        val file = fixture.addFileToProject("property.py", "class Example:\n    pass\n") as PyFile
        create(fixture, file, "class[Example]", ElementKind.PROPERTY, "answer = 42")
        val parsed = reparse(fixture, file)
        assertEquals(listOf("answer"), parsed.topLevelClasses.single().classAttributes.map { it.name })
        assertEquals(1, parsed.statements.size)
    }

    @Test
    fun insertsClassIntoFile() = withFixture { fixture ->
        val file = fixture.addFileToProject("module.py", "value = 1\n") as PyFile
        create(fixture, file, "", ElementKind.CLASS, "class Created:\n    pass")
        val parsed = reparse(fixture, file)
        assertEquals(listOf("Created"), parsed.topLevelClasses.map { it.name })
        assertEquals(
            2,
            parsed.statements.size,
            "Resulting Python:\n${file.text}\nStatements: ${parsed.statements.map { it.javaClass.simpleName + ": " + it.text }}"
        )
    }

    @Test
    fun rejectsMismatchedKindWithoutChangingFile() = withFixture { fixture ->
        val file = fixture.addFileToProject("mismatch.py", "class Example:\n    pass\n") as PyFile
        reject(fixture, file, "class[Example]", ElementKind.CLASS, "def created(self):\n    return 42")
    }

    @Test
    fun rejectsMultipleDeclarationsAndInvalidSyntaxWithoutChangingFile() = withFixture { fixture ->
        val file = fixture.addFileToProject("invalid.py", "class Example:\n    pass\n") as PyFile
        reject(fixture, file, "class[Example]", ElementKind.FUNCTION, "def first():\n    pass\ndef second():\n    pass")
        reject(fixture, file, "class[Example]", ElementKind.FUNCTION, "def broken(:\n    pass")
        reject(fixture, file, "class[Example]", ElementKind.FUNCTION, "")
        reject(fixture, file, "class[Example]", ElementKind.INTERFACE, "class Created:\n    pass")
    }

    @Test
    fun rejectsNonContainerWithoutChangingFile() = withFixture { fixture ->
        val file = fixture.addFileToProject("target.py", "value = 1\n") as PyFile
        reject(fixture, file, "property[value]", ElementKind.FUNCTION, "def created():\n    pass")
    }

    private fun checkMethodInsertion(position: InsertPosition, target: String, expected: List<String>) =
        withFixture { fixture ->
            val file = fixture.addFileToProject(
                "methods.py",
                "class Example:\n    def first(self):\n        return 1\n\n    def last(self):\n        return 2\n\ndef outside():\n    return 7\n\nclass Neighbor:\n    pass\n"
            ) as PyFile
            create(fixture, file, target, ElementKind.FUNCTION, "def created(self):\n    return 42", position)
            val parsed = reparse(fixture, file)
            assertEquals(listOf("Example", "Neighbor"), parsed.topLevelClasses.map { it.name })
            val methods = parsed.topLevelClasses.first().methods
            assertEquals(expected, methods.map { it.name })
            assertTrue(methods.single { it.name == "first" }.text.contains("return 1"))
            assertTrue(methods.single { it.name == "last" }.text.contains("return 2"))
            assertTrue(methods.single { it.name == "created" }.text.contains("return 42"))
            assertEquals(listOf("outside"), parsed.topLevelFunctions.map { it.name })
            assertTrue(parsed.topLevelFunctions.single().text.contains("return 7"))
        }

    private fun create(
        fixture: CodeInsightTestFixture,
        file: PyFile,
        target: String,
        kind: ElementKind,
        content: String,
        position: InsertPosition = InsertPosition.LAST_CHILD
    ) {
        val result = apply(fixture, file, target, kind, content, position)
        assertTrue(result is ModificationResult.Success, "CREATE_ELEMENT failed: $result")
    }

    private fun reject(
        fixture: CodeInsightTestFixture,
        file: PyFile,
        target: String,
        kind: ElementKind,
        content: String
    ) {
        val original = file.text
        val result = apply(fixture, file, target, kind, content, InsertPosition.LAST_CHILD)
        assertTrue(result is ModificationResult.Failure, "Expected rejection: $result")
        assertEquals(original, file.text)
    }

    private fun apply(
        fixture: CodeInsightTestFixture,
        file: PyFile,
        target: String,
        kind: ElementKind,
        content: String,
        position: InsertPosition
    ): ModificationResult = runBlocking {
        val path = if (target.isEmpty()) ElementPath.file(file.virtualFile.path)
        else ElementPath("file:${file.virtualFile.path}/$target")
        PyCodeRepository(fixture.project).applyModification(Modification.CreateElement(path, kind, content, position))
    }

    private fun reparse(fixture: CodeInsightTestFixture, file: PyFile): PyFile {
        val parsed = PythonElementFactory(fixture.project).createFile(file.text)
        assertNull(PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java), file.text)
        return parsed
    }

    private fun withFixture(action: (CodeInsightTestFixture) -> Unit) {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val factory = IdeaTestFixtureFactory.getFixtureFactory()
            val projectFixture = factory.createLightFixtureBuilder("PythonCreateElementTest").fixture
            val fixture = factory.createCodeInsightFixture(projectFixture, TempDirTestFixtureImpl())
            fixture.setUp()
            try {
                action(fixture)
            } finally {
                fixture.tearDown()
            }
        }
    }

    @Test
    fun reproducesFiveReportedMethodCreations() = withFixture { fixture ->
        // Exact failed CREATE_ELEMENT payloads from MALDI_Studio PSI reports:
        // 20260922-143031, 20260928-145211, 20260928-151915,
        // 20261001-154837 and 20261002-131828.
        val cases = listOf(
            "Workspace" to "def unique_peak_counts(self, ids):\n    \"\"\"Count aligned peaks per sample, expanding derived selections only once.\"\"\"\n    selected = self.source_selected(ids)\n    dataset = self.dataset\n    counts = []\n    for sample in dataset.samples if dataset else ():\n        measurements = tuple(m for s, m in selected if s.id == sample.id)\n        if measurements:\n            subset = replace(sample, measurements=measurements)\n            counts.append((sample.name, len(average_measurement(subset).peaks)))\n    return counts\n",
            "MainWindow" to "def eventFilter(self, watched, event):\n    from PyQt6.QtCore import QEvent\n\n    if (watched is getattr(self, 'tree', None)\n            and event.type() == QEvent.Type.KeyPress\n            and event.modifiers() == Qt.KeyboardModifier.ShiftModifier\n            and event.key() in (Qt.Key.Key_Up, Qt.Key.Key_Down)):\n        self.step_sample(1 if event.key() == Qt.Key.Key_Down else -1)\n        event.accept()\n        return True\n    return super().eventFilter(watched, event)\n",
            "MainWindow" to "def select_chart_sample(self, sample_id):\n    from PyQt6.QtCore import QItemSelectionModel\n\n    dataset = self.workspace.dataset\n    if dataset is None:\n        return\n    sample = next((sample for sample in dataset.samples if sample.id == sample_id), None)\n    if sample is None:\n        return\n    measurement_ids = {measurement.id for measurement in sample.measurements}\n    if not measurement_ids:\n        return\n    for index in range(self.tree.topLevelItemCount()):\n        item = self.tree.topLevelItem(index)\n        if set(item.data(0, Qt.ItemDataRole.UserRole) or ()) == measurement_ids:\n            self.tree.setCurrentItem(\n                item, 0, QItemSelectionModel.SelectionFlag.ClearAndSelect\n                | QItemSelectionModel.SelectionFlag.Rows)\n            self.tree.scrollToItem(item)\n            self.view_tabs.setCurrentIndex(0)\n            self.tree.setFocus()\n            return\n",
            "Workspace" to "def import_workbook(self, path: str | Path, *, mode: str = 'add') -> Dataset:\n    \"\"\"Import atomically, keeping each added workbook's identities independent.\n\n    Return the imported dataset so callers report only its import warnings.\n    Existing load/accept remain explicit replacement operations.\n    \"\"\"\n    from uuid import uuid4\n\n    if mode not in {'add', 'override'}:\n        raise ValueError(f'Unknown import mode: {mode}')\n    imported = self.importer(path)\n    workbook_id = uuid4().hex\n    incoming = []\n    for sample in imported.samples:\n        sample_id = f'{workbook_id}:{sample.id}'\n        incoming.append(replace(\n            sample, id=sample_id,\n            measurements=tuple(replace(\n                measurement, id=f'{workbook_id}:{measurement.id}',\n                sample_id=sample_id) for measurement in sample.measurements),\n            workbook_id=workbook_id,\n            workbook_name=Path(imported.source).name or imported.name,\n            workbook_source=imported.source))\n    current = self.dataset if mode == 'add' else None\n    existing = ()\n    if current is not None:\n        # Datasets supplied through accept/load retain their public IDs.\n        legacy_id = uuid4().hex\n        existing = tuple(\n            sample if sample.workbook_id is not None else replace(\n                sample, workbook_id=legacy_id,\n                workbook_name=Path(current.source).name or current.name,\n                workbook_source=current.source)\n            for sample in current.samples)\n    samples = (*existing, *incoming)\n    workbook_count = len({sample.workbook_id for sample in samples})\n    combined = replace(\n        imported, samples=tuple(samples),\n        name=f'{workbook_count} workbooks' if workbook_count > 1 else imported.name,\n        warnings=(*(current.warnings if current is not None else ()), *imported.warnings))\n    self.accept(combined)\n    return imported",
            "Workspace" to "def apply_replicate_rule(self, rule, *, expected_dataset: Dataset) -> Dataset:\n    \"\"\"Commit the reviewed grouping only while its input is still active.\"\"\"\n    from domain.replicates import regroup_replicates\n\n    if self.dataset is not expected_dataset:\n        raise ValueError('The dataset changed. Reopen the replicate preview.')\n    result = regroup_replicates(expected_dataset, rule)\n    if result is not expected_dataset:\n        self.accept(result)\n    return result"
        )

        fun statementShape(element: com.intellij.psi.PsiElement): List<String> {
            val own =
                if (element is com.jetbrains.python.psi.PyStatement || element is com.jetbrains.python.psi.PyStatementList) {
                    listOf(element.javaClass.simpleName)
                } else emptyList()
            return own + element.children.flatMap { statementShape(it) } + if (own.isEmpty()) emptyList() else listOf("end")
        }
        cases.forEachIndexed { index, (className, content) ->
            val expected = PythonElementFactory(fixture.project).createFile(content).topLevelFunctions.single()
            val file = fixture.addFileToProject(
                "report_$index.py",
                "class $className:\n    def existing(self):\n        return 123\n\ndef outside():\n    return 456\n\nclass Neighbor:\n    pass\n"
            ) as PyFile
            create(fixture, file, "class[$className]", ElementKind.FUNCTION, content, InsertPosition.LAST_CHILD)
            val parsed = reparse(fixture, file)
            assertEquals(listOf(className, "Neighbor"), parsed.topLevelClasses.map { it.name }, file.text)
            val methods = parsed.topLevelClasses.first().methods
            assertEquals(listOf("existing", expected.name), methods.map { it.name }, file.text)
            val actual = methods.last()
            assertEquals(
                expected.text.filterNot { it.isWhitespace() },
                actual.text.filterNot { it.isWhitespace() },
                file.text
            )
            assertEquals(
                statementShape(expected),
                statementShape(actual),
                "Statement nesting changed for ${expected.name}:\n${file.text}"
            )
            assertEquals("def existing(self):\n        return 123", methods.first().text)
            assertEquals(listOf("outside"), parsed.topLevelFunctions.map { it.name })
            assertEquals("def outside():\n    return 456", parsed.topLevelFunctions.single().text)
        }
    }
}
