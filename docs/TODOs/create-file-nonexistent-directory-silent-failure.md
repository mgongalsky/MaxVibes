# CREATE_FILE не создаёт отсутствующие директории (тихий сбой)

**Статус:** РЕШЁН 2026-07-28
**Обнаружен:** при записи планов фич в новые папки `docs/features/<Name>/`

## Симптом (исторический)

Когда модификация `CREATE_FILE` указывала путь, родительская директория которого
ещё не существует, файл не создавался. Ошибка наружу не отдавалась — ответ
выглядел успешным, но файла на диске не было.

## Репро 2026-07-17 (сессия EditorActions)

Батч из 8 `CREATE_FILE` в несуществующие
`maxvibes-plugin/src/main/resources/skills/<id>/SKILL.md` (включая промежуточную
`skills/`): «Applied approved modifications», ноль файлов, ноль ошибок. Обход —
ручной `New-Item -ItemType Directory -Force` через канал `commands`.

## Корневая причина

`PsiCodeRepository.findOrCreateDirectory` перебирал список possiblePaths —
фолбэк-цепочку `$base/$dirPath` → `src/main/kotlin` → `src` → корень проекта.
Корень проекта существует всегда, поэтому при отсутствующей целевой папке метод
молча возвращал ЧУЖУЮ существующую директорию, а корректная ветка
`createDirectoryPath` (создание недостающих сегментов) была недостижимым мёртвым
кодом. Вдобавок исключения внутри `WriteCommandAction`-лямбды не пробрасывались
через `invokeAndWait` во внешний catch — вторая «тихая» дыра.

## Решение (2026-07-28)

В `PsiCodeRepository` (maxvibes-adapter-psi):

1. **findOrCreateDirectory** — фолбэк-цепочка удалена: резолвится строго целевая
директория; если её нет — все недостающие сегменты создаются через
`createDirectoryPath` (пофайлово `findSubdirectory` → `createSubdirectory`
внутри write command).
2. **createFile** — добавлен `errorMessage`: недоступная директория, null от
`modifier.createFile` и исключения внутри write-лямбды теперь превращаются в
явный `ModificationResult.Failure(IOError(...))` с конкретным путём и причиной
вместо generic «Failed to create file» или ложного успеха.

## Верификация

Смоук на testMaxVibes 2026-07-28: `CREATE_FILE` в `TODOs/features/test.md` —
ни одного сегмента пути не существовало, файл создан штатно с первого раза.

## Связанные заметки

- Общая тема «операция не выполнилась, но наружу отдан успех»:
`psi-delete-element-silent-failure-in-batch.md`.
