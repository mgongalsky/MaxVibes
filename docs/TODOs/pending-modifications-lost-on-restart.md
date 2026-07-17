# Pending modifications are lost on IDE restart

`pendingModifications` в `ClaudeCodeInteractionService`(и карточка аппрува из фичи ModApproval) живут только в памяти.Если перезапустить IDE, пока сессия в AWAITING_APPROVE, предложенные правки теряются без следа: LLM считает их предложенными, пользователь их больше не видит.Возможное решение : персистить pending - набор в `ChatSession`(
    XML
) вместе со статусом и восстанавливать карточку при загрузке сессии .

Приоритет: низкий; пока фиксируем поведение как известное ограничение .
