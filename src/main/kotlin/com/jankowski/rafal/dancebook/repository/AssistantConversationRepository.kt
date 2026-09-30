package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AssistantConversation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AssistantConversationRepository : JpaRepository<AssistantConversation, UUID> {
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): AssistantConversation?
    fun findByOwnerIdOrderByUpdatedAtDesc(ownerId: UUID): List<AssistantConversation>
}
