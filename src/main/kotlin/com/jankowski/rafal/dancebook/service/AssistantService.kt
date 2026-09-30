package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AssistantTurn
import com.jankowski.rafal.dancebook.dto.ConversationView
import com.jankowski.rafal.dancebook.dto.PageContext
import java.util.UUID

interface AssistantService {
    companion object {
        const val MAX_TOOL_ROUNDS = 5
        const val HISTORY_LIMIT = 20
        const val ERROR_TEXT = "The assistant couldn't answer just now. Try again."
        const val RATE_LIMIT_TEXT = "You're sending messages too quickly. Wait a moment and try again."
        const val GAVE_UP_TEXT = "I couldn't finish that search. Try asking a narrower question."
    }

    /** Runs one user message to an answer. An id that is not the current user's conversation is a 404. */
    fun send(conversationId: UUID?, text: String, page: PageContext): AssistantTurn

    /** A saved conversation, rebuilt as bubbles. A foreign or missing id is a 404. */
    fun conversation(id: UUID): ConversationView
}
