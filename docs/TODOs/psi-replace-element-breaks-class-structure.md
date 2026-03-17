# PSI REPLACE_ELEMENT corrupts file when inserting after last property

## Что произошло

        В ходе STEP 4(ClipboardInteractionService refactor) были применены несколько `REPLACE_ELEMENT` и `CREATE_ELEMENT` операций к `ClipboardInteractionService.kt` :
-`CREATE_ELEMENT` с `position: AFTER` для добавления `waitingForPaste` после `lastRequest`
        -`REPLACE_ELEMENT` для замены `isWaitingForResponse`, `hasActiveSession`, `reset`, `generateAndCopyJson`, `handlePastedResponseInternal`

После применения всех операций компилятор выдал десятки ошибок вида:
-`Unresolved reference: log`(метод класса не виден)
-`Anonymous functions with names are prohibited`(PSI вставил методы вне тела класса)
-`Syntax error: Expecting '}'`(нарушена структура файла)
-`private` как unresolved reference (модификаторы оказались на уровне файла)

Пришлось применить `REPLACE_FILE` с полным содержимым файла.

## Вероятные причины

### 1.CREATE_ELEMENT AFTER +REPLACE_ELEMENT на том же элементе — конфликт PSI offset

Когда `CREATE_ELEMENT AFTER property[lastRequest]` вставляет новое свойство, все последующие PSI - элементы смещаются . Если в том же батче следует `REPLACE_ELEMENT` для функции, её offset уже устарел — PSI ищет элемент по старым координатам, промахивается, и либо заменяет не тот узел, либо вставляет содержимое вне класса.

**Вывод * *: `CREATE_ELEMENT` и `REPLACE_ELEMENT` в одном батче на одном файле — опасная комбинация . PSI -дерево мутирует после каждой операции, но плагин, судя по всему, применяет все операции к снимку дерева, снятому до начала батча .

### 2.REPLACE_ELEMENT с многострочным content, содержащим экранированные строки

Content `handlePastedResponseInternal` содержал строку :
```kotlin
"reasoning=${
    response.reasoning?.take(40) ?: \"none\"}"
    ```
    Экранирование `\"` внутри JSON → Kotlin string template . Если PSI - фабрика элементов получила некорректно экранированную строку, элемент мог быть распознан как синтаксически неполный, и PSI закрыл его раньше, сдвинув всё последующее содержимое за пределы класса.

    ### 3.Известное ограничение : REPLACE_ELEMENT не поддерживает замену `init` -блоков и иногда даёт сбой для функций с `suspend`

    См.уже задокументированное ограничение в `docs/TODOs/psi-backtick-function-replace-limitation.md`.Возможно, `suspend fun` с большим телом иногда вызывает аналогичный сбой по схожей причине — PSI не может корректно распарсить `suspend` как модификатор в контексте замены узла .

    ## Правило предосторожности (добавить в PLAN - шаблоны)

    > **Если в одном шаге нужно и добавить новое поле, и заменить несколько функций в том же классе — использовать `REPLACE_FILE`.* *
    >
    > Критерий: если батч содержит `CREATE_ELEMENT` +2 или более `REPLACE_ELEMENT` на одном файле → безопаснее `REPLACE_FILE` .

    ## Признаки того, что PSI -операция пошла не так

            Если после применения батча компилятор выдаёт :
    -`Unresolved reference` для методов того же класса
            -`Anonymous functions with names are prohibited`
    -`private` / `companion` как unresolved reference
            -Каскадные `Syntax error: Expecting '}'`

    → это означает, что один или несколько элементов были вставлены * * вне тела класса * * . Нужно немедленно применять `REPLACE_FILE`.

    ## Что делать при следующем подобном шаге

            1.Если рефакторинг затрагивает > 3 элементов одного файла — сразу использовать `REPLACE_FILE` .
    2.Никогда не смешивать `CREATE_ELEMENT` и `REPLACE_ELEMENT` на одном файле в одном батче .
    3.После применения большого батча — сразу запускать `compileKotlin` и проверять на каскадные ошибки.
