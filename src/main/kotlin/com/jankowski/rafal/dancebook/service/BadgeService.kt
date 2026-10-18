package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.UserBadge
import org.springframework.context.i18n.LocaleContextHolder
import java.util.Locale
import java.util.UUID

data class ProfileEarnedBadgeCard(
    val badgeType: BadgeType,
    val dateOrMonthArg: String,
    val repeatCount: Int = 1,
    val isMonthBadge: Boolean = false
)

data class ProfileNextBadgeTarget(
    val badgeType: BadgeType,
    val current: Int,
    val target: Int,
    val isHours: Boolean
) {
    val progressPercent: Int
        get() = if (target > 0) ((current * 100) / target).coerceIn(0, 100) else 0
}

data class BadgeProfileSummary(
    val earnedCount: Int,
    val totalCount: Int,
    val earnedCards: List<ProfileEarnedBadgeCard>,
    val nextTargets: List<ProfileNextBadgeTarget>,
    val latestBadgeType: BadgeType? = earnedCards.firstOrNull()?.badgeType
) {
    companion object {
        fun empty() = BadgeProfileSummary(
            earnedCount = 0,
            totalCount = BadgeType.entries.size,
            earnedCards = emptyList(),
            nextTargets = listOf(
                ProfileNextBadgeTarget(BadgeType.STREAK_5, 0, 5, isHours = false),
                ProfileNextBadgeTarget(BadgeType.STREAK_10, 0, 10, isHours = false),
                ProfileNextBadgeTarget(BadgeType.STREAK_20, 0, 20, isHours = false),
                ProfileNextBadgeTarget(BadgeType.HOURS_50, 0, 50, isHours = true),
                ProfileNextBadgeTarget(BadgeType.HOURS_100, 0, 100, isHours = true),
                ProfileNextBadgeTarget(BadgeType.HOURS_250, 0, 250, isHours = true),
                ProfileNextBadgeTarget(BadgeType.PERFECT_MONTH, 0, 0, isHours = false),
                ProfileNextBadgeTarget(BadgeType.STYLE_BALANCE, 0, 0, isHours = false)
            ),
            latestBadgeType = null
        )
    }
}

data class TimelineBadgeChip(
    val badgeType: BadgeType,
    val repeatCount: Int = 1
)

interface BadgeService {
    fun evaluateForUser(user: AppUser, triggerEventId: UUID? = null): List<UserBadge>
    fun profileSummaryForUser(user: AppUser, locale: Locale = LocaleContextHolder.getLocale()): BadgeProfileSummary
    fun timelineBadgesForUser(user: AppUser): List<TimelineBadgeChip>
    fun badgesByEventForUser(user: AppUser, eventIds: Collection<UUID>): Map<UUID, List<BadgeType>>
}
