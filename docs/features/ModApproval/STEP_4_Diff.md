# Шаг 4 — Diff - окно

## Цель

Кнопка Diff у каждой pending - правки открывает стандартное окно сравнения IntelliJ .

## Изменения

1.Новый `maxvibes-plugin/.../ui/ModificationDiffHelper.kt` :
-`show(project, row: PendingModView)`.
-before: REPLACE_ELEMENT / DELETE_ELEMENT — текущий текст элемента из PSI(
    view по element path через CodeRepository / PsiNavigator,
    read action
); REPLACE_FILE / DELETE_FILE — текст файла; CREATE_ * — пустой .
-after: `content` для CREATE / REPLACE; DELETE_ * — пустой; ADD_IMPORT / REMOVE_IMPORT — без диффа (однострочной подписи в карточке достаточно).
-Показ: `SimpleDiffRequest` + `DiffContentFactory` с типом файла Kotlin, `DiffManager.getInstance().showDiff(...)`.2.Кнопка Diff в карточке Шага 3 вызывает хелпер.

## Acceptance

-REPLACE_ELEMENT: слева текущий код элемента, справа предлагаемый .
-CREATE_ELEMENT: слева пусто .
-Битый path : диалог с сообщением об ошибке, не исключение .
