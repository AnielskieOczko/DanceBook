package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AssistantConversation
import com.jankowski.rafal.dancebook.model.AssistantMessage
import com.jankowski.rafal.dancebook.model.AssistantRole
import java.util.UUID

interface AssistantConversationService {
    companion object {
        const val MAX_TITLE_LENGTH = 80
    }

    fun list(): List<AssistantConversation>
    fun findOwned(id: UUID): AssistantConversation
    fun start(firstMessage: String): AssistantConversation
    fun messages(conversationId: UUID): List<AssistantMessage>
    fun recentContext(conversationId: UUID, limit: Int = 20): List<AssistantMessage>
    fun append(
        conversationId: UUID,
        role: AssistantRole,
        content: String,
        toolPayload: MutableMap<String, Any?>? = null
    ): AssistantMessage
    fun rename(id: UUID, title: String): AssistantConversation
    fun delete(id: UUID)
}
