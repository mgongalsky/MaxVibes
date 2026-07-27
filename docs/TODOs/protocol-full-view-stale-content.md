# Протокол: FULL-просмотр отдаёт устаревшее содержимое после модификаций

**Статус:** РЕШЁН — 2026-07-27
**Компонент:** `ClipboardInteractionService.gatherRequestedFiles` / `ClaudeCodeInteractionService.gatherRequestedFiles`

## Симптом (историческое)

После применения PSI-модификаций к файлу повторный запрос `FULL` по этому файлу возвращал содержимое ДО модификаций (байт-в-байт повторяемое, иногда старше HEAD). `SIGNATURES` по тому же файлу был актуален. Агент делал ложный вывод «модификации тихо не применились» и дублировал работу; худший сценарий — откат уже применённых правок через REPLACE_ELEMENT по устаревшему телу.

## Корень

В обоих `gatherRequestedFiles` была ветка дедупликации:

```kotlin
val newPaths = requestedPaths.filter { it !in state.allGatheredFiles }
if (newPaths.isEmpty()) {
return requestedPaths.associateWith { state.allGatheredFiles[it] ?: "" }
}
```

Повторно запрошенный путь отдавался из **сессионного кэша** `ClipboardSessionState.allGatheredFiles` (путь → содержимое) вместо перечитывания. Кэш живёт всю сессию и переживает рестарты через persistence — отсюда версии старше HEAD. `SIGNATURES`/`ELEMENT` шли через `codeRepository.getCodeView` (живой PSI) — поэтому были свежими.

Тот же код был причиной `context-gatherer-dedup-drops-requested-views.md`: при смеси новых и уже собранных путей возвращались только новые (`gathered.files`), старые молча выпадали из выдачи.

## Фикс

Оба `gatherRequestedFiles` теперь ВСЕГДА перечитывают все запрошенные пути через `contextProvider.gatherFiles(requestedPaths)`. `allGatheredFiles` сохранён только как учёт для `previouslyGatheredPaths` (ключи нужны `InteractionRequestBuilder` для фазы и previousPaths), его содержимое обновляется при каждой сборке.

Смежно: `PsiCodeRepository.applyModifications` теперь сбрасывает Document-буферы на диск после успешного батча (см. `psi-apply-disk-flush-race.md`), поэтому чтение через `VfsUtil.loadText` в `gatherFiles` видит применённые правки.
