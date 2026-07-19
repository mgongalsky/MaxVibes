# PSI CREATE_ELEMENT for ENUM_ENTRY fails when content includes leading KDoc

## Симптом

При попытке добавить новый литерал в enum через `CREATE_ELEMENT` с `elementKind: ENUM_ENTRY`,
если `content` начинается с KDoc - комментария перед именем литерала, плагин возвращает :

```
Failed to parse code : Failed to parse :
/**
 * Claude Code (local CLI process). MaxVibes s
```

и операция целиком отвергается — литерал в файл не добавляется.

## Воспроизведение

Любой `CREATE_ELEMENT` вида:

```json
{
"type": "CREATE_ELEMENT",
"path": "file:.../SomeEnum.kt/enum[SomeEnum]/enum_entry[EXISTING]",
"elementKind": "ENUM_ENTRY",
"position": "AFTER",
"content": "/** docs */\nNEW_VALUE"
}
```

падает с parse-ошибкой. Если убрать KDoc и оставить только `NEW_VALUE` — операция проходит
успешно.

## Причина (предположение)

`KotlinElementFactory` для `ENUM_ENTRY` использует Kotlin PSI factory-метод (вероятно
`createEnumEntry(text)` или аналог), который ожидает на вход **только декларацию литерала** —
имя плюс опциональные аргументы. KDoc-комментарий в Kotlin PSI прикрепляется к элементу
как отдельный leading-comment node, а не как часть текста самого `KtEnumEntry`. Поэтому
при попытке распарсить строку с KDoc'ом как enum entry парсер не понимает, что перед ним.

Для других элементов (function, class, property) KDoc в `content` обычно работает,
потому что соответствующие фабричные методы парсят полную декларацию из текста файла-обёртки
и там KDoc допустим как часть declaration text. Для enum entry, видимо, используется
более узкий API.

## Workaround (текущий)

Использовать `REPLACE_FILE` для всего файла enum, если новый литерал должен иметь KDoc.
Для enum-файлов это обычно дёшево — они короткие.

Альтернатива: добавлять литерал без KDoc через `CREATE_ELEMENT`, KDoc докидывать вручную
или в следующей итерации (но в плагине пока нет операции «добавить leading-comment к элементу»).

## Возможные решения (для будущей доработки плагина)

1. **Препроцессинг content в `KotlinElementFactory`**: при `elementKind == ENUM_ENTRY`
отделять leading KDoc от основной декларации, парсить их раздельно и крепить KDoc
к созданному `KtEnumEntry` через `addBefore(kdoc, entry.firstChild)`.
2. **Использовать file-level parsing**: оборачивать content в синтетический enum-файл
(`enum class _Wrapper { <CONTENT> }`), парсить как `KtFile`, извлекать первый
`KtEnumEntry` со всеми его leading-trivia. Так делает большинство фабрик для function/class.
3. **Документировать явно**: добавить в системный промпт MaxVibes правило
«для ENUM_ENTRY с KDoc используй REPLACE_FILE» — это самый дешёвый фикс.

## Связанные ограничения

- `psi-backtick-function-replace-limitation.md` — backtick-функции не находятся через path.
- `psi-replace-element-breaks-class-structure.md` — смешивание CREATE+REPLACE в одном батче.

Общая тема: `KotlinElementFactory` имеет неоднородное покрытие сценариев — где-то парсит
полную декларацию, где-то только узкий фрагмент. Имеет смысл провести аудит фабрики и
унифицировать вход (везде принимать «декларация + leading trivia»).

## Статус

Открыто. Workaround — `REPLACE_FILE` для enum-файлов с KDoc на новых литералах.
