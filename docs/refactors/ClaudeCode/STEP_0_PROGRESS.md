# STEP_0 — Прогресс починки тестов

Обновлено: 2026-07-26. **ШАГ 0 ЗАВЕРШЁН** — полный `gradlew test --continue` зелёный по всем модулям.

## Итог

- ✅ maxvibes-application: 166/166 (было 32 падения).
- ✅ maxvibes-plugin: тесты проходят; краш «Gradle Test Executor N» был вызван осиротевшими `.class`-файлами с удалённым типом `PendingModsBlockView` (инкрементальная компиляция не подчистила) — вылечен `:maxvibes-plugin:clean`, правок кода не потребовалось.
- ✅ shared / domain / adapter-llm / adapter-psi: зелёные.
- ✅ Создан `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/testsupport/FakeChatPanelCallbacks.kt` (рекордящий фейк ChatPanelCallbacks).
- ↪ Общий фикстурный testsupport для application (§0.3 STEP_0_FixTests.md) перенесён в шаги 1–2: строить общие фейки до разбиения монстр-классов преждевременно — сигнатуры коллабораторов изменятся.

## Ключевые факты (важно для STEP_1/2)

- **Legacy `requestedFiles` → `codeViewRequests`**: конвертацию делает кодек `maxvibes-plugin/.../clipboard/JsonInteractionProtocolCodec.kt:210–229` (`requestedFiles → CodeViewRequest(path, FULL)`). `ClipboardInteractionService.processUnifiedResponse` читает ТОЛЬКО `codeViewRequests` — намеренная миграция, не баг. Тесты, стабящие `parseResponse`, обязаны строить `InteractionResponse(codeViewRequests = ...)`.
- **Модель `InteractionResponse`** — в `maxvibes-domain/.../model/interaction/ClipboardProtocol.kt` (файла InteractionResponse.kt нет).
- **Порядок в `handlePastedResponseInternal`**: restore(ensureWorkspace) → validate/parse → transition(ResponsePasted). При null от parseResponse — `ClipboardStepResult.ParseError` ДО transition.
- **`ensureWorkspace(sessionId)`**: рестор после перезапуска IDE; требует getSessionById → сессия с ≥1 USER-сообщением + успешный getProjectContext; иначе «Cannot restore session state for session X». Это контракт continueDialog/handlePastedResponse вместо старого «No active clipboard session».
- **Паттерн тестов**: реальные доменные data-классы (`ProjectContext`, `FileTree`, `FileNode`, `GatheredContext`) вместо строгих моков; рекордящие фейки вместо MockK для больших интерфейсов. Расхождения тестов с продакшеном обсуждаем с пользователем, не чиним молча.
- Сервис легитимно читает `chatSessionRepository.getSessionById` при generate/redo — verify(exactly=0) на него устарели.

## Грабли инфраструктуры (не забывать)

- Прогон тестов с разбором падений (одной командой, PowerShell 5.1, `;` вместо `&&`):
`.\gradlew.bat <module>:test --continue; Get-ChildItem -Recurse <module>\build\test-results -Filter '*.xml' | ForEach-Object { [xml]$r = Get-Content $_.FullName -Raw; foreach ($tc in $r.testsuite.testcase) { $f = $tc.failure; if (-not $f) { $f = $tc.error }; if ($f) { Write-Output ('FAIL ' + $tc.classname + '.' + $tc.name + ' :: ' + ($f.message -replace "`r?`n", ' ')) } } }`
- Полная причина краша executor'а — в InnerText failure-элемента JUnit XML (в выводе Gradle цепочка Caused by обрезается).
- При NoClassDefFoundError на несуществующий в исходниках класс — первым делом `clean` модуля (осиротевшие артефакты инкрементальной компиляции).
- Вывод терминала в плагине обрезается (~200 строк) — содержимое файлов читать через requestedViews.
- Несколько ELEMENT-вью по одному файлу за ход схлопываются — по одному или FULL.
- REPLACE_FILE в батче с commands может молчаливо не примениться — модификации файлов слать отдельным ходом от команд.

## Следующий шаг

STEP_1 — разбиение ChatMessageController (CommandTurnCoordinator, QuestionTurnCoordinator, ClaudeCodeDispatcher). См. STEP_1_ChatMessageController.md.
