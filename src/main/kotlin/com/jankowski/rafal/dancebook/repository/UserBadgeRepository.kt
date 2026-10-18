package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.UserBadge
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserBadgeRepository : JpaRepository<UserBadge, UUID> {
    fun findAllByUserOrderByEarnedAtAsc(user: AppUser): List<UserBadge>
    fun findAllByUserAndTrainingEventIdIn(user: AppUser, trainingEventIds: Collection<UUID>): List<UserBadge>
    fun existsByUserAndBadgeTypeAndMonthKey(user: AppUser, badgeType: BadgeType, monthKey: String): Boolean
}
