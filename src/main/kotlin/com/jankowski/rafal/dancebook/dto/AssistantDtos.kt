package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AssistantRole
import java.util.UUID

/** Where the assistant widget was opened. The client sends this pair and nothing else about the page. */
enum class PageContextType { HOME, NOTE, FIGURE, SESSION, CHOREOGRAPHY, OTHER }

data class PageContext(
    val type: PageContextType = PageContextType.OTHER,
    val id: UUID? = null
)

/** A [PageContext] whose name the server has looked up itself. */
data class ResolvedPage(
    val type: PageContextType,
    val id: UUID?,
    val name: String?
) {
    /** The words after "Looking at:". Null when there is nothing worth saying. */
    val label: String?
        get() = name ?: when (type) {
            PageContextType.HOME -> "Home"
            PageContextType.NOTE -> "this note"
            PageContextType.FIGURE -> "this figure"
            PageContextType.SESSION -> "this session"
            PageContextType.CHOREOGRAPHY -> "this choreography"
            PageContextType.OTHER -> null
        }
}

/** One thing a tool found, small enough to give the model and to draw as a linked card. */
data class ResultCard(
    /** `note`, `figure` or `session`. Picks the icon. */
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val snippet: String? = null,
    /** The real page. Always built server-side from the id, never taken from a model or a client. */
    val url: String,
    val chips: List<String> = emptyList()
)

/** What every read tool returns: at most 10 cards, the true total, and a note for the model when something was off. */
data class ToolResult(
    val total: Int,
    val items: List<ResultCard>,
    val message: String? = null,
    /** Set by the draft tools: the id of the draft the turn created, which the reply shows as a card. */
    val draftId: String? = null
)

/** One bubble in the thread. Cards belong to an assistant bubble: what its tools found. */
data class AssistantMessageView(
    val role: AssistantRole,
    val text: String,
    val cards: List<ResultCard> = emptyList(),
    val error: Boolean = false
)

/**
 * The result of one send: the messages to add to the thread. [persisted] is true when the
 * user's message was stored, which is what lets the page clear its input.
 */
data class AssistantTurn(
    val conversationId: UUID?,
    val messages: List<AssistantMessageView>,
    val persisted: Boolean
)

data class ConversationView(
    val id: UUID,
    val title: String,
    val messages: List<AssistantMessageView>
)
