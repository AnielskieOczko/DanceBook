package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.BadgeEarnedEvent
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.model.UserBadge
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import com.jankowski.rafal.dancebook.repository.UserBadgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class BadgeServiceTest {

    private lateinit var userBadgeRepository: UserBadgeRepository
    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var assistantPreferencesService: AssistantPreferencesService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var badgeService: BadgeServiceImpl
    private lateinit var user: AppUser

    // Fixed clock at 2026-10-25T12:00:00Z
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("UTC"))
    private val now = LocalDateTime.ofInstant(fixedClock.instant(), fixedClock.zone)

    @BeforeEach
    fun setUp() {
        userBadgeRepository = mock(UserBadgeRepository::class.java)
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        assistantPreferencesService = mock(AssistantPreferencesService::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)

        user = AppUser().apply {
            id = UUID.randomUUID()
            username = "dancer"
            displayName = "Dancer"
        }

        `when`(assistantPreferencesService.forUser(user))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = true))
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user))
            .thenReturn(emptyList())
        `when`(userBadgeRepository.save(any(UserBadge::class.java)))
            .thenAnswer { invocation ->
                val b = invocation.getArgument<UserBadge>(0)
                if (b.id == null) b.id = UUID.randomUUID()
                b
            }

        badgeService = BadgeServiceImpl(
            userBadgeRepository = userBadgeRepository,
            trainingRecordRepository = trainingRecordRepository,
            assistantPreferencesService = assistantPreferencesService,
            eventPublisher = eventPublisher,
            clock = fixedClock
        )
    }

    private fun record(
        daysAgo: Long,
        outcome: TrainingOutcome = TrainingOutcome.ATTENDED,
        minutes: Int = 60,
        segments: List<Pair<String, Int>> = emptyList(),
        eventId: UUID = UUID.randomUUID()
    ): TrainingRecord = recordAt(now.minusDays(daysAgo), outcome, minutes, segments, eventId)

    private fun recordAt(
        occurredAt: LocalDateTime,
        outcome: TrainingOutcome = TrainingOutcome.ATTENDED,
        minutes: Int = 60,
        segments: List<Pair<String, Int>> = emptyList(),
        eventId: UUID = UUID.randomUUID()
    ): TrainingRecord = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = eventId
        this.occurredAt = occurredAt
        durationMinutes = minutes
        this.outcome = outcome
        title = "Practice"
        eventType = TrainingEventType.TRAINING
        createdBy = user
        this.segments = segments.mapIndexed { idx, (name, segMinutes) ->
            TrainingRecordSegment().apply {
                danceCategory = DanceCategory().apply { this.name = name }
                categoryName = name
                durationMinutes = segMinutes
                sortOrder = idx
            }
        }.toMutableList()
    }

    private fun givenRecords(vararg records: TrainingRecord) {
        `when`(trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(user))
            .thenReturn(records.sortedByDescending { it.occurredAt })
    }

    @Test
    fun `streak badges award at 5, 10, and 20 consecutive attended sessions`() {
        // 4 consecutive attended sessions -> below threshold
        givenRecords(*(1L..4L).map { record(daysAgo = it) }.toTypedArray())
        assertTrue(badgeService.evaluateForUser(user).isEmpty())

        // 5 consecutive attended sessions -> STREAK_5
        val triggerId = UUID.randomUUID()
        val five = listOf(record(daysAgo = 1, eventId = triggerId)) + (2L..5L).map { record(daysAgo = it) }
        givenRecords(*five.toTypedArray())
        val earnedAt5 = badgeService.evaluateForUser(user, triggerId)
        assertEquals(listOf(BadgeType.STREAK_5), earnedAt5.map { it.badgeType })
        assertEquals(triggerId, earnedAt5.single().trainingEventId)

        // 10 consecutive attended sessions with STREAK_5 already earned -> only STREAK_10 is newly awarded
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earnedAt5)
        givenRecords(*(1L..10L).map { record(daysAgo = it) }.toTypedArray())
        val earnedAt10 = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.STREAK_10), earnedAt10.map { it.badgeType })

        // 20 consecutive attended sessions with STREAK_5 and STREAK_10 already earned -> STREAK_20
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earnedAt5 + earnedAt10)
        givenRecords(*(1L..20L).map { record(daysAgo = it) }.toTypedArray())
        val earnedAt20 = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.STREAK_20), earnedAt20.map { it.badgeType })
    }

    @Test
    fun `skipped session breaks streak below 5 while keeping previously earned streak badge`() {
        val existingBadge = UserBadge().apply {
            id = UUID.randomUUID()
            this.user = this@BadgeServiceTest.user
            badgeType = BadgeType.STREAK_5
            monthKey = ""
        }
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(listOf(existingBadge))

        // Newest session is SKIPPED after 5 attended sessions -> current streak is 0, existing badge is untouched
        val records = listOf(record(daysAgo = 1, outcome = TrainingOutcome.SKIPPED)) +
            (2L..6L).map { record(daysAgo = it, outcome = TrainingOutcome.ATTENDED) }
        givenRecords(*records.toTypedArray())

        val newlyEarned = badgeService.evaluateForUser(user)
        assertTrue(newlyEarned.isEmpty())
        verify(userBadgeRepository, never()).delete(any())

        val summary = badgeService.profileSummaryForUser(user, Locale.ENGLISH)
        assertEquals(1, summary.earnedCount)
        assertEquals(BadgeType.STREAK_5, summary.earnedCards.single().badgeType)
    }

    @Test
    fun `hours badges award at 50h, 100h, and 250h of attended training`() {
        // 49h (2940 min) attended + 120 min skipped -> below 50h
        givenRecords(
            record(daysAgo = 1, minutes = 2940, outcome = TrainingOutcome.ATTENDED),
            record(daysAgo = 2, minutes = 120, outcome = TrainingOutcome.SKIPPED)
        )
        assertTrue(badgeService.evaluateForUser(user).isEmpty())

        // 50h (3000 min) attended -> HOURS_50
        givenRecords(record(daysAgo = 1, minutes = 3000, outcome = TrainingOutcome.ATTENDED))
        val earned50 = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.HOURS_50), earned50.map { it.badgeType })

        // 100h (6000 min) attended -> HOURS_100
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earned50)
        givenRecords(record(daysAgo = 1, minutes = 6000, outcome = TrainingOutcome.ATTENDED))
        val earned100 = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.HOURS_100), earned100.map { it.badgeType })

        // 250h (15000 min) attended -> HOURS_250
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earned50 + earned100)
        givenRecords(record(daysAgo = 1, minutes = 15000, outcome = TrainingOutcome.ATTENDED))
        val earned250 = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.HOURS_250), earned250.map { it.badgeType })
    }

    @Test
    fun `perfect month awards once per finished calendar month with attended and no skipped sessions`() {
        // Current month is 2026-10; September (2026-09) and August (2026-08) are finished.
        // July (2026-07) had a skipped session so it does not qualify.
        val octAttended = recordAt(LocalDateTime.of(2026, 10, 5, 18, 0), TrainingOutcome.ATTENDED)
        val sepAttended = recordAt(LocalDateTime.of(2026, 9, 20, 18, 0), TrainingOutcome.ATTENDED)
        val augAttended = recordAt(LocalDateTime.of(2026, 8, 12, 18, 0), TrainingOutcome.ATTENDED)
        val julAttended = recordAt(LocalDateTime.of(2026, 7, 10, 18, 0), TrainingOutcome.ATTENDED)
        val julSkipped = recordAt(LocalDateTime.of(2026, 7, 15, 18, 0), TrainingOutcome.SKIPPED)

        givenRecords(octAttended, sepAttended, augAttended, julAttended, julSkipped)

        val earned = badgeService.evaluateForUser(user)
        assertEquals(2, earned.size)
        assertTrue(earned.all { it.badgeType == BadgeType.PERFECT_MONTH })
        assertEquals(listOf("2026-08", "2026-09"), earned.map { it.monthKey })

        // Re-evaluating with those two already stored awards nothing new
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earned)
        assertTrue(badgeService.evaluateForUser(user).isEmpty())

        // Profile summary groups both PERFECT_MONTH awards into a single counting row (x2)
        val summaryEn = badgeService.profileSummaryForUser(user, Locale.ENGLISH)
        assertEquals(1, summaryEn.earnedCount)
        assertEquals(8, summaryEn.totalCount)
        val cardEn = summaryEn.earnedCards.single()
        assertEquals(BadgeType.PERFECT_MONTH, cardEn.badgeType)
        assertEquals(2, cardEn.repeatCount)
        assertTrue(cardEn.isMonthBadge)
        assertEquals("September", cardEn.dateOrMonthArg)
    }

    @Test
    fun `style balance requires at least 4 attended sessions in last 30 days and 40 to 60 percent split`() {
        // Only 3 attended sessions with 50/50 split -> below 4-session minimum
        givenRecords(
            record(daysAgo = 2, segments = listOf("Standard" to 30, "Latin" to 30)),
            record(daysAgo = 5, segments = listOf("Standard" to 30, "Latin" to 30)),
            record(daysAgo = 8, segments = listOf("Standard" to 30, "Latin" to 30))
        )
        assertTrue(badgeService.evaluateForUser(user).isEmpty())

        // 4 attended sessions, but split is 70% Standard / 30% Latin -> outside 40-60% range
        givenRecords(
            record(daysAgo = 2, segments = listOf("Standard" to 70, "Latin" to 30)),
            record(daysAgo = 5, segments = listOf("Standard" to 70, "Latin" to 30)),
            record(daysAgo = 8, segments = listOf("Standard" to 70, "Latin" to 30)),
            record(daysAgo = 10, segments = listOf("Standard" to 70, "Latin" to 30))
        )
        assertTrue(badgeService.evaluateForUser(user).isEmpty())

        // 4 attended sessions in last 30 days with 40% Standard / 60% Latin -> awards STYLE_BALANCE
        givenRecords(
            record(daysAgo = 2, segments = listOf("Standard" to 40, "Latin" to 60)),
            record(daysAgo = 5, segments = listOf("Standard" to 40, "Latin" to 60)),
            record(daysAgo = 8, segments = listOf("Standard" to 40, "Latin" to 60)),
            record(daysAgo = 10, segments = listOf("Standard" to 40, "Latin" to 60))
        )
        val earned = badgeService.evaluateForUser(user)
        assertEquals(listOf(BadgeType.STYLE_BALANCE), earned.map { it.badgeType })

        val eventCaptor = ArgumentCaptor.forClass(BadgeEarnedEvent::class.java)
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture())
        assertEquals(BadgeType.STYLE_BALANCE, eventCaptor.value.userBadge.badgeType)
        assertEquals(user, eventCaptor.value.actor)
    }

    @Test
    fun `when showStreaksAndBadges is false nothing is evaluated awarded or notified`() {
        `when`(assistantPreferencesService.forUser(user))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = false))
        givenRecords(*(1L..10L).map { record(daysAgo = it, minutes = 600) }.toTypedArray())

        val result = badgeService.evaluateForUser(user)

        assertTrue(result.isEmpty())
        verifyNoInteractions(userBadgeRepository)
        verifyNoInteractions(trainingRecordRepository)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `profile summary orders earned cards newest first and computes remaining targets`() {
        val earned = listOf(
            UserBadge().apply { badgeType = BadgeType.STREAK_5; earnedAt = now.minusDays(10) },
            UserBadge().apply { badgeType = BadgeType.STREAK_10; earnedAt = now.minusDays(2) },
            UserBadge().apply { badgeType = BadgeType.HOURS_50; earnedAt = now.minusDays(5) }
        )
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earned)

        // 12 consecutive sessions totaling 62 hours (3720 minutes)
        val records = (1L..12L).map { record(daysAgo = it, minutes = 310) }
        givenRecords(*records.toTypedArray())

        val summary = badgeService.profileSummaryForUser(user, Locale.ENGLISH)

        assertEquals(3, summary.earnedCount)
        assertEquals(8, summary.totalCount)
        assertEquals(BadgeType.STREAK_10, summary.latestBadgeType)
        assertEquals(
            listOf(BadgeType.STREAK_10, BadgeType.HOURS_50, BadgeType.STREAK_5),
            summary.earnedCards.map { it.badgeType }
        )
        assertEquals(5, summary.nextTargets.size)

        val streakTarget = summary.nextTargets.first { !it.isHours && it.target > 0 }
        assertEquals(BadgeType.STREAK_20, streakTarget.badgeType)
        assertEquals(12, streakTarget.current)
        assertEquals(20, streakTarget.target)
        assertEquals(60, streakTarget.progressPercent)

        val hoursTarget = summary.nextTargets.first { it.isHours }
        assertEquals(BadgeType.HOURS_100, hoursTarget.badgeType)
        assertEquals(62, hoursTarget.current)
        assertEquals(100, hoursTarget.target)
        assertEquals(62, hoursTarget.progressPercent)
    }

    @Test
    fun `profile summary skips history read when all streak and hours badges are already earned`() {
        val earned = listOf(
            BadgeType.STREAK_5,
            BadgeType.STREAK_10,
            BadgeType.STREAK_20,
            BadgeType.HOURS_50,
            BadgeType.HOURS_100,
            BadgeType.HOURS_250
        ).mapIndexed { idx, type ->
            UserBadge().apply { badgeType = type; earnedAt = now.minusDays((10 - idx).toLong()) }
        }
        `when`(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(user)).thenReturn(earned)

        val summary = badgeService.profileSummaryForUser(user, Locale.ENGLISH)

        assertEquals(6, summary.earnedCount)
        assertEquals(listOf(BadgeType.PERFECT_MONTH, BadgeType.STYLE_BALANCE), summary.nextTargets.map { it.badgeType })
        verifyNoInteractions(trainingRecordRepository)
    }
}
