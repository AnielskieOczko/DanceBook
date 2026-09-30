package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AssistantConversation
import com.jankowski.rafal.dancebook.model.AssistantMessage
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * Every read and write here is scoped to the current user. A conversation that belongs to
 * someone else is the same [EntityNotFoundException] as one that does not exist, so a 404
 * never reveals that it is there.
 */
@Service
class AssistantConversationServiceImpl(
    private val conversationRepository: AssistantConversationRepository,
    private val messageRepository: AssistantMessageRepository,
    private val appUserService: AppUserService
) : AssistantConversationService {

    @Transactional(readOnly = true)
    override fun list(): List<AssistantConversation> =
        conversationRepository.findByOwnerIdOrderByUpdatedAtDesc(appUserService.getCurrentUser().id!!)

    @Transactional(readOnly = true)
    override fun findOwned(id: UUID): AssistantConversation =
        conversationRepository.findByIdAndOwnerId(id, appUserService.getCurrentUser().id!!)
            ?: throw EntityNotFoundException("Conversation not found")

    @Transactional
    override fun start(firstMessage: String): AssistantConversation {
        val conversation = AssistantConversation().apply {
            owner = appUserService.getCurrentUser()
            title = titleFrom(firstMessage)
        }
        return conversationRepository.save(conversation)
    }

    @Transactional(readOnly = true)
    override fun messages(conversationId: UUID): List<AssistantMessage> {
        findOwned(conversationId)
        return messageRepository.findByConversationIdOrderByPositionAsc(conversationId)
    }

    @Transactional(readOnly = true)
    override fun recentContext(conversationId: UUID, limit: Int): List<AssistantMessage> {
        findOwned(conversationId)
        return messageRepository
            .findByConversationIdAndRoleInOrderByPositionDesc(
                conversationId, listOf(AssistantRole.USER, AssistantRole.ASSISTANT), PageRequest.of(0, limit)
            )
            .reversed()
    }

    @Transactional
    override fun append(
        conversationId: UUID,
        role: AssistantRole,
        content: String,
        toolPayload: MutableMap<String, Any?>?
    ): AssistantMessage {
        val conversation = findOwned(conversationId)
        val message = AssistantMessage().apply {
            this.conversation = conversation
            this.position = messageRepository.countByConversationId(conversationId)
            this.role = role
            this.content = content
            this.toolPayload = toolPayload
        }
        conversation.updatedAt = LocalDateTime.now()
        conversationRepository.save(conversation)
        return messageRepository.save(message)
    }

    @Transactional
    override fun rename(id: UUID, title: String): AssistantConversation {
        val conversation = findOwned(id)
        val cleaned = titleFrom(title)
        require(cleaned.isNotEmpty()) { "A conversation needs a name" }
        conversation.title = cleaned
        return conversationRepository.save(conversation)
    }

    @Transactional
    override fun delete(id: UUID) {
        conversationRepository.delete(findOwned(id))
    }

    private fun titleFrom(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(AssistantConversationService.MAX_TITLE_LENGTH)
}
