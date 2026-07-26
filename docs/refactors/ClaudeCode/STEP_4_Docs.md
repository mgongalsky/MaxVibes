# STEP 4 — Документация и мелочи

## 4.1 Обновить docs / ARCHITETURE.md

Документ отстал на несколько фич.Добавить / исправить:

-* * Подсистема Claude Code * *(главный режим !): `ClaudeCodePort` → `ClaudeCodeProcessAdapter` → `StreamJsonEventParser`, `AgentStreamHub` / `AgentStreamSink`, `LiveTurnPanel`, session log, state machine (IDLE → SESSION_ACTIVE → AWAITING_APPROVE) с pending -модификациями.
-* * Commands - подсистема * *: `CommandRunnerPort`, `ProcessCommandRunner`, `CommandExecutionService`, command - turn поток .
-* * Planner * *: `TaskPlan`, `PlanPanel`, `PlanDiagram` + `DiagramViewerDialog` / `MermaidDiagramRenderer`.
-* * Granularity / CodeView * *: `CodeGranularity`, renderer'ы, `USAGES`/`CALLERS`.
-* * Skills / specific prompts * * : `SpecificPromptService`, `SkillMdParser`, каталог skills .
-* * PyCharm - адаптер * *: `maxvibes-adapter-psi-python`, runtime - dispatch в `MaxVibesService.createCodeRepository`.
-Дерево domain -моделей: добавить `interaction/`, `planning/`, `command/`, `context/`.
-Актуализировать LOC -заметки(« ChatPanel ~515 LOC ✅» — неправда) и матрицу тестов; ссылка на этот план рефакторинга.
-Новые классы из STEP_1 – STEP_3 вносить в документ по мере их появления.

## 4.2 Мелкая гигиена

-`MaxVibesService.init`: убрать отладочные `println`(3 шт .), оставить `MaxVibesLogger` / `LOG` .
-KDoc - ссылки на « conscious duplication» в обоих interaction - сервисах — удалить после STEP_2(дублей больше нет).
-Опечатка в имени файла `ARCHITETURE.md`(→ `ARCHITECTURE.md`) — переименовывать только вместе с обновлением всех ссылок в docs /; отдельным коммитом .

## Definition of Done

-[] ARCHITETURE . md отражает фактическую структуру всех модулей и подсистем.
-[] В `MaxVibesService` нет `println`.
-[] Матрица тестов в доке соответствует реальному набору .
