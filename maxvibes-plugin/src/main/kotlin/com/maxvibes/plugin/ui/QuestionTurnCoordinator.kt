package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * State machine for one turn of LLM questions (the `questions` channel).
 *
 * Renders interactive question blocks via [QuestionView.addQuestionBubble];
 * the main input stays enabled — typing a message instead is a valid answer and
 * supersedes the blocks via [dismissQuestionTurn]. Once every block is answered,
 * the composed answer is submitted through the panel's regular send path.
 */
class QuestionTurnCoordinator(
    private val questionView: QuestionView,
    private val callbacks: InputStatusView
) {

    /** One question awaiting the user's choice, with its rendered block handle. */
    private class QuestionItem(val question: InteractionQuestion) {
        var view: QuestionBlockView? = null
        var answer: String? = null
    }

    /** In-flight questions turn. Null when no questions are pending. */
    private class QuestionTurn {
        val items = mutableListOf<QuestionItem>()
    }

    private var questionTurn: QuestionTurn? = null

    fun presentQuestions(questions: List<InteractionQuestion>) {
        if (questions.isEmpty()) return
        MaxVibesLogger.info("QuestionCoordinator", "presentQuestions", mapOf("count" to questions.size))
        val turn = QuestionTurn()
        questionTurn = turn
        questions.forEach { question ->
            val item = QuestionItem(question)
            turn.items.add(item)
            item.view = questionView.addQuestionBubble(question.question, question.options) { answer ->
                answerQuestion(item, answer)
            }
        }
    }

    private fun answerQuestion(item: QuestionItem, answer: String) {
        val turn = questionTurn ?: return
        if (item.answer != null) return
        item.answer = answer
        item.view?.setAnswered(answer)
        val remaining = turn.items.count { it.answer == null }
        if (remaining > 0) {
            callbacks.setStatus("\u2753 $remaining question(s) left")
            return
        }
        questionTurn = null
        val responseText = if (turn.items.size == 1) {
            turn.items.first().answer.orEmpty()
        } else {
            turn.items.joinToString("\n") { questionItem ->
                "${questionItem.question.id}: ${questionItem.answer}"
            }
        }
        callbacks.sendUserMessage(responseText)
    }

    /** Freezes pending question blocks when the user answers by typing in the main input. */
    fun dismissQuestionTurn() {
        val turn = questionTurn ?: return
        questionTurn = null
        turn.items
            .filter { it.answer == null }
            .forEach { it.view?.setDismissed() }
    }
}
