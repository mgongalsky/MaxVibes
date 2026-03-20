# ColorfulBubbles — Цветная визуализация запросов и модификаций

## Цель

Дать разработчику визуальную обратную связь о « весе » каждого запроса к LLM:
-Запрошенные файлы / view раскрашены по гранулярности
-Модификации раскрашены по уровню операции
-Все записи в развёрнутом футере кликабельны (навигация к элементу)
-В summary -строке показан breakdown по типам вместо простого счётчика

## Цветовая схема

### Запрошенные view (requestedViews)
| Гранулярность | Смысл | Цвет |
|---|-- - |-- - |
| FULL | Тяжело, весь файл | 🔵 Blue #2980 B9 / #5 DADE2 |
| SIGNATURES | Средне, только сигнатуры | 🟡 Yellow #D4AC0D / # F4D03F |
| OUTLINE | Средне, компактный outline | 🟡 Yellow #D4AC0D / # F4D03F |
| ELEMENT | Легко, один элемент | 🟢 Green #27 AE60 / #58 D68D |

### Модификации(appliedModifications)
| Категория | Типы | Цвет |
|---|-- - |-- - |
| FILE_LEVEL | CreateFile, ReplaceFile, DeleteFile | 🔵 Blue #1 A5276 / #2E86 C1 |
| ELEMENT_LEVEL | CreateElement, ReplaceElement, DeleteElement | 🟢 Green #1E8449 / #58 D68D |
| IMPORT | AddImport, RemoveImport | 🟡 Yellow #B7950B / # F4D03F |

## Архитектурный подход

        Добавляем два новых поля в `ChatMessage` (domain):

```
requestViewInfo: List<RequestedViewInfo>  // path + granularity + elementPath?
appliedModifications: List<AppliedModInfo>  // path + ModificationCategory
```

Старые поля `requestedFiles` и `appliedModificationPaths` остаются для compat .
При рендеринге : если новые поля непустые — используем их; иначе fallback на старые
        (старые `appliedModificationPaths` трактуются как ELEMENT_LEVEL).

## Шаги

| Шаг | Файл плана | Слой | Статус |
|---|-- - |-- - |-- - |
| 1 | STEP_1_Domain.md | maxvibes - domain | ⬜ |
| 2 | STEP_2_Persistence.md | maxvibes - plugin / chat | ⬜ |
| 3 | STEP_3_ApplicationWiring.md | maxvibes - application + plugin / ui | ⬜ |
| 4 | STEP_4_DisplayMessage.md | plugin / ui | ⬜ |
| 5 | STEP_5_ConversationPanel.md | plugin / ui | ⬜ |
| 6 | STEP_6_Tests.md | domain + application | ⬜ |

Каждый шаг оставляет плагин компилируемым.

## Инварианты

-Ни один существующий тест не должен сломаться после каждого шага
        -XML backward compat: старые сессии не падают, просто показывают fallback
-Нет изменений в протоколе LLM - запроса(это display -only фича)
-`ConversationRenderer` — единственное место маппинга domain → display
