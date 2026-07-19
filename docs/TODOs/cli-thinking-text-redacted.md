# CLI thinking text is server-redacted — live CoT cannot be restored client -side

**Дата фиксации : * * 2026 - 07 - 19.* * Статус:** внешнее ограничение, не баг MaxVibes.

## Симптом

Живой поток мыслей(«💭 Reasoning · live» в LiveTurnPanel) пуст: `thinking_delta` события приходят с `"thinking":""` и полем `estimated_tokens`; в авторитетных assistant - событиях thinking -блоки несут только зашифрованную `signature`.Раньше(
    CLI ~2.1.138, ~начало июля 2026) текст стримился .

## Цепочка доказательств (все эксперименты воспроизводимы)

1.Голый CLI * * 2.1.215 * * вне MaxVibes(
    `claude -p "ultrathink: ..." --output-format stream-json --verbose --include-partial-messages`
), дефолтный `~/.claude/settings.json` → дельты пустые.2.То же +`"showThinkingSummaries": true` в settings.json → пустые.3.Даунгрейд CLI до * *2.1.138 * * + откат плагина на `94bba2e` (до планнера) → в sandbox текста нет .
4.13 сохранённых сессий(
    MaxVibes + testVibes,
    CLI
    2.1
    .202 / 2.1
    .214,
    модели sonnet -5 / fable - 5 / opus - 4.8,
    effort auto / low / medium / high / xhigh
) → 0 непустых `thinking_delta`.5.claude.ai также перестал показывать reasoning(наблюдение пользователя).Вывод: thinking - текст отключён на стороне сервера Anthropic для доступных нам поверхностей . Не зависят: код плагина, версия CLI, модель, effort, env(
    `MAX_THINKING_TOKENS`,
    `CLAUDE_CODE_EFFORT_LEVEL`
), settings.json.

## Ложные следы (проверены, отброшены)

-Планнер - коммит `ae3b03c` — конвейер live - стриминга в нём не тронут(diff пуст).
-`CLAUDE_CODE_EFFORT_LEVEL` — текст отсутствует при любом значении, включая auto .
-Версия CLI — редактирование воспроизводится и на 2.1.138.

## Что сделано взамен

-Поле `reasoning` в JSON -протоколе ответа (промпты) → секция «💭 Reasoning» в пузыре .
-Live - экстрактор `LiveTurnPanel.extractJsonStringField` : стримит значения `message` / `reasoning` из частичного протокольного JSON в live -панель.
-Счётчик `💭 ~N tok` в хедере live - панели из `system/thinking_tokens`(событие `AgentStreamEvent.ThinkingProgress`); спам «system: thinking_tokens» из ленты убран.

## Когда пересматривать

        -Если в `thinking_delta` снова появится непустой текст(конвейер подхватит его автоматически — код не удалён).
-Если в CLI / docs появится официальный флаг раскрытия thinking для headless stream - json.
