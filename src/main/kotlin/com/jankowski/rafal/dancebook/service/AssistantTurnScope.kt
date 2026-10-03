package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What a tool needs to know about the turn it is running in: the conversation, the page the
 * user is on, and how many drafts each tool has already refused this turn.
 */
class ToolTurn(val conversationId: UUID, val page: ResolvedPage) {
    val failures: MutableMap<String, Int> = mutableMapOf()
}

/**
 * The tools are singletons but a turn is not, and Spring AI calls a tool with its arguments only.
 * The loop runs the tool calls on the request thread, so the turn rides a thread-local for the
 * length of [run]. A tool called with no turn running gets null and must refuse.
 */
@Component
@ConditionalOnAssistant
class AssistantTurnScope {

    private val current = ThreadLocal<ToolTurn?>()

    fun <T> run(turn: ToolTurn, block: () -> T): T {
        val previous = current.get()
        current.set(turn)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun current(): ToolTurn? = current.get()
}
