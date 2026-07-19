# Feature: CodeGranularity — Частичная выдача файлов

## Цель

Минимизировать количество входящих токенов за счёт частичной выдачи содержимого файлов .
Вместо полного файла LLM может запросить только сигнатуры, outline или конкретный элемент .

## Проблема

Сейчас плагин всегда отправляет файлы целиком . Для большинства задач (понять структуру, уточнить
сигнатуру соседнего класса) это избыточно и дорого по токенам .

## Решение

Расширить протокол : вместо `requestedFiles: ["path"]` LLM может указать
        `requestedViews: [{path, granularity, elementPath?}]`.Плагин через PSI рендерит нужный «вид» файла и возвращает его в следующем сообщении.

## Варианты гранулярности (CodeGranularity)

| Значение    | Что возвращается |
|-------------|------------------|
| `FULL`      | Полный файл (текущее поведение, дефолт) |
| `SIGNATURES`| Все декларации верхнего уровня и членов классов — только сигнатуры, без тел |
| `OUTLINE`   | Структура класса : суперклассы, свойства(имя + тип), сигнатуры методов |
| `ELEMENT`   | Конкретный элемент по `elementPath` — полный текст включая тело |

## Обратная совместимость

        -Старый `requestedFiles` продолжает работать как `granularity: FULL`
        -Если `requestedViews` содержит элемент без `granularity` — дефолт `FULL`
-Ничего не ломается если LLM не использует новый формат

## Шаги реализации

| Шаг | Что делаем | Модуль | Тестируем |
|-----|---------- - |--------|----------|
| [STEP_1](STEP_1_Domain.md) | Доменные модели | `maxvibes-domain` | Unit : data classes |
| [STEP_2](STEP_2_ApplicationPort.md) | Расширение порта CodeRepository + ClipboardRequestSchema | `maxvibes-application` | Компиляция |
| [STEP_3](STEP_3_Codec.md) | Парсинг `requestedViews` в Codec | `maxvibes-application` / `maxvibes-plugin` | Unit : codec |
| [STEP_4](STEP_4_PsiRenderer.md) | PsiCodeViewRenderer в adapter - psi | `maxvibes-adapter-psi` | Integration: рендеринг |
| [STEP_5](STEP_5_PsiRepository.md) | Реализация `getCodeView()` в PsiCodeRepository | `maxvibes-adapter-psi` | Smoke test в IDE |
| [STEP_6](STEP_6_Tests.md) | Автоматические тесты (unit + integration) | все | `./gradlew test` |
| [STEP_7](STEP_7_Prompt.md) | Обновление системного промпта | `maxvibes-plugin` | Ручной smoke test |

## Затрагиваемые файлы

        -`maxvibes-domain/…/code/CodeElement.kt`(или новые файлы рядом)
-`maxvibes-application/port/output/CodeRepository.kt`
-`maxvibes-application/port/output/ClipboardRequestSchema.kt`
-`maxvibes-plugin/clipboard/JsonClipboardProtocolCodec.kt`
-`maxvibes-adapter-psi/PsiCodeRepository.kt`
-`maxvibes-adapter-psi/…`(новый `PsiCodeViewRenderer.kt`)
-`maxvibes-plugin/resources/prompts/chat-system.md`
