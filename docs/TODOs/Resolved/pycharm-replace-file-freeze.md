# PyCharm: REPLACE_FILE ронял apply - пайплайн и навсегда замораживал панель — РЕШЕНО

**Статус:** исправлено(2026 - 07 - 22)
**Компоненты:** maxvibes - adapter - psi - python(PyPsiModifier, PythonElementFactory), maxvibes - plugin(
    ChatMessageController,
    IdeNotificationService
)

## Симптом

В PyCharm после Approve батча с `REPLACE_FILE`:

-панель MaxVibes замерзала навсегда (все кнопки disabled, ввод неактивен), остальной IDE жив;
-файл на диске не менялся;
-CC - лог обрывался сразу после `pending modifications approved` — ни одной строки про apply.

## Корень(стектрейс из idea.log)

```
SEVERE #c.i.o.p.Task — java.lang.IllegalArgumentException:
Can't create an element of type interface com.jetbrains.python.psi.PyFile
from text '...', got PyExpressionStatementImpl instead
```

`PythonElementFactory.createFile` использовал `PyElementGenerator.createFromText(level, PyFile::class.java, text)`.Генератор парсит текст во временный файл и достаёт из него * * первый дочерний элемент * * запрошенного типа —
сам `PyFile` он вернуть не может в принципе . REPLACE_FILE на PyCharm не работал ни разу .

Исключение вылетало из write action → через `runBlocking` убивало `Task.Backgroundable` в
`ChatMessageController.runClaudeCodeBg` → `handleClaudeCodeResult` не вызывался → `setInputEnabled(true)`
некому вызвать → «вечная заморозка ». В Python -пути(в отличие от Kotlin `PsiCodeRepository`) не было
ни одного try /catch.

    ## Что исправлено

    1.`PyPsiModifier.replaceFile` — переписан на `Document.setText`+`commitDocument`
    (канонический способ полной замены файла; PSI - хирургия `children.delete()/add(copy())` удалена).
    2.`PyPsiModifier.runWrite` — try/catch внутри write action, ошибка возвращается как `Result.Failure`
    (паритет с Kotlin - адаптером).3.`PythonElementFactory.createFile` — через `PsiFileFactory.createFileFromText` (это чинит и
    `validateSyntax`, ложно ругавшийся на любой Python-файл).
    4.`ChatMessageController.runClaudeCodeBg` / `runClipboardBg` — страховочный try/catch вокруг
    `runBlocking`: любое исключение адаптера превращается в Error-результат, панель гарантированно
    оживает.`ProcessCanceledException` пробрасывается .
    5.`IdeNotificationService.showProgress` — `setIndeterminate(false)` перед `setFraction`
    (убирает логируемый платформой IllegalStateException).

    ## Как проверять

    Пересобрать плагин, в PyCharm-проекте выполнить REPLACE_FILE файла с docstring в начале.
    Ожидаемо: файл перезаписан; при любой ошибке применения — ❌ в чате с причиной, панель активна.

    ## Родственные TODO

    -`replace-file-silent-failure-with-commands.md` — другой случай (Kotlin-адаптер, .kts+commands), остаётся открытым;
    -`modification-results-not-fed-back-to-llm.md` — теперь ошибки применения хотя бы видны пользователю; фидбек в LLM — отдельная задача.
