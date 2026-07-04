# STEP 4 — Тесты

## A.StreamJsonProtocol(plugin - модуль, запуск через IDEA runner)

`StreamJsonProtocolTest` — создать рядом с существующими тестами плагина, если файла
        ещё нет . Кейсы для `extractThinkingFull` :

1.assistant - событие с одним thinking -блоком → полный текст без обрезки,
переносы строк сохранены;
2.два thinking -блока в ОДНОМ content [] → склейка через пустую строку (`\n\n`);
3.смешанный content (thinking + text) → возвращается только thinking;
4.событие только с text -блоками → null;
5.не - assistant событие (system / init, result) → null;
6.битая / не - JSON строка → null(parseLine глотает).Заодно регрессия : на кейсе 1 `extractAssistantText` возвращает null(
    thinking не
            протекает в финальный текст
).

## B.ClaudeCodeInteractionService(maxvibes - application, Gradle)

Место: `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/`.Стек: MockK + JUnit 5 + `runBlocking`(
    kotlinx - coroutines - test в проекте НЕТ
).Мокается ClaudeCodePort; по образцу существующих тестов сервиса.Кейсы:

1.`port.send` → `Success(ClaudeCodeSendResult(resp, "sid", thinkingText = "FULL THOUGHTS"))`,
resp без reasoning и без views ⇒ результат `Completed`, `llmReasoning == "FULL THOUGHTS"`;
2.thinkingText + `response.reasoning = "json reasoning"` ⇒
`llmReasoning == "FULL THOUGHTS\n\njson reasoning"`(CLI - thinking первым);
3.thinkingText = null, reasoning = null ⇒ `llmReasoning == null`;
4.ответ с codeViewRequests и thinkingText ⇒ `WaitingForApprove.llmReasoning` содержит thinkingText.Запуск: `./gradlew :maxvibes-application:test`

## Чекпоинт

Оба набора зелёные; STEP 3 смоук пройден ранее . Фича готова — при желании завести
в docs / TODOs заметку про возможное сворачиваемое отображение thinking по раундам
        (разделители между блоками), если склейка пустой строкой окажется нечитаемой .
