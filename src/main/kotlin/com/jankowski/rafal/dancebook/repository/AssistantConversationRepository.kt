package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AssistantConversation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AssistantConversationRepository : JpaRepository<AssistantConversation, UUID> {
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): AssistantConversation?
    fun findByOwnerIdOrderByUpdatedAtDesc(ownerId: UUID): List<AssistantConversation>
    fun findByOwnerIdAndEphemeralFalseOrderByUpdatedAtDesc(ownerId: UUID): List<AssistantConversation>

    @Modifying(clearAutomatically = true)
    @Query("delete from AssistantConversation c where c.ephemeral = true and c.owner.id = :ownerId")
    fun deleteByEphemeralTrueAndOwnerId(@Param("ownerId") ownerId: UUID)

    @Modifying(clearAutomatically = true)
    @Query("delete from AssistantConversation c where c.ephemeral = true and c.sessionId = :sessionId")
    fun deleteByEphemeralTrueAndSessionId(@Param("sessionId") sessionId: String)

    @Modifying(clearAutomatically = true)
    @Query("delete from AssistantConversation c where c.ephemeral = true")
    fun deleteByEphemeralTrue()
}
