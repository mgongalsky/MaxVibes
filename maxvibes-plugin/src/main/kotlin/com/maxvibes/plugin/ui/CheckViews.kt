package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.check.CheckProgress

/**
 * Chat-side rendering of the check channel: one bubble per requested build or test run.
 *
 * Deliberately narrower than [CommandView] — checks have no Run all / Decline all bar,
 * because a turn carries one or two of them and the coordinator already sequences them.
 */
interface CheckView {

    fun addCheckBubble(
        title: String,
        reason: String?,
        onRun: () -> Unit,
        onDecline: (comment: String?) -> Unit
    ): CheckBlockView
}

/** Handle to a single check bubble, driven by [CheckTurnCoordinator] as the check progresses. */
interface CheckBlockView {

    fun setQueued()

    /**
     * Кнопка Cancel живёт ровно в состоянии Running, поэтому колбэк приходит сюда,
     * а не хранится в пузыре отдельным полем: у неработающей проверки нет способа
     * случайно его дёрнуть.
     */
    fun setRunning(onCancel: () -> Unit)

    /** Живое состояние прогона: что выполняется сейчас и сколько уже сделано. */
    fun setProgress(progress: CheckProgress)

    fun setResult(headline: String, details: String, success: Boolean)

    fun setDeclined(comment: String?)
}
