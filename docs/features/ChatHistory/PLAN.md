# Feature: Add History Mode for Clipboard

## Проблема

В Clipboard-режиме мы жёстко экономим токены: `previouslyGatheredPaths` по умолчанию
**всегда пуст** — LLM в текущем чате получает только то, что напрямую нужно для
текущего сообщения. Это правильно для продолжения одного и того же чата.

Но если пользователь открывает **новый чат** в Claude/ChatGPT — LLM не знает,
какие файлы уже были загружены в рамках задачи. Нужна возможность одним кликом
сообщить ей об этом — без повторной загрузки содержимого (LLM запросит сама,
если понадобится).

## Решение

Добавить чекбокс **"Add History"** в панели ввода (только в Clipboard-режиме).

Когда **галка снята (по умолчанию)**:
- `previouslyGatheredPaths = []` — ЛЛМ не знает о прошлых файлах. Минимум токенов.
- Это оптимальный режим для продолжения **того же** LLM-чата.

Когда **галка стоит**:
- `previouslyGatheredPaths = allGatheredFiles.keys` — ЛЛМ видит, какие файлы
уже были в контексте задачи, и может сама запросить нужные через `requestedFiles`.
- Содержимое файлов **не дублируется** — только пути.
- Используется при старте **нового** LLM-чата, чтобы восстановить контекст.
- After send: галка автоматически сбрасывается (one-shot).

## Где лежат системные инструкции

- **Дефолт**: захардкожены в `PromptService.kt` (константы `DEFAULT_CHAT_SYSTEM`, `DEFAULT_PLANNING_SYSTEM`)
- **Кастомные**: `.maxvibes/prompts/chat-system.md` и `.maxvibes/prompts/planning-system.md`
- **Открыть для редактирования**: кнопка ⚙ в хедере ChatPanel

## Затронутые файлы

| Файл | Слой | Что меняем |
|------|------|------------|
| `ClipboardInteractionService.kt` | application | Добавить `addHistory: Boolean` в `startTask()`, `continueDialog()`, `generateAndCopyJson()` |
| `ChatMessageController.kt` | plugin/ui | Пробросить `addHistory` из `sendMessage()` → `dispatchClipboardMessage()` |
| `ChatPanel.kt` | plugin/ui | Добавить чекбокс `addHistoryCheckbox`, скрытый в non-Clipboard режимах |

## Шаги реализации

1. [STEP_1: Application Layer](STEP_1_ApplicationLayer.md) ✅ — логика addHistory в сервисе
2. [STEP_2: UI and Controller](STEP_2_UIAndController.md) — чекбокс и проброс параметра
3. [STEP_3: Smoke Test](STEP_3_SmokeTest.md) — ручная проверка и финальный коммит

## Архитектурные решения

- `addHistory` — параметр вызова, не сохраняемое состояние сессии. Правильно:
галка описывает конкретный *запрос*, а не режим всей сессии.
- Сервисный слой (`ClipboardInteractionService`) не знает о UI — только принимает bool флаг.
- UI-слой (`ChatPanel`) сбрасывает галку после каждой отправки — one-shot семантика.
- Мы не меняем `ClipboardRequest` / `ClipboardProtocol` — поле `previouslyGatheredPaths`
уже существует; просто управляем тем, что туда кладём.
- Содержимое файлов **никогда** не перекладывается через этот механизм.
LLM сама запросит нужные файлы через `requestedFiles`.
