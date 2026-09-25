package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.ActivityEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import com.jankowski.rafal.dancebook.model.TargetType

interface ActivityEventRepository : JpaRepository<ActivityEvent, UUID>, JpaSpecificationExecutor<ActivityEvent> {

    @Modifying
    @Query("UPDATE ActivityEvent e SET e.targetVisibility = :targetVisibility WHERE e.targetType = :targetType AND e.targetId = :targetId")
    fun updateTargetVisibilityForTarget(
        @Param("targetType") targetType: TargetType,
        @Param("targetId") targetId: UUID,
        @Param("targetVisibility") targetVisibility: String
    ): Int
}
