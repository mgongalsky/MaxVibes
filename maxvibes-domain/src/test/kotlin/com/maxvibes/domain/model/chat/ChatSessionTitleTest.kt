package com.maxvibes.domain.model.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatSessionTitleTest {

    @Test
    fun `generated title replaces the default one`() {
        val session = ChatSession()

        val renamed = session.withGeneratedTitle("Автозаголовки чатов")

        assertEquals("Автозаголовки чатов", renamed.title)
        assertFalse(renamed.titleSetByUser)
    }

    @Test
    fun `generated title replaces the auto title derived from the first message`() {
        val session = ChatSession()
            .withMessage(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "Привет, я тут подумал, давай сделаем вот такую задачу"
                )
            )

        val renamed = session.withGeneratedTitle("Автозаголовки чатов")

        assertEquals("Автозаголовки чатов", renamed.title)
    }

    @Test
    fun `manual rename locks the title forever`() {
        val session = ChatSession().renamedByUser("Мой чат")

        val renamed = session.withGeneratedTitle("Автозаголовки чатов")

        assertSame(session, renamed)
        assertEquals("Мой чат", renamed.title)
        assertTrue(renamed.titleSetByUser)
    }

    @Test
    fun `withTitle does not lock the title`() {
        val session = ChatSession().withTitle("Промежуточное")

        assertFalse(session.titleSetByUser)
        assertEquals("Автозаголовки", session.withGeneratedTitle("Автозаголовки").title)
    }

    @Test
    fun `blank proposal is a no-op`() {
        val session = ChatSession()

        assertSame(session, session.withGeneratedTitle("   "))
    }

    @Test
    fun `proposal equal to the current title is a no-op`() {
        val session = ChatSession().withTitle("Автозаголовки чатов")

        assertSame(session, session.withGeneratedTitle("  Автозаголовки чатов  "))
    }

    @Test
    fun `only the first line of a proposal is used`() {
        val session = ChatSession()

        val renamed = session.withGeneratedTitle("Автозаголовки чатов\nпояснение от модели")

        assertEquals("Автозаголовки чатов", renamed.title)
    }

    @Test
    fun `long proposal is truncated`() {
        val session = ChatSession()

        val renamed = session.withGeneratedTitle("з".repeat(200))

        assertEquals(ChatSession.MAX_GENERATED_TITLE, renamed.title.length)
    }
}
