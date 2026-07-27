package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuestionTurnCoordinatorTest {

    private val callbacks = FakeChatPanelCallbacks()
    private val coordinator = QuestionTurnCoordinator(callbacks)

    private fun question(
        id: String,
        text: String = "Question $id?",
        options: List<String> = listOf("Yes", "No")
    ) = InteractionQuestion(id = id, question = text, options = options)

    @Test
    fun `presenting questions renders one bubble per question`() {
        coordinator.presentQuestions(listOf(question("q1"), question("q2", options = emptyList())))

        assertEquals(2, callbacks.questionBubbles.size)
        assertEquals("Question q1?", callbacks.questionBubbles[0].question)
        assertEquals(listOf("Yes", "No"), callbacks.questionBubbles[0].options)
        assertTrue(callbacks.questionBubbles[1].options.isEmpty())
    }

    @Test
    fun `empty question list is a no-op`() {
        coordinator.presentQuestions(emptyList())

        assertTrue(callbacks.questionBubbles.isEmpty())
        assertTrue(callbacks.sentUserMessages.isEmpty())
    }

    @Test
    fun `single question answer is sent as-is through the regular send path`() {
        coordinator.presentQuestions(listOf(question("q1")))

        callbacks.questionBubbles.single().onAnswer("Yes")

        assertEquals("Yes", callbacks.questionBubbles.single().answeredWith)
        assertEquals(listOf("Yes"), callbacks.sentUserMessages)
    }

    @Test
    fun `multiple questions compose an id-prefixed answer after the last one`() {
        coordinator.presentQuestions(listOf(question("q1"), question("q2")))

        callbacks.questionBubbles[0].onAnswer("Yes")
        assertTrue(callbacks.sentUserMessages.isEmpty())
        assertTrue(callbacks.statusUpdates.last().contains("1 question(s) left"))

        callbacks.questionBubbles[1].onAnswer("No")
        assertEquals(listOf("q1: Yes\nq2: No"), callbacks.sentUserMessages)
    }

    @Test
    fun `second answer to the same question is ignored`() {
        coordinator.presentQuestions(listOf(question("q1"), question("q2")))

        callbacks.questionBubbles[0].onAnswer("Yes")
        callbacks.questionBubbles[0].onAnswer("No")

        assertEquals("Yes", callbacks.questionBubbles[0].answeredWith)
        assertTrue(callbacks.sentUserMessages.isEmpty())

        callbacks.questionBubbles[1].onAnswer("No")
        assertEquals(listOf("q1: Yes\nq2: No"), callbacks.sentUserMessages)
    }

    @Test
    fun `dismiss freezes unanswered bubbles only`() {
        coordinator.presentQuestions(listOf(question("q1"), question("q2")))

        callbacks.questionBubbles[0].onAnswer("Yes")
        coordinator.dismissQuestionTurn()

        assertFalse(callbacks.questionBubbles[0].dismissed)
        assertTrue(callbacks.questionBubbles[1].dismissed)
        assertTrue(callbacks.sentUserMessages.isEmpty())
    }

    @Test
    fun `answers after dismissal are ignored`() {
        coordinator.presentQuestions(listOf(question("q1")))

        coordinator.dismissQuestionTurn()
        callbacks.questionBubbles.single().onAnswer("Yes")

        assertNull(callbacks.questionBubbles.single().answeredWith)
        assertTrue(callbacks.sentUserMessages.isEmpty())
    }
}
