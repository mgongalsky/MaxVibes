# STEP 0 — Починить тесты

        Цель: зелёный `./gradlew test --continue` по всем модулям.Без этого распил монстров не начинаем .

## 0.1 Фикс компиляции application -тестов

`maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClipboardMinimalModeTest.kt:122` — анонимный объект `PromptPort` не реализует появившийся член `claudeCodeSystem` .

-Добавить недостающий член в фейк.
-Проверить остальные анонимные фейки `PromptPort` в тестах модуля (grep по `object : PromptPort`).

## 0.2 Фикс 21 падения в plugin : MockK vs ChatPanelCallbacks

        Все 21 падения — `MockKException: Can't instantiate proxy for class com.maxvibes.plugin.ui.ChatPanelCallbacks` в `ChatMessageControllerAttachmentTest` и `ChatMessageControllerSessionTest` .

Диагностика(в порядке вероятности):
1.Версия MockK несовместима с JDK прогона → попробовать обновить MockK до актуальной .
2.Если обновление не помогает или нежелательно — заменить `mockk<ChatPanelCallbacks>()` на рукописный `FakeChatPanelCallbacks` .

Рекомендация: сразу писать `FakeChatPanelCallbacks`(
    запись вызовов в списки,
    no - op view handles
).Интерфейс на ~30 методов мокать через MockK неудобно в принципе; фейк переживает эволюцию интерфейса ошибкой компиляции в одном месте, а не 21 рантайм -падением.

## 0.3 Фундамент : переиспользуемые фейки портов

        Создать пакет фейков вместо анонимных объектов в каждом тесте:

-`maxvibes-application/src/test/kotlin/com/maxvibes/application/testsupport/` — `FakePromptPort`, `FakeChatSessionRepository`, `FakeClaudeCodePort`, `FakeCodeRepository`, `FakeNotificationPort`, `FakeProjectContextPort`.
-`maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/testsupport/` — `FakeChatPanelCallbacks`.
-Миграция существующих тестов на фейки — по мере касания, не большим взрывом.
-Опционально(отдельным решением): `java-test-fixtures` плагин, чтобы plugin -модуль мог переиспользовать фейки application - портов.

## Definition of Done

-[] `./gradlew test --continue` — 0 failed, 0 compilation errors во всех модулях .
-[] `FakeChatPanelCallbacks` существует, оба Controller -теста на нём.
-[] Пакет `testsupport` создан минимум с фейками, нужными для STEP_1 / STEP_2.
