package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AssistantDraft
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AssistantDraftRepository : JpaRepository<AssistantDraft, UUID> {

    /** The draft, only if its conversation belongs to [ownerId]. */
    @Query("select d from AssistantDraft d where d.id = :id and d.conversation.owner.id = :ownerId")
    fun findOwned(@Param("id") id: UUID, @Param("ownerId") ownerId: UUID): AssistantDraft?

    /** As [findOwned], holding a row lock until the transaction ends, so two Saves cannot both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AssistantDraft d where d.id = :id and d.conversation.owner.id = :ownerId")
    fun findOwnedForUpdate(@Param("id") id: UUID, @Param("ownerId") ownerId: UUID): AssistantDraft?
}
