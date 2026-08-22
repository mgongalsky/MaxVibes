package com.maxvibes.plugin.check

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckIssue
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckProgress
import com.maxvibes.domain.model.check.CheckProgressSink
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import com.maxvibes.domain.model.check.TestScope
import com.maxvibes.domain.model.check.TestScopeParser
import com.maxvibes.domain.model.check.TestTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import com.maxvibes.domain.model.check.TestFailureText
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key

/**
 * Прогон тестов по кругу, который назвал агент.
 *
 * Конфигурацию не ищем среди сохранённых и не пишем руками: цель резолвится в
 * PSI-элемент, а конфигурацию под него делает штатный RunConfigurationProducer —
 * ровно как при Run по правому клику. Поэтому работает любой установленный
 * фреймворк (JUnit, Gradle, TestNG), и при этом плагину не нужны их API на
 * компиляции. Побочный эффект важнее: запустить постороннюю конфигурацию вроде
 * "Run Plugin" здесь физически нечем.
 *
 * Второй принцип — не молчать. Конец прогона определяется смертью процесса, а не
 * только событием onTestingFinished: если тестовый фреймворк не сказал ни слова,
 * проверка вернёт ошибку с exit code, а не тишину до таймаута.
 */
class ScopedTestCheckRunner(private val project: Project) : CheckRunnerPort {

    override fun supports(kind: CheckKind): Boolean = kind == CheckKind.TESTS

