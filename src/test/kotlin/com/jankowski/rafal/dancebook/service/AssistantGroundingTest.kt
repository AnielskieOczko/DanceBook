package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AssistantGroundingTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var conversationId: UUID
    private lateinit var grounding: AssistantGrounding

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        conversationId = conversations.start("wrap up").id!!
        grounding = AssistantGrounding(conversations, DraftTestSupport.mapper())
    }

    private fun toolResult(vararg cards: Pair<String, UUID>) {
        conversations.append(
            conversationId, AssistantRole.TOOL, "search",
            mutableMapOf(
                "name" to "search",
                "result" to mutableMapOf(
                    "total" to cards.size,
                    "items" to cards.map { (kind, id) ->
                        mutableMapOf<String, Any?>("kind" to kind, "id" to id.toString(), "title" to "t", "url" to "/x", "chips" to emptyList<String>())
                    }
                )
            )
        )
    }

    private fun turn(page: ResolvedPage = ResolvedPage(PageContextType.HOME, null, null)) = ToolTurn(conversationId, page)

    @Test
    fun `ids come only from tool results, sorted by the kind of card that carried them`() {
        val figure = UUID.randomUUID(); val note = UUID.randomUUID(); val session = UUID.randomUUID()
        toolResult("figure" to figure, "note" to note)
        toolResult("session" to session)
        conversations.append(conversationId, AssistantRole.USER, "an id in user text ${UUID.randomUUID()}")

        val grounded = grounding.idsFor(turn())

        assertEquals(setOf(figure), grounded.figures)
        assertEquals(setOf(note), grounded.notes)
        assertEquals(setOf(session), grounded.sessions)
    }

    @Test
    fun `a figure id is not grounded as a session id`() {
        val figure = UUID.randomUUID()
        toolResult("figure" to figure)

        assertTrue(figure !in grounding.idsFor(turn()).sessions)
    }

    @Test
    fun `the page the user is on is grounded as the kind of thing it is`() {
        val session = UUID.randomUUID()

        val grounded = grounding.idsFor(turn(ResolvedPage(PageContextType.SESSION, session, "Standard group class")))

        assertEquals(setOf(session), grounded.sessions)
        assertTrue(grounded.notes.isEmpty() && grounded.figures.isEmpty())
    }

    @Test
    fun `a page the server could not resolve grounds nothing`() {
        val grounded = grounding.idsFor(turn(ResolvedPage(PageContextType.NOTE, UUID.randomUUID(), null)))

        assertTrue(grounded.notes.isEmpty())
    }

    @Test
    fun `an unreadable tool payload is skipped, not fatal`() {
        conversations.append(conversationId, AssistantRole.TOOL, "search", mutableMapOf("name" to "search", "result" to "garbage"))
        val figure = UUID.randomUUID()
        toolResult("figure" to figure)

        assertEquals(setOf(figure), grounding.idsFor(turn()).figures)
    }

    @Test
    fun `the scope hands the running turn to the tools, and only while it runs`() {
        val scope = AssistantTurnScope()
        val t = turn()

        assertNull(scope.current())
        val seen = scope.run(t) { scope.current() }

        assertNotNull(seen)
        assertEquals(t.conversationId, seen!!.conversationId)
        assertNull(scope.current(), "the turn is gone once the block returns")
    }

    @Test
    fun `the scope clears the turn even when the block throws`() {
        val scope = AssistantTurnScope()

        try { scope.run(turn()) { error("boom") } } catch (e: IllegalStateException) { /* expected */ }

        assertNull(scope.current())
    }
}
