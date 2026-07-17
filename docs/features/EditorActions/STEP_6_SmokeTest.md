# STEP 6 — Смоук - тест

Перед стартом : rebuild, перезапуск sandbox, Install Starter Skills, Reset сессии .

## Чеклист
1.testVibes, Kotlin - файл: каретка на теле функции → правый клик → Vibe: On Element . Видны function - скиллы(
    characterize,
    seam,
    sprout,
    extract - override,
    explain,
    smells,
    kdoc,
    unittest
) + разделитель + две утилитарные кнопки.2.Каретка на классе: sprout / extract - override(applies - to: function) ИСЧЕЗЛИ из меню; characterize / seam / explain / smells / unittest остались . Фильтрация по kind работает.3.feathers - extract - override на функции: окно MaxVibes активировалось, карточка чата, в inputArea — текст editor - template с корректным elementPath, фокус в поле.НЕ отправлено . Чип «⚡ Skill: function<name>(
    1×)» виден; ✕ убирает чип .
4.Send в ClaudeCode: в CC -логе видно specificPrompt =
    телу скилла и attachedContext с текстом элемента; модель НЕ делает requestedViews на этот элемент(attach убил round - trip).После send чип исчез, следующий send идёт без скилла(
    one - shot
).5.Скилл сессии в дропдауне выбран(не Just Code) + клик по editor - скиллу → на этот send уехал editor - скилл, на следующий — снова скилл из дропдауна.Дропдаун визуально не менялся .
6.Багфикс билдера : в середине активной ClaudeCode - сессии прикрепить Trace → send → в CC -логе attachedContext присутствует(
    до фикса терялся
).Gradle - тест билдера зелёный.7.Add Element Reference: в поле уже есть текст → путь ДОПИСАЛСЯ через пробел, не затёр . Два клика на разных элементах → оба пути в поле.8.Add Element to Context : поле не тронуто, чип появился, send доставил контекст(
    CC - лог
), скилл сессии не перекрыт .
9.Approve при армированном one -shot: предупреждение «skill dropped », approve прошёл штатно.10.Правка установленного SKILL.md(
    изменить editor -template,
    убрать applies -to
) → без ребилда : текст префила изменился / скилл исчез из меню.Ничего не хардкодится.11.Легаси - скиллы из . maxvibes / prompts / specific в меню редактора НЕ появляются; в дропдауне чата — как раньше .
12.Каретка на локальной
val внутри тела → путь поднялся к функции. Каретка между объявлениями → в меню только утилитарные кнопки; клик по ним → подсказка «Put the caret on a named declaration».
13.Не - Kotlin файл → подменю скрыто.PyCharm sandbox (если доступен): подменю скрыто, в логе НЕТ NoClassDefFoundError (регистрация в maxvibes - kotlin.xml).14.Тулвинда закрыта → вызов скилла → окно открылось, префилл и чип на месте(
    гонка activate / publish
).15.`gradlew.bat :maxvibes-application:test` — зелёный(SkillMdParser + builder + сервис).

## По результатам
        Баги — в docs / TODOs /(отдельно завести кандидата: «API mode drops trace content — dispatchApiMessage передаёт только маркер [trace: N lines]»). Решения — в PLAN . md . Затем commit.
