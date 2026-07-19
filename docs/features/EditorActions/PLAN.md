# EditorActions v2 — скиллы из редактора

## Концепция (v2, после обсуждения с Максимом)
Хардкодный каталог рецептов отменён — он был бы вторым промпт-механизмом рядом со скиллами. Вместо этого точка входа в редакторе вызывает ОБЫЧНЫЕ скиллы (.claude/skills/*/SKILL.md), расширенные тремя ключами фронтматтера. Пользователь настраивает всё правкой файла скилла; пребилд-набор копируется в проект installer-ом и дальше живёт как обычные файлы.

Клик правой кнопкой на элементе → подменю из скиллов, применимых к этому виду элемента → выбранный скилл: (а) армируется one-shot на следующий send, (б) префилит ввод чата своим editor-template с подставленным путём элемента, (в) при attach-element: true сразу прикрепляет тело элемента — модель не тратит round-trip на requestedViews. Send жмёт пользователь.

## Фронтматтер SKILL.md — новые ключи
- `applies-to:` — список видов элементов через запятую: function, property, class, interface, object, companion_object, enum_entry, any. Ключа нет → скилл в меню редактора не показывается (остаётся чисто чатовым). Неизвестные ключи парсер игнорирует — старые скиллы не ломаются.
- `attach-element:` true|false (дефолт false) — прикрепить тело элемента one-shot вложением.
- `editor-template: |` — блочный скаляр (строки с отступом 2 пробела); текст префила с плейсхолдерами {{elementPath}}, {{elementName}}, {{filePath}}. Нет ключа → дефолт "Apply the '<name>' skill to {{elementPath}}."

Пример:
```
---
name: feathers-extract-override
description: Prepare a function for testing via Extract and Override
applies-to: function
attach-element: true
editor-template: |
  Prepare {{elementPath}} for testing via Extract and Override (Feathers).
---
<тело скилла = методология, едет как specificPrompt>
```

## Зафиксированные решения
- Префилл, НЕ автосенд. Текст префила настраивается через editor-template — не хардкодится.
- One-shot на один send (решение Максима): клик армирует скилл ровно на следующий send; чип «⚡ Skill: <name> (1×)» в панели вложений с ✕ для отмены; дропдаун скилла сессии не трогается. pendingOneShot живёт в контроллере — сигнатура ChatPanel.sendMessage не меняется.
- Хоткеи — потом. Пребилды и шаблоны — на английском.
- Канал attach — attachedContext (тот же, что у трейса). Требует багфикса: InteractionRequestBuilder в minimal-режиме обнулял attachedContext, т.е. трейс, прикреплённый в середине сессии ClaudeCode/Clipboard, УЖЕ терялся молча. Фиксим в STEP_3: attachedContext форвардится всегда, как ideErrors и commandResults.
- Фильтрация меню по виду элемента: каждый показ попапа перечитывает скиллы и резолвит kind под кареткой (BGT + read action). Файловый I/O на каждый попап — осознанный компромисс MVP, кэш в бэклоге.

## Поток
Клик → SkillRecipeAction: read action (резолв элемента + text при attach) → загрузка скилла по имени → рендер editor-template → ChatPrefill.publish(EditorPrefill) → MessageBus → подписка в MaxVibesToolPanel → showChat + ChatPanel.acceptPrefill → префилл + armOneShot(чип) → пользователь жмёт Send → контроллер: one-shot перекрывает скилл сессии на этот ход, element context уезжает в attachedContext, всё сбрасывается в clearAttachmentsAfterSend.

## Утилитарные кнопки (в том же подменю, без скилла)
- Add Element Reference — ДОПИСЫВАЕТ elementPath в конец ввода (append, не затирая — можно собрать сообщение про несколько элементов).
- Add Element to Context — прикрепляет тело элемента без текста (armOneShot без имени скилла; скилл сессии не перекрывается).

## Ограничения MVP
- Editor-скиллы полноценно работают в Clipboard и ClaudeCode. API-режимы specificPrompt не подключали и раньше; плюс найден латентный баг: dispatchApiMessage кладёт в задачу только маркер "[trace: N lines]" без содержимого → в API-режиме element context тоже не доедет. При армированном one-shot и режиме API контроллер честно предупреждает в чате. Сам баг трейса в API — отдельный кандидат в docs/TODOs, вне скоупа.
- Kotlin only; Python-диспатч резолвера — бэклог.
- Backtick-имена: анализ сработает, модификации могут не примениться (docs/TODOs/psi-backtick-function-replace-limitation.md).
- Локальные объявления внутри тел не адресуемы — резолвер поднимается к ближайшему адресуемому члену.

## Шаги (каждый оставляет проект компилируемым)
- STEP_1_SkillMdParser — SkillEditorSpec (domain) + чистый парсер фронтматтера (application) + делегация из репозитория + методы сервиса + Gradle-тесты
- STEP_2_ElementAtCaretResolver — каретка → путь/имя/kind/текст элемента (adapter-psi)
- STEP_3_ChatInputBus — EditorPrefill + топик + acceptPrefill + one-shot в контроллере + чип + багфикс билдера
- STEP_4_Actions — динамическая группа из скиллов + 2 утилитарные кнопки + регистрация в maxvibes-kotlin.xml
- STEP_5_PrebuiltSkills — 8 стартовых скиллов ресурсами + кнопка Install Starter Skills
- STEP_6_SmokeTest — смоук

## Бэклог
Кэш скиллов для попапа; autoSend per-skill (флаг во фронтматтере); intention actions (Alt+Enter); gutter/inlay; скиллы на IdeError; хоткеи; Python; выбор гранулярности attach (сейчас всегда полный текст элемента).
