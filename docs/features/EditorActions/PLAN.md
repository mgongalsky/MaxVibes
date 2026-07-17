# EditorActions — вайбкодинг из редактора

## Цель
Правый клик на PSI -элементе → готовый «рецепт» с подставленным elementPath попадает в поле ввода чата MaxVibes.Одно нажатие Ctrl + Enter — и текущий режим(
    ClaudeCode / Clipboard / API
) делает остальное: сбор контекста, approve, модификации.

## Что показал анализ кода
        1.Старые экшены (AnalyzeCodeAction, ModifyCodeAction, SmartModifyAction) — только API -режим, уровень файла (`ElementPath.file(...)`), элемент под кареткой не резолвится, чат не задействован.Оставляем как есть, новая фича идёт параллельно .
2.Готовый вход в чат уже есть : ChatPanel +ChatMessageController поддерживают все 4 режима (approve - гейт ClaudeCode, команды, вопросы). Не хватает только способа доставить текст в inputArea снаружи.3.PSI - слой уже умеет всё нужное для Физерса: USAGES(
    с via -super
), CALLERS(дерево ≤3 / ≤40), SIGNATURES / OUTLINE / ELEMENT.Модель умеет запрашивать это сама через requestedViews — значит, экшену не нужно собирать контекст, достаточно правильно составить сообщение .
4.Classloading: код с Kotlin PSI нельзя грузить на IDE без Kotlin -плагина.Экшены регистрируем в maxvibes -kotlin.xml(
    optional depends
) — тогда их классы вообще не существуют там, где нет Kotlin.

## Ключевые решения
        -Экшены НЕ вызывают LLM и не собирают файлы . Только : резолв элемента → шаблон рецепта → prefill ввода чата . Prefill вместо автосенда : пользователь может дописать детали, и нет случайных дорогих отправок в ClaudeCode.
-Доставка текста : MessageBus -топик → подписка в MaxVibesToolPanel(маленький файл) → chatPanel.prefillInput()(
    CREATE_ELEMENT,
    init огромного ChatPanel.kt не трогаем
) + переключение на карточку чата .
-Авто - контекст(USAGES / CALLERS / ELEMENT) прописан в тексте рецепта — модель запрашивает сама, транспорт и протокол не меняются.
-Рецепты — чистый каталог в application -слое(тестируется Gradle без IDE).Файловые оверрайды — в бэклог.

## Поток
RecipeAction(EDT) → readAction: ElementAtCaretResolver(
    psiFile,
    offset
) → EditorRecipeCatalog.compose → ToolWindow.activate → messageBus.syncPublisher(ChatInputListener)
    .onPrefillRequested(text) → MaxVibesToolPanel → showChat + chatPanel.prefillInput.

## Рецепты MVP (8)
Feathers: characterize(характеризационные тесты), seam(
    анализ швов,
    без модификаций
), sprout(Sprout Method), extract - override.Быстрые: explain, smells, kdoc, unittest.

## Шаги
-STEP_1: EditorRecipe + EditorRecipeCatalog + тест(application)
-STEP_2: ElementAtCaretResolver(adapter - psi)
-STEP_3: ChatInputListener topic +prefillInput + подписка в MaxVibesToolPanel(plugin)
-STEP_4: MaxVibesOnElementGroup + RecipeAction + регистрация в maxvibes - kotlin.xml
-STEP_5: смоук - тест
Каждый шаг оставляет проект компилируемым.

## Отложено(бэклог)
Python - диспатч резолвера; пользовательские рецепты из.maxvibes / prompts / recipes /; флаг autoSend per - recipe; intention actions (Alt + Enter); gutter / inlay - подсказки; «объясни ошибку » на IdeError; хоткеи на топ - рецепты.

## Риски и ограничения
-Локальные функции /
val внутри тел не адресуемы грамматикой пути — резолвер поднимается к ближайшему прямому члену класса/файла.
-Backtick - имена: известное ограничение навигации(docs / TODOs / psi - backtick - function - replace - limitation.md) — анализ сработает, модификации могут не примениться .
-update() группы — только дешёвая проверка языка; резолв элемента строго в actionPerformed.
-Гонка activate / publish при первом открытии тулвинды — проверяется в STEP_5 .

## Открытые вопросы к Максиму
        1.Prefill vs автосенд по умолчанию? (сейчас prefill)
2.Хоткеи сразу или после обкатки в меню?
3.Русский текст рецептов ок ?(модель отвечает по - русски; для публичного релиза можно перевести)
