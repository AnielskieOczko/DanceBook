package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.model.AssistantRole
import org.springframework.stereotype.Component
import java.util.UUID

/** The ids a draft may name, by what they are. */
data class Grounded(val figures: Set<UUID>, val notes: Set<UUID>, val sessions: Set<UUID>)

/**
 * The grounding rule: a figure, note or session id in a draft must have come from a tool result
 * in this conversation, or be the page the user is looking at (which the server resolved through
 * a service). Anything else is an id the model made up, or took from somewhere it should not.
 * Ids are kept by the kind of card that carried them, so a figure's id cannot stand in for a session's.
 */
@Component
@ConditionalOnAssistant
class AssistantGrounding(
    private val conversations: AssistantConversationService,
    private val objectMapper: ObjectMapper
) {

    fun idsFor(turn: ToolTurn): Grounded {
        val cards = conversations.messages(turn.conversationId)
            .filter { it.role == AssistantRole.TOOL }
            .mapNotNull { it.toolPayload?.get("result") }
            .flatMap { result ->
                try {
                    objectMapper.convertValue(result, ToolResult::class.java).items
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            }

        fun ids(kind: String): MutableSet<UUID> =
            cards.filter { it.kind == kind }.mapNotNull { parse(it.id) }.toMutableSet()

        val figures = ids("figure")
        val notes = ids("note")
        val sessions = ids("session")

        val page = turn.page
        if (page.name != null && page.id != null) {
            when (page.type) {
                PageContextType.FIGURE -> figures += page.id
                PageContextType.NOTE -> notes += page.id
                PageContextType.SESSION -> sessions += page.id
                else -> Unit
            }
        }
        return Grounded(figures, notes, sessions)
    }

    private fun parse(raw: String): UUID? = try { UUID.fromString(raw) } catch (e: IllegalArgumentException) { null }
}