    override suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink,
        cancellation: CheckCancellation
    ): CheckExecution {
        val startedAt = System.currentTimeMillis()
        val scope = TestScopeParser.parse(request.scope)

        val unknown = scope.unknownTargets
        if (unknown.isNotEmpty()) {
            return CheckExecution(
                request = request,
                status = CheckStatus.ERROR,
                durationMs = System.currentTimeMillis() - startedAt,
                rawOutput = "Cannot interpret test scope: " + unknown.joinToString(", ") { it.raw } + ".\n" + SCOPE_HELP
            )
        }

        progress.publish(CheckProgress("Resolving ${scope.description}"))
        val problems = mutableListOf<String>()
        val targets = ReadAction.compute<List<ResolvedTarget>, RuntimeException> { resolveTargets(scope, problems) }
        if (targets.isEmpty()) {
            return CheckExecution(
                request = request,
                status = CheckStatus.ERROR,
                durationMs = System.currentTimeMillis() - startedAt,
                rawOutput = "Nothing to run for scope '${scope.description}'.\n" +
                        problems.joinToString("\n").ifBlank { SCOPE_HELP }
            )
        }

        val state = RunState()
        val activeHandler = AtomicReference<ProcessHandler?>()
        // Делегированная в Gradle тестовая задача умирает на компиляции ещё до
        // старта процесса: текста ловить неоткуда, и единственным источником
        // ошибки остаётся поток событий вкладки Build.
        val buildOutput = BuildOutputCollector(project)
        buildOutput.start()
        val completed = try {
            withTimeoutOrNull(request.timeoutSec * 1000L) {
                for (target in targets) {
                    if (cancellation.isCancelled || state.cancelled) break
                    runOne(target, progress, cancellation, state, activeHandler)
                }
                true
            }
        } finally {
            buildOutput.stop()
        }
        if (completed == null) {
            // Оставить процесс жить после таймаута значит отдать пользователю IDE,
            // в которой продолжают крутиться чужие тесты.
            activeHandler.get()?.destroyProcess()
        }

        val total = maxOf(state.total, state.completed)
        // Сессия открылась и закрылась, не выполнив ни одного теста. Успехом это
        // называть нельзя: агент, получив зелёное, сочтёт код проверенным и
        // пойдёт дальше по сломанному месту. Одного sawTests тут мало — его
        // поднимает уже старт сессии, а не первый тест.
        val nothingRan = completed != null && !state.cancelled && total == 0

        val status = when {
            state.cancelled -> CheckStatus.CANCELLED
            completed == null -> CheckStatus.TIMEOUT
            state.failures.isNotEmpty() -> CheckStatus.FAILED
            !state.sawTests -> CheckStatus.ERROR
            nothingRan -> CheckStatus.ERROR
            state.errors.isNotEmpty() -> CheckStatus.ERROR
            else -> CheckStatus.PASSED
        }
        // Ошибка компиляции тестового модуля — это и есть причина пустого
        // прогона, поэтому она идёт в issues наравне с падениями тестов.
        val buildIssues = if (nothingRan || state.errors.isNotEmpty()) buildOutput.issues() else emptyList()
        return CheckExecution(
            request = request,
            status = status,
            issues = state.failures + buildIssues,
            testsTotal = total.takeIf { state.sawTests },
            testsFailed = state.failed.takeIf { state.sawTests },
            durationMs = System.currentTimeMillis() - startedAt,
            rawOutput = buildReport(scope, targets, problems, state, nothingRan, buildOutput)
        )
    }

    /** Один запуск одной конфигурации; результаты копятся в общем [state]. */
    private suspend fun runOne(
        target: ResolvedTarget,
        progress: CheckProgressSink,
        cancellation: CheckCancellation,
        state: RunState,
        activeHandler: AtomicReference<ProcessHandler?>
    ): Unit = suspendCancellableCoroutine { continuation ->
        val connection = project.messageBus.connect()
        val done = AtomicBoolean(false)

        fun finish(error: String?) {
            if (!done.compareAndSet(false, true)) return
            connection.disconnect()
            if (error != null) state.errors += error
            if (continuation.isActive) continuation.resume(Unit)
        }

        val environment: ExecutionEnvironment = try {
            ExecutionEnvironmentBuilder
                .create(DefaultRunExecutor.getRunExecutorInstance(), target.settings)
                .build()
        } catch (e: Exception) {
            connection.disconnect()
            state.errors += "Cannot prepare ${target.label}: ${e.message ?: e::class.simpleName}"
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        environment.assignNewExecutionId()
        val executionId = environment.executionId

        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env.executionId != executionId) return
                activeHandler.set(handler)
                state.ownHandler = handler
                // Без этого весь текст процесса пропадает, и упавшая компиляция
                // выглядит для агента как «тест не найден».
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        state.appendOutput(event.text)
                    }
                })
                if (cancellation.isCancelled) handler.destroyProcess()
                progress.publish(CheckProgress("Running ${target.label}", state.completed, null, state.failed))
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (env.executionId != executionId) return
                finish("${target.label} did not start")
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                if (env.executionId != executionId) return
                // Смерть процесса — единственный надёжный конец. Без этой ветки
                // молчащий фреймворк вешал проверку до самого таймаута.
                finish(
                    if (state.sawTests) null
                    else "${target.label} finished without running a single test (exit code $exitCode). " +
                            "A non-zero exit code here usually means the test module failed to compile, " +
                            "not that the test is missing — see the output below."
                )
            }
        })

        connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
                if (!state.owns(testsRoot)) return
                state.sawTests = true
                progress.publish(CheckProgress("Running ${target.label}", state.completed, null, state.failed))
            }

            override fun onTestsCountInSuite(count: Int) {
                state.total += count
            }

            override fun onTestStarted(test: SMTestProxy) {
                state.sawTests = true
                progress.publish(
                    CheckProgress(displayName(test), state.completed, state.total.takeIf { it > 0 }, state.failed)
                )
            }

            override fun onTestFinished(test: SMTestProxy) {
                state.completed++
                progress.publish(
                    CheckProgress(displayName(test), state.completed, state.total.takeIf { it > 0 }, state.failed)
                )
            }

            override fun onTestFailed(test: SMTestProxy) {
                state.failed++
                state.failures += toIssue(test)
                progress.publish(
                    CheckProgress(
                        "FAILED " + displayName(test),
                        state.completed,
                        state.total.takeIf { it > 0 },
                        state.failed
                    )
                )
            }

            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                if (!state.owns(testsRoot)) return
                finish(null)
            }
        })

        cancellation.onCancel {
            state.cancelled = true
            activeHandler.get()?.destroyProcess()
            finish(null)
        }
        continuation.invokeOnCancellation { connection.disconnect() }

        ApplicationManager.getApplication().invokeLater {
            try {
                ProgramRunnerUtil.executeConfiguration(environment, false, false)
            } catch (e: Throwable) {
                finish("Cannot launch ${target.label}: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun resolveTargets(scope: TestScope, problems: MutableList<String>): List<ResolvedTarget> =
        scope.targets
            .flatMap { resolveElements(it, problems) }
            .mapNotNull { (label, element) -> configurationFor(label, element, problems) }

    private fun resolveElements(
        target: TestTarget,
        problems: MutableList<String>
    ): List<Pair<String, PsiElement>> = when (target) {
        is TestTarget.AllTests -> testSourceDirectories()
        is TestTarget.TestFile -> resolveFile(target.path, problems)
        is TestTarget.TestClass -> single(target.fqn, JavaTargets.findClass(project, target.fqn), problems)
        is TestTarget.TestMethod -> single(
            target.description,
            JavaTargets.findMethod(project, target.classFqn, target.methodName),
            problems
        )

        is TestTarget.TestPackage -> single(
            target.packageName,
            JavaTargets.findPackage(project, target.packageName),
            problems
        )

        is TestTarget.Unknown -> emptyList()
    }

    private fun single(
        label: String,
        element: PsiElement?,
        problems: MutableList<String>
    ): List<Pair<String, PsiElement>> {
        if (element == null) {
            problems += "Not found in project: $label. $MODEL_HINT"
            return emptyList()
        }
        return listOf(label to element)
    }

    private fun resolveFile(path: String, problems: MutableList<String>): List<Pair<String, PsiElement>> {
        val base = project.basePath
        val file = LocalFileSystem.getInstance().findFileByPath(if (base == null) path else "$base/$path")
            ?: LocalFileSystem.getInstance().findFileByPath(path)
        val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) }
        if (psiFile == null) {
            problems += "File not found: $path"
            return emptyList()
        }
        return listOf(path.substringAfterLast('/') to psiFile)
    }

    /**
     * Тестовые корни всех модулей. Каталоги ресурсов отсеиваются по имени:
     * platform-индекс считает их тестовыми источниками, а тестов там нет.
     */
    private fun testSourceDirectories(): List<Pair<String, PsiElement>> {
        val index = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        return ProjectRootManager.getInstance(project).contentSourceRoots
            .filter { index.isInTestSourceContent(it) && it.name != "resources" }
            .mapNotNull { root -> psiManager.findDirectory(root)?.let { root.path to it } }
    }

    private fun configurationFor(
        label: String,
        element: PsiElement,
        problems: MutableList<String>
    ): ResolvedTarget? {
        val context = ConfigurationContext(element)
        val settings: RunnerAndConfigurationSettings? =
            context.configurationsFromContext?.firstOrNull()?.configurationSettings ?: context.configuration
        if (settings == null) {
            problems += "No test runner in this IDE can run $label"
            return null
        }
        return ResolvedTarget(label, settings)
    }

    private fun buildReport(
        scope: TestScope,
        targets: List<ResolvedTarget>,
        problems: List<String>,
        state: RunState,
        nothingRan: Boolean,
        buildOutput: BuildOutputCollector
    ): String = buildString {
        appendLine("Scope: ${scope.description}")
        appendLine("Launched: " + targets.joinToString(", ") { it.settings.name })
        // Счётчик тестов печатает заголовок проверки — второй раз он тут не нужен.
        if (nothingRan) {
            appendLine(
                "! Not a single test ran for '${scope.description}': the run started and finished empty, " +
                        "so either the scope matches no tests or the module failed to compile. " + SCOPE_HELP
            )
        }
        problems.forEach { appendLine("! $it") }
        state.errors.forEach { appendLine("! $it") }
        // Без этого хвоста агент видит только «0 tests» и начинает чинить
        // обнаружение тестов вместо ошибки компиляции, которая всё и убила.
        // Вывод процесса приоритетнее: он относится к самому прогону, тогда как
        // вывод сборки остаётся единственным источником, когда процесс не дожил
        // до старта.
        if (nothingRan || state.errors.isNotEmpty()) {
            val processTail = state.outputTail()
            val buildTail = buildOutput.outputTail()
            when {
                processTail.isNotEmpty() -> {
                    appendLine("--- Process output (tail) ---")
                    appendLine(processTail)
                }

                buildTail.isNotEmpty() -> {
                    appendLine("--- Build output (tail) ---")
                    appendLine(buildTail)
                }
            }
        }
    }.trim()

    private fun displayName(test: SMTestProxy): String =
        listOfNotNull(test.parent?.name?.takeIf { it.isNotBlank() }, test.name).joinToString(".")

    private fun toIssue(test: SMTestProxy): CheckIssue {
        // Сравнение живёт не в сообщении, а в провайдере диффа: IDE вынимает его
        // из исключения, чтобы показать диалог, и в errorMessage остаётся только
        // имя класса исключения.
        val comparison = test.diffViewerProvider
        return CheckIssue(
            message = TestFailureText.message(test.errorMessage, comparison?.left, comparison?.right),
            testName = displayName(test),
            details = TestFailureText.relevantFrames(test.stacktrace)
        )
    }

    private class ResolvedTarget(val label: String, val settings: RunnerAndConfigurationSettings)

    /** Состояние одного прогона проверки, общее для всех её конфигураций. */
    private class RunState {
        val failures = mutableListOf<CheckIssue>()
        val errors = mutableListOf<String>()

        private val output = StringBuilder()

        @Volatile
        var ownHandler: ProcessHandler? = null

        @Volatile
        var completed = 0

        @Volatile
        var failed = 0

        @Volatile
        var total = 0

        @Volatile
        var sawTests = false

        @Volatile
        var cancelled = false

        /**
         * Отсекает события чужого прогона: пользователь мог запустить свои тесты
         * руками, и их падения не должны попасть в наш отчёт.
         */
        fun owns(root: SMTestProxy.SMRootTestProxy): Boolean {
            val ours = ownHandler ?: return true
            return root.handler === ours
        }

        /** Текст приходит с потоков процесса, поэтому под замком. */
        fun appendOutput(text: String) {
            synchronized(output) {
                output.append(text)
                if (output.length > MAX_CAPTURED_OUTPUT_CHARS) {
                    output.delete(0, output.length - MAX_CAPTURED_OUTPUT_CHARS)
                }
            }
        }

        /** Именно хвост: компилятор печатает ошибки в конце, начало выбросить не жалко. */
        fun outputTail(): String = synchronized(output) {
            val text = output.toString().trim()
            if (text.length <= MAX_REPORTED_OUTPUT_CHARS) text
            else text.substring(text.length - MAX_REPORTED_OUTPUT_CHARS)
        }
    }

    /**
     * Цели, для которых нужен Java-PSI. Вынесены в отдельный класс намеренно:
     * загрузка откладывается до первой цели-класса или цели-пакета, поэтому в IDE
     * без Java-поддержки раннер остаётся рабочим для файлов и тестовых корней.
     */
    private object JavaTargets {

        fun findClass(project: Project, fqn: String): PsiElement? {
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqn, scope)?.let { return it }
            // Агент почти всегда называет тест коротким именем: для человека это
            // однозначно, а findClass по FQN не находит ничего. Сужаем результат до
            // точного совпадения хвоста, иначе GreetTest подхватил бы MyGreetTest.
            return com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                .getClassesByName(fqn.substringAfterLast('.'), scope)
                .firstOrNull { it.qualifiedName == fqn || it.qualifiedName?.endsWith(".$fqn") == true }
        }

        fun findMethod(project: Project, classFqn: String, methodName: String): PsiElement? =
            (findClass(project, classFqn) as? PsiClass)
                ?.findMethodsByName(methodName, true)
                ?.firstOrNull()

        fun findPackage(project: Project, name: String): PsiElement? =
            com.intellij.psi.JavaPsiFacade.getInstance(project).findPackage(name)
    }

    private companion object {
        const val SCOPE_HELP: String =
            "Expected: a class (com.foo.BarTest), a method (com.foo.BarTest#name), " +
                    "a package (com.foo.bar or com.foo.bar.**), a file path, " +
                    "several of those separated by commas, or 'all'."

        /**
         * Отличает опечатку в scope от устаревшей модели проекта: без этой подсказки
         * агент видит «не найдено» и повторяет ровно тот же запуск.
         */
        const val MODEL_HINT: String =
            "If the file exists on disk, the IDE project model may be stale — reload the Gradle/Maven project first."

        /** Вывод тестовой задачи Gradle измеряется мегабайтами — держим только хвост. */
        const val MAX_CAPTURED_OUTPUT_CHARS: Int = 200_000

        /** В отчёт уходит куда меньше: ошибки компилятора печатаются в самом конце. */
        const val MAX_REPORTED_OUTPUT_CHARS: Int = 4_000
    }
}
