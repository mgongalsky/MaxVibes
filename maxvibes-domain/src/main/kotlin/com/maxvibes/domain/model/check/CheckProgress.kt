package com.maxvibes.domain.model.check

/**
 * Промежуточное состояние выполняющейся проверки.
 *
 * Нужно ровно для одного: пользователь должен видеть, что проверка живая.
 * Голый счётчик секунд эту задачу не решает, поэтому [label] обязателен —
 * это имя того, что выполняется прямо сейчас. Счётчики опциональны: сборка
 * не знает ни общего числа, ни прогресса, тесты знают оба.
 */
data class CheckProgress(
    val label: String,
    val completed: Int? = null,
    val total: Int? = null,
    val failed: Int = 0
)

/**
 * Канал, по которому раннер отдаёт [CheckProgress] наверх.
 *
 * [NOOP] существует, чтобы раннеры и тесты, которым нечего сообщать, не были
 * обязаны ничего знать про UI.
 */
fun interface CheckProgressSink {

    fun publish(progress: CheckProgress)

    companion object {
        val NOOP: CheckProgressSink = CheckProgressSink { }
    }
}

/**
 * Одноразовый выключатель для запущенной проверки.
 *
 * Раннер регистрирует в [onCancel] способ прервать именно свою реализацию
 * (убить процесс тестов), UI дёргает [cancel]. Отмена корутины здесь не годится:
 * проверка выполняется внутри блокирующей фоновой задачи IDE, и настоящая
 * остановка — это завершить порождённый процесс.
 *
 * Гонка «отмену нажали раньше, чем раннер успел зарегистрироваться» разрешается
 * внутри: опоздавший [onCancel] выполняет действие немедленно.
 */
class CheckCancellation {

    @Volatile
    var isCancelled: Boolean = false
        private set

    private var canceller: (() -> Unit)? = null
    private val lock = Any()

    fun onCancel(action: () -> Unit) {
        val alreadyCancelled = synchronized(lock) {
            canceller = action
            isCancelled
        }
        if (alreadyCancelled) action()
    }

    fun cancel() {
        val action = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            canceller
        }
        action?.invoke()
    }
}
