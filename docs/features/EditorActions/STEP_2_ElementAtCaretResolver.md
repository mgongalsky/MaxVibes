# STEP 2 — ElementAtCaretResolver(adapter - psi)

Цель: каретка → ближайшее адресуемое объявление → строка ElementPath по грамматике плагина.Обратная операция к PsiNavigator .

## Новый файл
        `maxvibes-adapter-psi/src/main/kotlin/com/maxvibes/adapter/psi/operation/ElementAtCaretResolver.kt`

```kotlin
package com.maxvibes.adapter.psi.operation

import com . intellij . psi . PsiElement
        import com . intellij . psi . PsiFile
        import com . intellij . psi . util . PsiTreeUtil
        import org . jetbrains . kotlin . psi . KtClass
        import org . jetbrains . kotlin . psi . KtClassBody
        import org . jetbrains . kotlin . psi . KtClassOrObject
        import org . jetbrains . kotlin . psi . KtEnumEntry
        import org . jetbrains . kotlin . psi . KtFile
        import org . jetbrains . kotlin . psi . KtNamedDeclaration
        import org . jetbrains . kotlin . psi . KtNamedFunction
        import org . jetbrains . kotlin . psi . KtObjectDeclaration
        import org . jetbrains . kotlin . psi . KtParameter
        import org . jetbrains . kotlin . psi . KtProperty
        import org . jetbrains . kotlin . psi . KtTypeParameter

        /**
         * Resolves the addressable declaration at a caret offset and renders its
         * MaxVibes element path (grammar: class[X]/function[y], companion_object, enum_entry[E]).
         *
         * Addressable = a direct member of the file or of a chain of named classes/objects.
         * Local declarations (inside function bodies, initializers, lambdas) are not
         * addressable — the resolver climbs to the nearest addressable ancestor.
         *
         * Must be called inside a read action. Stateless.
         */
        object ElementAtCaretResolver {

            data class CaretElement(
                /** Full path like file:src/.../Foo.kt/class[Foo]/function[bar], null when only the file is addressable. */
                val elementPath: String?,
                val elementName: String?,
                /** Segment kind of the leaf (function / property / class / ...) — for UI hints. */
                val kind: String?
            )

            fun resolve(psiFile: PsiFile, offset: Int, projectRelativePath: String): CaretElement {
                if (psiFile !is KtFile) return CaretElement(null, null, null)
                val leaf = psiFile.findElementAt(offset) ?: return CaretElement(null, null, null)
                val start = PsiTreeUtil.getParentOfType(leaf, KtNamedDeclaration::class.java, false)
                    ?: return CaretElement(null, null, null)
                val target = nearestAddressable(start) ?: return CaretElement(null, null, null)

                val leafSegment = segmentFor(target) ?: return CaretElement(null, null, null)
                val segments = mutableListOf(leafSegment)
                var container = PsiTreeUtil.getParentOfType(target, KtClassOrObject::class.java, true)
                while (container != null) {
                    val seg = segmentFor(container) ?: return CaretElement(null, null, null)
                    segments.add(0, seg)
                    container = PsiTreeUtil.getParentOfType(container, KtClassOrObject::class.java, true)
                }
                return CaretElement(
                    elementPath = "file:" + projectRelativePath + "/" + segments.joinToString("/"),
                    elementName = target.name,
                    kind = kindLabel(target)
                )
            }

            /** Climbs from [start] to the innermost declaration that is a direct member of the file/class chain. */
            private fun nearestAddressable(start: KtNamedDeclaration): KtNamedDeclaration? {
                var candidate: KtNamedDeclaration? = start
                while (candidate != null) {
                    if (isDirectMember(candidate)) return candidate
                    candidate = PsiTreeUtil.getParentOfType(candidate, KtNamedDeclaration::class.java, true)
                }
                return null
            }

            private fun isDirectMember(d: KtNamedDeclaration): Boolean {
                if (d is KtParameter || d is KtTypeParameter) return false
                val isCompanion = d is KtObjectDeclaration && d.isCompanion()
                if (d.name.isNullOrBlank() && !isCompanion) return false
                var p: PsiElement? = d.parent
                while (p != null && p !is KtFile) {
                    p = when (p) {
                        is KtClassBody -> p.parent
                        is KtClassOrObject -> {
                            val named = p.name != null || (p is KtObjectDeclaration && p.isCompanion())
                            if (!named) return false // anonymous object literal
                            p.parent
                        }

                        else -> return false // block, initializer, lambda => local declaration
                    }
                }
                return true
            }

            private fun segmentFor(d: KtNamedDeclaration): String? = when {
                d is KtObjectDeclaration && d.isCompanion() -> "companion_object"
                d is KtEnumEntry -> d.name?.let { "enum_entry[" + it + "]" }
                d is KtObjectDeclaration -> d.name?.let { "object[" + it + "]" }
                d is KtClass && d.isInterface() -> d.name?.let { "interface[" + it + "]" }
                d is KtClass -> d.name?.let { "class[" + it + "]" }
                d is KtNamedFunction -> d.name?.let { "function[" + it + "]" }
                d is KtProperty -> d.name?.let { "property[" + it + "]" }
                else -> null
            }

            private fun kindLabel(d: KtNamedDeclaration): String? = when {
                d is KtObjectDeclaration && d.isCompanion() -> "companion_object"
                d is KtEnumEntry -> "enum_entry"
                d is KtObjectDeclaration -> "object"
                d is KtClass && d.isInterface() -> "interface"
                d is KtClass -> "class"
                d is KtNamedFunction -> "function"
                d is KtProperty -> "property"
                else -> null
            }
        }
```

## Заметки
-projectRelativePath приходит от вызывающего экшена: virtualFile.path.removePrefix(basePath)
    .removePrefix(slash) — VFS всегда с прямыми слэшами, включая Windows (так уже делают старые экшены).
-Backtick - имена: PSI отдаёт name без бэктиков; навигация по таким путям — известное ограничение(docs / TODOs / psi - backtick - function - replace - limitation.md).Не блокируем, просто фиксируем .
-Enum class сейчас маппится в class[ Name] — PsiNavigator это резолвит; отдельный kind enum добавим при необходимости .

## Проверка шага
        `gradlew.bat :maxvibes-adapter-psi:compileKotlin` — компилируется.Поведенческая проверка — в STEP_5(
    юнит - тест на PSI требует IntelliJ Test Framework,
    для MVP хватит смоука
).
