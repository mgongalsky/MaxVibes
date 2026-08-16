# Отчёты о сбоях PSI : непокрытые случаи

Отчёты о сбоях пишутся в `.maxvibes/reports/psi/<timestamp>_<kind>_<session>.md`
        из `ChatMessageControllerComposition.reportBrokenStep` . Сейчас туда попадают
        два случая : отказ применения правки (`APPLY`) и запись `modifications`, которую
отбросил разбор ответа(`PARSE`).Ниже — то, что в отчёт не попадает.

## 1.Ответ целиком не является JSON

`JsonInteractionProtocolCodec.decode` намеренно терпим: при отсутствии JSON он
        возвращает `InteractionResponse(message = rawText)`, а не ошибку.Такой ход
        обрабатывает `ClaudeCodeDispatcher` через `pendingFix` / `fixRetries` — он просит
модель переприслать ответ в правильном формате . Отчёта при этом не создаётся,
и разобрать потом, что именно прислала модель, можно только по транскрипту
        диалога.Почему не сделано сразу : у `ClaudeCodeDispatcher` нет ни `project`, ни корня
        проекта, а расширение его первичного конструктора в этом проекте уже дважды
        разрушало класс (см.`psi-primary-constructor-not-editable.md`,
`psi-replace-primary-constructor-corrupts-class.md`).

Что сделать : передать `PsiFailureReportPort` в диспетчер безопасным способом
(свойство с сеттером или отдельный компонент -наблюдатель, а не конструктор) и
писать отчёт вида `PARSE` в момент постановки `pendingFix`, вкладывая в него
сырой текст ответа и номер попытки .

## 2.Правка теряется молча в конвертере

`CodingAgentApprovalService.applyModifications` делает
        `requestedModifications.mapNotNull { ProtocolConverter.convertModification(it) }`.Запись, которую кодек разобрал, а домен не принял, исчезает бесследно : её нет
ни в `malformedModifications`, ни в `modifications`.Как следствие
        `failureCount` занижен, шаг не считается сломанным, отчёт не пишется, а
проверки запускаются поверх кода, куда правка не легла .

Что сделать : вернуть из конвертации причину отказа, а отброшенные записи
добавлять в `malformedModifications` — тогда существующий путь отчёта подхватит
их без изменений.

## Критерии готовности

        -Ответ без JSON создаёт отчёт `PARSE` с сырым текстом ответа .
-Запись, отвергнутая `ProtocolConverter`, попадает в `malformedModifications`
и в отчёт.
-`failureCount` шага равен числу правок, не легших на диск .
