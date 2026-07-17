# STEP 5 — Смоук - тест

Перед стартом : rebuild, перезапуск sandbox, Reset сессии .

## Чеклист
1.testVibes, Kotlin - файл(например SnakeGame): каретка на теле функции → правый клик → Vibe: On Element видно, 8 пунктов .
2.Explain Element : prefill появился в inputArea с правильным elementPath (file:.../class[X]/function[y]), окно MaxVibes активировалось, показана карточка чата, фокус в поле ввода . Текст НЕ отправлен автоматически.3.Ctrl + Enter в режиме ClaudeCode : модель сама запрашивает ELEMENT / CALLERS через requestedViews, дальше обычный цикл approve .
4.Feathers: Characterization Tests на функции с вызовами : модель запрашивает ELEMENT +USAGES, предлагает только тестовый файл, продакшен - код не трогает.5.Каретка на локальной
val внутри тела функции → путь поднимается к самой функции.
6.Каретка на top - level property; на методе companion object(
    путь содержит
    companion_object
); на enum entry.7.Каретка на пробеле между объявлениями / requiresElement: диалог - подсказка «Put the caret on a named declaration», ничего не отправлено.8.Тулвинда ЗАКРЫТА → вызвать рецепт: окно открылось, prefill дошёл (гонка activate / publish).9.Не - Kotlin файл (xml, md): подменю скрыто .
10.PyCharm sandbox (если доступен): подменю скрыто, НЕТ NoClassDefFoundError в логе (проверка регистрации в maxvibes -kotlin.xml).11.Prefill поверх недописанного текста в inputArea — текст затирается: приемлемо для MVP? (зафиксировать решение)
12.`gradlew.bat :maxvibes-application:test` — зелёный.

## По результатам
        Баги — в docs / TODOs /, решения по открытым вопросам — в PLAN.md, затем commit .
