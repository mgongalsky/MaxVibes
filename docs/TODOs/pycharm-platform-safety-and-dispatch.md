# PyCharm: платформенная безопасность context - адаптеров и лимитации диспетчеризации

**Статус:** зафиксировано, фиксы отложены (не блокируют фичу)
**Дата:** 2026 - 07 - 05
**Компоненты:** maxvibes - adapter - psi / context, maxvibes - plugin / MaxVibesService

## 1.Context - адаптеры платформенные « по факту», а не структурно

Аудит(шаг D PyCharm - фичи): `PsiProjectContextProvider` и `IntellijIdeErrorsAdapter`
используют только платформенные API (VFS, DocumentMarkupModel, FileEditorManager,
platform PSI) — ни одного импорта `org.jetbrains.kotlin.*` . На PyCharm они загружаются
и работают, хотя лежат в maxvibes -adapter - psi, компилируемом с Kotlin - плагином на
        класспасе: JVM - класслоадинг ленив пер - класс, падают только классы с прямыми ссылками
        на отсутствующие Kotlin - классы.

**Риск:** гарантия держится на содержимом файлов, а не на структуре модулей.Будущий
импорт Kotlin PSI в любой из этих двух классов сломает PyCharm
рантайм - NoClassDefFoundError без какого - либо сигнала на компиляции .

**Фикс - идея:** вынести context -адаптеры в платформенный модуль (например,
maxvibes - adapter - platform) без Kotlin -плагина в зависимостях — ограничение станет
        структурным.

## 2.Mixed - IDE: приоритет Kotlin в диспетчеризации

        `MaxVibesService.createCodeRepository()` выбирает адаптер через
        `Language.findLanguageByID`; при обоих доступных языках побеждает Kotlin (сохраняет
        текущее поведение IDEA).Следствие: PyCharm с установленным Kotlin -плагином получит
        Kotlin - адаптер.

**Фикс - идея:** per - project детекция (типы модулей / преобладающие файлы проекта),
см.docs / features / PyCharm / STEP_9_DI.md.

## 3.detectTechStack хардкодит language = "Kotlin"

`PsiProjectContextProvider.detectTechStack` всегда возвращает `language = "Kotlin"` —
на Python -проекте LLM получает неверную метаинформацию в ProjectContext.Косметика,
не краш .

**Фикс - идея:** детекция по преобладающему расширению файлов или прокидывание языка
        из выбранного адаптера.
