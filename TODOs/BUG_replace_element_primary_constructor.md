# BUG: REPLACE_ELEMENT на constructor[primary] разваливает класс

Статус: открыт(обнаружен 2026 - 07 - 29, сессия STEP 1 « ChatMessageController deep cut », шаг 11)

## Симптом

`REPLACE_ELEMENT` с путём `file:.../Foo.kt/class[Foo]/constructor[primary]` и content вида:

```
(
        private
val a: TypeA,
private val b: TypeB
)
```

ломает заголовок класса на уровне текста : тело класса «выпадает» наружу.Компилятор выдаёт характерную лавину :

-`Syntax error: Property getter or setter expected`(на месте бывшего заголовка)
-`Syntax error: Expecting a top level declaration`
-`Function declaration must have a name`
-`Modifier 'private' is not applicable to 'local class' / 'local function' / 'local variable'`(все члены класса стали локальными)
-`Property must be initialized`(у val - параметров конструктора)
-каскад `Unresolved reference` на всех членах класса у вызывающих

## Репро

Воспроизведено дважды за один батч на реальных файлах :

-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/QuestionTurnCoordinator.kt` — замена конструктора `(callbacks: ChatPanelCallbacks)` на `(questionView: QuestionView, callbacks: InputStatusView)`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/CommandTurnCoordinator.kt` — добавление параметра `commandView: CommandView` в 7 - параметрный конструктор

        В том же батче обычные `REPLACE_ELEMENT` по `function[...]` и `property[...]` в тех же и соседних файлах применились корректно — сломались именно и только конструкторы .

## Обходной путь

        Менять сигнатуру primary - конструктора только через `REPLACE_FILE` (полное содержимое файла).Именно так оба файла были восстановлены, тесты 140 / 140 зелёные .

## Куда смотреть при починке

        -`PsiModifier`(или его аналог), ветка обработки сегмента `constructor[primary]` : судя по картине поломки, заменяемым PSI -элементом оказывается не `KtPrimaryConstructor`, а кусок заголовка класса, либо content вставляется с потерей скобок / фигурной скобки тела .
-Проверить и `KtPrimaryConstructor.replace(...)` на конструкторе без ключевого слова `constructor`(наш случай : `class Foo(` без модификаторов).
-Покрыть тестом : замена конструктора у класса с KDoc, вложенными private -классами и телом — именно такая конфигурация развалилась .

## До починки

        Добавить в системный промпт (CLAUDE.md, раздел «PSI limitations — MUST follow») пункт: «Сигнатуру primary -конструктора менять только через REPLACE_FILE, REPLACE_ELEMENT на constructor[primary] запрещён ».
