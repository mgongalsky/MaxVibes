# PSI: невозможность REPLACE_ELEMENT для функций с backtick -именами

## Проблема

При попытке применить `REPLACE_ELEMENT` к тестовым функциям с именами в backtick - нотации
(пробелы и спецсимволы в имени, стандарт для Kotlin - тестов) PSI - адаптер не находит элемент :

```
Element not found: file:.../ClipboardMinimalModeTest.kt
/class[ClipboardMinimalModeTest]
/function[startTask always sends full context regardless of addHistory]
```

Максимум что удаётся — заменить весь класс через `REPLACE_ELEMENT` на уровне класса,
либо переписать весь файл через `REPLACE_FILE` . Оба варианта дорогостоящие по токенам
        и опасны при больших тестовых файлах .

## Воспроизведение

Любой `REPLACE_ELEMENT` с путём вида:
```
file:...Test.kt / class[ MyTest]/function[my test with spaces in name]
```
возвращает `Element not found`, даже если функция существует и имя указано точно .

## Причина(предположение)

Kotlin PSI представляет backtick -функции как `KtNamedFunction`, где `name` содержит
сырой идентификатор с backtick -символами.Вероятно, `PsiNavigator` ищет функцию по
        имени без учёта backtick -обёртки, либо парсит путь некорректно при наличии пробелов
внутри сегмента `function[...]`.

## Последствия

-Точечные правки в тестовых файлах с backtick - функциями невозможны через `REPLACE_ELEMENT` .
-Приходится использовать `REPLACE_FILE` или `REPLACE_ELEMENT` на уровне всего класса,
что увеличивает расход токенов и риск затронуть несвязанный код.

## Возможные решения

        1.* * Экранирование в PsiNavigator * *: при парсинге пути сегмент `function[name with spaces]`
искать по имени с backtick - обёрткой: `` ` name with spaces ` ``.
2.* * Нормализация имени * * : при поиске элемента сравнивать `name` без backtick и без
        учёта регистра, чтобы путь `function[foo bar]` находил ` ` fun ` foo bar `() ``.
3.* * Альтернативная адресация * * : поддержать адресацию по номеру строки или по
        аннотации(`@Test` - индекс) как запасной механизм для тест -функций.

## Статус

Открыто.Обходное решение — `REPLACE_FILE` или `REPLACE_ELEMENT` на уровне класса.
