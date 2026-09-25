package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Share
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ShareRepository : JpaRepository<Share, UUID> {
    fun existsByItemTypeAndItemIdAndGranteeUserId(itemType: String, itemId: UUID, granteeUserId: UUID): Boolean
}
