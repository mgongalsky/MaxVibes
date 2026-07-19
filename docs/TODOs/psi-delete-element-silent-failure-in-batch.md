# PSI DELETE_ELEMENT silently fails when batched with CREATE_ELEMENT on the same parent

## Симптом

Когда в одном батче применяются `DELETE_ELEMENT` для функции и сразу следом
`CREATE_ELEMENT`(sibling, position `BEFORE` / `AFTER`) с * * тем же именем функции * *
        на том же родителе (классе или интерфейсе), результат:

-DELETE_ELEMENT не сообщает об ошибке.
-Старая функция остаётся в файле.
-Новая функция добавляется рядом .
-В файле оказывается * * две функции с одинаковым именем * * — Kotlin компилятор
немедленно падает с `Conflicting overloads` .

Снаружи это выглядит как « команда DELETE проигнорирована, а CREATE сработала».

## Воспроизведение(наблюдалось в этой сессии)

При попытке заменить сигнатуру `fun encode(request: ClipboardRequest): String`
в интерфейсе `InteractionProtocolCodec` на `fun encode(request, omitMetaFields)`
был отправлен батч:

```json
[
    {
        "type": "DELETE_ELEMENT",
        "path": "file:.../InteractionProtocolCodec.kt/interface[InteractionProtocolCodec]/function[encode]"
    },
    {
        "type": "CREATE_ELEMENT",
        "path": "file:.../InteractionProtocolCodec.kt/interface[InteractionProtocolCodec]/function[decode]",
        "position": "BEFORE",
        "elementKind": "FUNCTION",
        "content": "fun encode(request: ClipboardRequest, omitMetaFields: Boolean = false): String"
    }
]
```

Результат: в интерфейсе оказалось * * два * * `fun encode(...)` — старый и новый,
с одинаковыми именами и сигнатурами(после применения default - параметра).Компилятор:

```
Conflicting overloads : public abstract fun encode(
    request: ClipboardRequest,
    omitMetaFields: Boolean = ...): String defined in InteractionProtocolCodec,
public abstract fun encode(
    request: ClipboardRequest,
    omitMetaFields: Boolean = ...): String defined in InteractionProtocolCodec
```

Workaround: `REPLACE_FILE` для всего файла . Только тогда исчезла дублирующая
декларация.

## Вероятные причины

        Точно та же модель, что описана в
`psi-replace-element-breaks-class-structure.md` — операции применяются к снимку
        PSI - дерева, снятому до начала батча . После DELETE родительский элемент мутирует,
но CREATE использует прежние offset'ы и пути. Конкретно для DELETE возможны
два сценария отказа:

1.Резолвинг пути `function[encode]` в момент DELETE находит элемент, но сама
        операция не коммитится до конца батча . CREATE затем добавляет вторую
функцию рядом .
2.Резолвинг пути находит * * другой * * элемент(
    например первую попавшуюся
            `function[encode]` среди нескольких overload'ов), а наш целевой остаётся.
            Если в результате получились два метода с одинаковой сигнатурой — это
            именно сценарий (2).Точный механизм не подтверждён логами плагина . Симптом — двойная декларация после
            batch DELETE +CREATE на одном parent .

    ## Правило предосторожности (добавить в PLAN - шаблоны)

> **Не использовать DELETE_ELEMENT в одном батче с CREATE_ELEMENT на том же
> родительском элементе . * *
>
> Критерии перехода на `REPLACE_FILE` :
> -DELETE_ELEMENT + CREATE_ELEMENT на одном parent → `REPLACE_FILE` .
> -DELETE_ELEMENT функции / свойства с целью « изменить сигнатуру» → `REPLACE_ELEMENT`
>   на самом элементе(если PSI поддерживает) или `REPLACE_FILE`.
>
> Чистый `REPLACE_ELEMENT` обычно безопаснее, чем пара DELETE + CREATE: меняется
> один узел, родитель не реструктурируется.

## Признаки того, что DELETE не сработал

        -После применения батча компилятор выдаёт `Conflicting overloads` для метода,
который должен был быть удалён.
-В файле физически присутствуют два соседних объявления с одинаковым именем .
-Плагин при этом не сообщил об ошибке при DELETE.

→ Применять `REPLACE_FILE` немедленно.Не пытаться повторить DELETE отдельным
батчем — поведение неустойчиво .

## Связанные ограничения

        -`psi-replace-element-breaks-class-structure.md` — общая тема : PSI mutating - batch
effects, снимок дерева до начала батча.
-`psi-backtick-function-replace-limitation.md` — резолвинг путей PSI неоднороден .

## Возможные решения (для будущей доработки плагина)

1.* * Последовательное применение с пересчётом offset'ов**: применять каждую
операцию батча с фиксацией PSI - tree между шагами, чтобы пути CREATE
ресолвились на актуальном дереве .
2.* * Явная ошибка вместо silent skip * *: если DELETE_ELEMENT не находит
        уникального target 'а — возвращать `ModificationResult.Failure` с понятным
сообщением, а не молча no -op.3.* * Документировать в системном промпте * * : добавить правило «для смены
        сигнатуры функции используй REPLACE_ELEMENT, не DELETE +CREATE».

## Статус

Открыто.Workaround — `REPLACE_FILE` или `REPLACE_ELEMENT` на самом элементе .
