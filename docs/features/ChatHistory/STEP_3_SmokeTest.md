# STEP 3: Smoke Test & Final Commit

## Контекст

После STEP 1 и STEP 2 фича полностью реализована. Этот шаг — финальная проверка
сценария end-to-end и документирование ожидаемого поведения.

## Сценарий 1: Обычный Clipboard-диалог (Add History OFF — дефолт)

> Убеждаемся что ничего не сломалось и токены не тратятся зря.

1. Clipboard режим, новый чат
2. Ввести задачу → Generate → JSON скопирован
3. В JSON: `files` = только globalContextFiles, `previouslyGatheredFiles` = **[]
(всегда пусто по умолчанию)**
4. Вставить mock-ответ с `requestedFiles: ["some/file.kt"]`
5. Ввести следующее сообщение (Add History OFF) → Generate
6. В JSON: `files` = {} (или только свежезапрошенные), `previouslyGatheredFiles` = **[]**
7. ✅ Поведение минимально по токенам — поле previouslyGatheredFiles всегда пусто

## Сценарий 2: Переход в новый LLM-чат (Add History ON)

> Сессия уже активна, файлы собраны. Открываем новый чат с Claude/ChatGPT.

1. Clipboard режим, сессия уже активна (есть собранные файлы)
2. Поставить галку **Add History**
3. Написать сообщение → Generate
4. Проверить JSON:
- `previouslyGatheredFiles` содержит пути ранее собранных файлов
- `files` пуст (содержимое файлов **не дублируется**)
- `chatHistory` присутствует с историей диалога
- `systemInstruction` присутствует
- `fileTree` присутствует
5. Галка автоматически снялась
6. LLM в новом чате видит список файлов и сама запрашивает нужные через requestedFiles
7. ✅ Bootstrap нового LLM-чата без повторной загрузки контента

## Сценарий 3: Режимы API и Cheap API

1. Переключиться в API режим
2. Убедиться: чекбокс Add History **не виден**
3. Переключиться в Cheap API
4. Убедиться: чекбокс Add History **не виден**
5. Вернуться в Clipboard → виден снова
6. ✅ Видимость корректна

## Сценарий 4: Waiting for paste

1. Clipboard, отправить сообщение → плагин ждёт вставки
2. Убедиться что `addHistoryCheckbox.isEnabled = false` в waiting state
3. ✅ Нет случайного включения во время ожидания

## Чеклист перед коммитом

- [ ] `./gradlew :maxvibes-application:compileKotlin` — OK
- [ ] `./gradlew :maxvibes-plugin:compileKotlin` — OK
- [ ] `./gradlew :maxvibes-application:test` — все тесты зелёные
- [ ] Ручной Сценарий 1 — `previouslyGatheredFiles` пуст по умолчанию ✓
- [ ] Ручной Сценарий 2 — Add History ON передаёт пути, не содержимое ✓
- [ ] Ручной Сценарий 3 — видимость чекбокса корректна ✓
- [ ] Ручной Сценарий 4 — чекбокс недоступен при ожидании ✓

## Итоговый коммит

```
feat(clipboard): "Add History" checkbox for bootstrapping new LLM chat

- Add addHistoryCheckbox in ChatPanel (Clipboard-only, auto-resets after send)
- Default (unchecked): previouslyGatheredFiles is always empty — minimum tokens
- When checked: previously gathered file paths sent so fresh LLM chat can
re-request them via requestedFiles (no content duplication)
- Propagated through: ChatPanel -> ChatMessageController -> ClipboardInteractionService
- No changes to ClipboardRequest domain model or codec
```

## Заметки для следующего агента

- Не нужно трогать `ClipboardProtocol.kt` — поле `previouslyGatheredPaths` в
`ClipboardRequest` уже существует. Мы просто управляем тем, что туда кладём.
- Не нужно трогать `JsonClipboardProtocolCodec.kt` — сериализация уже реализована.
- Внутренний параметр: `addHistory: Boolean`. Пользователь видит его как "Add History".
- **Содержимое файлов никогда не дублируется** через этот механизм — ЛЛМ сама
запросит нужное через `requestedFiles` в своём ответе.
- Системные инструкции берутся из `.maxvibes/prompts/chat-system.md` (кастомные)
или из констант `DEFAULT_CHAT_SYSTEM` / `DEFAULT_PLANNING_SYSTEM` в `PromptService.kt`.
