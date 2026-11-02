package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.BadgeEarnedEvent
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.UserBadge
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import com.jankowski.rafal.dancebook.repository.UserBadgeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Service
class BadgeServiceImpl(
    private val userBadgeRepository: UserBadgeRepository,
    private val trainingRecordRepository: TrainingRecordRepository,
    private val assistantPreferencesService: AssistantPreferencesService,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock
) : BadgeService {

    companion object {
        private val STREAK_THRESHOLDS = listOf(
            BadgeType.STREAK_5 to 5,
            BadgeType.STREAK_10 to 10,
            BadgeType.STREAK_20 to 20
        )

        private val HOURS_THRESHOLDS = listOf(
            BadgeType.HOURS_50 to 50,
            BadgeType.HOURS_100 to 100,
            BadgeType.HOURS_250 to 250
        )

        private const val STYLE_BALANCE_MIN_SESSIONS = 4
        private const val STYLE_BALANCE_WINDOW_DAYS = 30L
        private const val STYLE_BALANCE_MIN_PCT = 40L
        private const val STYLE_BALANCE_MAX_PCT = 60L
    }

    @Transactional
    override fun evaluateForUser(user: AppUser, triggerEventId: UUID?): List<UserBadge> {
        if (!assistantPreferencesService.forUser(user).showStreaksAndBadges) {
            return emptyList()
        }

        val existing = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)
        val earnedKeys = existing.map { it.badgeType to it.monthKey }.toMutableSet()
        val records = trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(user)
        if (records.isEmpty()) {
            return emptyList()
        }

        val newlyEarned = mutableListOf<UserBadge>()
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()

        fun award(type: BadgeType, monthKey: String = "", eventId: UUID?) {
            val key = type to monthKey
            if (key in earnedKeys) return
            val saved = userBadgeRepository.save(
                UserBadge().apply {
                    this.user = user
                    this.badgeType = type
                    this.monthKey = monthKey
                    this.trainingEventId = eventId
                    this.earnedAt = now
                }
            )
            earnedKeys.add(key)
            newlyEarned.add(saved)
            eventPublisher.publishEvent(BadgeEarnedEvent(saved, user))
        }

        // 1-3: Streak badges (5, 10, 20 consecutive attended sessions from newest confirmed record)
        val currentStreak = TrainingStatsService.currentStreak(records)
        val latestAttendedEventId = triggerEventId
            ?: records.firstOrNull { it.outcome == TrainingOutcome.ATTENDED }?.trainingEventId
        for ((badgeType, threshold) in STREAK_THRESHOLDS) {
            if (currentStreak >= threshold) {
                award(badgeType, eventId = latestAttendedEventId)
            }
        }

        // 4-6: Hours badges (50h, 100h, 250h total attended duration)
        val totalAttendedMinutes = TrainingStatsService.totalAttendedMinutes(records)
        for ((badgeType, hoursThreshold) in HOURS_THRESHOLDS) {
            if (totalAttendedMinutes >= hoursThreshold * 60L) {
                award(badgeType, eventId = latestAttendedEventId)
            }
        }

        // 7: Perfect month (a finished calendar month with >= 1 attended and 0 skipped sessions)
        val currentMonth = YearMonth.from(today)
        val recordsByMonth = records.groupBy { YearMonth.from(it.occurredAt) }
        recordsByMonth.entries
            .filter { (ym, _) -> ym.isBefore(currentMonth) }
            .sortedBy { (ym, _) -> ym }
            .forEach { (ym, monthRecords) ->
                val attendedInMonth = monthRecords.filter { it.outcome == TrainingOutcome.ATTENDED }
                val skippedCount = monthRecords.count { it.outcome == TrainingOutcome.SKIPPED }
                if (attendedInMonth.isNotEmpty() && skippedCount == 0) {
                    val monthEventId = if (triggerEventId != null && attendedInMonth.any { it.trainingEventId == triggerEventId }) {
                        triggerEventId
                    } else {
                        attendedInMonth.maxByOrNull { it.occurredAt }?.trainingEventId ?: triggerEventId
                    }
                    award(BadgeType.PERFECT_MONTH, monthKey = ym.toString(), eventId = monthEventId)
                }
            }

        // 8: Style balance (last 30 days, >= 4 attended sessions, Standard & Latin each 40%..60% of combined)
        val cutoff = today.minusDays(STYLE_BALANCE_WINDOW_DAYS)
        val windowAttended = records.filter { record ->
            val date = record.occurredAt.toLocalDate()
            record.outcome == TrainingOutcome.ATTENDED && !date.isBefore(cutoff) && !date.isAfter(today)
        }
        if (windowAttended.size >= STYLE_BALANCE_MIN_SESSIONS) {
            var standardMinutes = 0L
            var latinMinutes = 0L
            for (record in windowAttended) {
                for (segment in record.segments) {
                    val catName = segment.danceCategory?.name ?: segment.categoryName
                    if (catName.equals("Standard", ignoreCase = true)) {
                        standardMinutes += segment.durationMinutes.toLong()
                    } else if (catName.equals("Latin", ignoreCase = true)) {
                        latinMinutes += segment.durationMinutes.toLong()
                    }
                }
            }
            val combined = standardMinutes + latinMinutes
            if (combined > 0L &&
                standardMinutes * 100L in (combined * STYLE_BALANCE_MIN_PCT)..(combined * STYLE_BALANCE_MAX_PCT) &&
                latinMinutes * 100L in (combined * STYLE_BALANCE_MIN_PCT)..(combined * STYLE_BALANCE_MAX_PCT)
            ) {
                val balanceEventId = if (triggerEventId != null && windowAttended.any { it.trainingEventId == triggerEventId }) {
                    triggerEventId
                } else {
                    windowAttended.firstOrNull()?.trainingEventId ?: triggerEventId
                }
                award(BadgeType.STYLE_BALANCE, eventId = balanceEventId)
            }
        }

        return newlyEarned
    }

    @Transactional(readOnly = true)
    override fun profileSummaryForUser(user: AppUser, locale: Locale): BadgeProfileSummary {
        val earned = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)
        val earnedByType = earned.groupBy { it.badgeType }

        val earnedCards = earnedByType.entries
            .sortedWith(
                compareByDescending<Map.Entry<BadgeType, List<UserBadge>>> { (_, instances) ->
                    instances.maxOf { it.earnedAt }
                }.thenBy { (type, _) -> type.ordinal }
            )
            .map { (type, instances) ->
                if (type == BadgeType.PERFECT_MONTH) {
                    val latest = instances.maxByOrNull { it.monthKey.ifBlank { it.earnedAt.toString() } }!!
                    val monthLabel = formatMonthKey(latest.monthKey, latest.earnedAt, locale)
                    ProfileEarnedBadgeCard(
                        badgeType = type,
                        dateOrMonthArg = monthLabel,
                        repeatCount = instances.size,
                        isMonthBadge = true
                    )
                } else {
                    val first = instances.first()
                    ProfileEarnedBadgeCard(
                        badgeType = type,
                        dateOrMonthArg = formatDayMonth(first.earnedAt, locale),
                        repeatCount = 1,
                        isMonthBadge = false
                    )
                }
            }

        val streakThresholdMap = STREAK_THRESHOLDS.toMap()
        val hoursThresholdMap = HOURS_THRESHOLDS.toMap()
        val needsHistoryForProgress = streakThresholdMap.keys.any { it !in earnedByType } ||
            hoursThresholdMap.keys.any { it !in earnedByType }
        val records = if (needsHistoryForProgress) {
            trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(user)
        } else {
            emptyList()
        }
        val currentStreak = TrainingStatsService.currentStreak(records)
        val totalAttendedHours = TrainingStatsService.totalAttendedHours(records)

        val nextTargets = BadgeType.entries
            .filter { it !in earnedByType }
            .map { type ->
                when {
                    type in streakThresholdMap -> {
                        val target = streakThresholdMap.getValue(type)
                        ProfileNextBadgeTarget(
                            badgeType = type,
                            current = minOf(currentStreak, target),
                            target = target,
                            isHours = false
                        )
                    }
                    type in hoursThresholdMap -> {
                        val target = hoursThresholdMap.getValue(type)
                        ProfileNextBadgeTarget(
                            badgeType = type,
                            current = minOf(totalAttendedHours, target),
                            target = target,
                            isHours = true
                        )
                    }
                    else -> ProfileNextBadgeTarget(
                        badgeType = type,
                        current = 0,
                        target = 0,
                        isHours = false
                    )
                }
            }

        return BadgeProfileSummary(
            earnedCount = earnedByType.keys.size,
            totalCount = BadgeType.entries.size,
            earnedCards = earnedCards,
            nextTargets = nextTargets,
            latestBadgeType = earnedCards.firstOrNull()?.badgeType
        )
    }

    @Transactional(readOnly = true)
    override fun timelineBadgesForUser(user: AppUser): List<TimelineBadgeChip> {
        val earned = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)
        val earnedByType = earned.groupBy { it.badgeType }
        return BadgeType.entries.mapNotNull { type ->
            val instances = earnedByType[type] ?: return@mapNotNull null
            TimelineBadgeChip(
                badgeType = type,
                repeatCount = if (type == BadgeType.PERFECT_MONTH) instances.size else 1
            )
        }
    }

    @Transactional(readOnly = true)
    override fun badgesByEventForUser(user: AppUser, eventIds: Collection<UUID>): Map<UUID, List<BadgeType>> {
        if (eventIds.isEmpty()) return emptyMap()
        val badges = userBadgeRepository.findAllByUserAndTrainingEventIdIn(user, eventIds)
        return badges
            .filter { it.trainingEventId != null }
            .groupBy { it.trainingEventId!! }
            .mapValues { (_, list) ->
                list.map { it.badgeType }.distinct().sortedBy { it.ordinal }
            }
    }

    private fun formatDayMonth(dateTime: LocalDateTime, locale: Locale): String {
        val formatter = DateTimeFormatter.ofPattern("d MMM", locale)
        return dateTime.format(formatter).trimEnd('.')
    }

    private fun formatMonthKey(monthKey: String, fallbackDate: LocalDateTime, locale: Locale): String {
        val ym = runCatching { YearMonth.parse(monthKey) }.getOrNull() ?: YearMonth.from(fallbackDate)
        return ym.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    }
}
