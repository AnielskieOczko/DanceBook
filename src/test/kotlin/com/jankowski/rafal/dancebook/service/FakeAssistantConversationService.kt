package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantConversation
import com.jankowski.rafal.dancebook.model.AssistantMessage
import com.jankowski.rafal.dancebook.model.AssistantRole
import jakarta.persistence.EntityNotFoundException
import java.util.UUID

/** An in-memory [AssistantConversationService] for one current user, so loop tests can read back what was stored. */
class FakeAssistantConversationService(private val currentUser: AppUser) : AssistantConversationService {

    val conversations = mutableListOf<AssistantConversation>()
    val stored = mutableListOf<AssistantMessage>()

    override fun list() = conversations.filter { it.owner?.id == currentUser.id }

    override fun findOwned(id: UUID): AssistantConversation =
        conversations.firstOrNull { it.id == id && it.owner?.id == currentUser.id }
            ?: throw EntityNotFoundException("Conversation not found")

    override fun start(firstMessage: String): AssistantConversation {
        val conversation = AssistantConversation().apply {
            id = UUID.randomUUID()
            owner = currentUser
            title = firstMessage.trim().take(AssistantConversationService.MAX_TITLE_LENGTH)
        }
        conversations += conversation
        return conversation
    }

    override fun messages(conversationId: UUID): List<AssistantMessage> {
        findOwned(conversationId)
        return stored.filter { it.conversation?.id == conversationId }.sortedBy { it.position }
    }

    override fun recentContext(conversationId: UUID, limit: Int): List<AssistantMessage> =
        messages(conversationId).filter { it.role != AssistantRole.TOOL }.takeLast(limit)

    override fun append(
        conversationId: UUID, role: AssistantRole, content: String, toolPayload: MutableMap<String, Any?>?
    ): AssistantMessage {
        val conversation = findOwned(conversationId)
        val message = AssistantMessage().apply {
            id = UUID.randomUUID()
            this.conversation = conversation
            position = stored.count { it.conversation?.id == conversationId }
            this.role = role
            this.content = content
            this.toolPayload = toolPayload
        }
        stored += message
        return message
    }

    override fun rename(id: UUID, title: String): AssistantConversation {
        val conversation = findOwned(id)
        require(title.isNotBlank()) { "A conversation needs a name" }
        conversation.title = title.trim().take(AssistantConversationService.MAX_TITLE_LENGTH)
        return conversation
    }

    override fun delete(id: UUID) {
        val conversation = findOwned(id)
        conversations.remove(conversation)
        stored.removeAll { it.conversation?.id == id }
    }
}
