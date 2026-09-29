package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AssistantMessage
import com.jankowski.rafal.dancebook.model.AssistantRole
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AssistantMessageRepository : JpaRepository<AssistantMessage, UUID> {
    fun findByConversationIdOrderByPositionAsc(conversationId: UUID): List<AssistantMessage>
    fun findByConversationIdAndRoleInOrderByPositionDesc(
        conversationId: UUID, roles: Collection<AssistantRole>, pageable: Pageable
    ): List<AssistantMessage>
    fun countByConversationId(conversationId: UUID): Int
}
