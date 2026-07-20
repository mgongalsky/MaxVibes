# Миграция на IntelliJ Platform Gradle Plugin 2.x

**Статус:** запланировано, не блокирует релизы
**Дата:** 2026 - 07 - 20
**Компоненты:** build.gradle.kts(root), maxvibes - plugin, maxvibes - adapter - psi, maxvibes - adapter - psi - python

## Зачем

Сейчас сборка на `org.jetbrains.intellij` * * 1.17.4 * * — устаревшем и неподдерживаемом.Главная боль, пойманная 2026 - 07 - 20: плагин 1.x * * не умеет запускать IDE с layout'ом
2024.2 + * * — `runIde` / `runIdePyCharm` против установленного PyCharm 2025.x падает с голым
        `Index: 1, Size: 1` на этапе разбора дистрибутива.То есть запустить современный PyCharm
в песочнице невозможно в принципе; рабочий обходной путь — ставить zip из `buildPlugin`
        в реальный PyCharm через « Install Plugin from Disk» (проверено: 1.2.2 работает в CE 2025.2).

Что даёт 2.x(`org.jetbrains.intellij.platform`):

-запуск современных IDE, включая * * скачивание PyCharm по типу * *(`pycharmCommunity(...)`) —
исчезают захардкоженные пути и переменная `PYCHARM_PATH` в `runIdePyCharm`;
-официальная поддержка новых версий платформы(1.x будет ломаться и дальше);
-нормальная декларация зависимостей на платформу / плагины в `dependencies { intellijPlatform { ... } }`.

## Объём работ

        Блок `intellij {}` есть в трёх модулях — во всех меняется DSL:

| Модуль | Сейчас | На что менять |
|---|-- - |-- - |
| maxvibes - plugin | IC 2023.1.5 + `com.intellij.java`, `org.jetbrains.kotlin` | `intellijIdeaCommunity(...)` + bundled plugins |
| maxvibes - adapter - psi | IC + Kotlin(компиляция против Kotlin PSI) | то же |
| maxvibes - adapter - psi - python | **PC 2023.1.5 + `PythonCore` * *, buildPlugin / runIde отключены | `pycharmCommunity(...)` +`PythonCore` |

Плюс root `build.gradle.kts`(версия плагина в `plugins {}`), задачи `runIdePyCharm` /
`runIdeAndroidStudio`(в 2.x кастомные IDE - таргеты делаются иначе), и перепроверка
        хака с вырезанием `coroutines-javaagent` в тестах maxvibes - plugin.При миграции стоит поднять и целевую платформу с 2023.1.5 — иначе главный мотив
(запуск свежего PyCharm) не реализуется.

## Промежуточная идея до миграции (не сделано)

Тестировать Python -адаптер без PyCharm можно в обычной IDEA - песочнице, добавив
`PythonCore` в `intellij.plugins` maxvibes -plugin(версию брать совместимую с 231).
**Ловушка:** `MaxVibesService.createCodeRepository()` глобально предпочитает Kotlin —
в песочнице с обоими языками Python -файлы получит Kotlin - адаптер.Для честного теста
временно убирать `org.jetbrains.kotlin` из списка плагинов песочницы, либо сначала
        сделать per -project диспетчеризацию (см.`pycharm-platform-safety-and-dispatch.md`, п.2).
