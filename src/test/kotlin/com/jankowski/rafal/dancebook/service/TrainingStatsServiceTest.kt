package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.formatMinutes
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.LocalDateTime
import java.util.UUID

/**
 * The dashboard is deliberately hybrid: history figures come from training records, schedule
 * figures from calendar events. The fixture below mirrors that — a confirmed session produces
 * both an event and its record, exactly as the write path does — so every one of these
 * assertions still describes a real state of the database.
 */
class TrainingStatsServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingStatsService: TrainingStatsServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingStatsService = TrainingStatsServiceImpl(
            trainingEventRepository, trainingRecordRepository, appUserService
        )
    }

    /**
     * One session as the database actually holds it: a calendar row, plus a record when the
     * session has been confirmed. [record] alone models an orphaned record — a session that
     * was deleted after being confirmed.
     */
    private class Session(val event: TrainingEvent?, val record: TrainingRecord?)

    /**
     * @param daysAgo negative values put the session in the future.
     */
    private fun session(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): Session {
        val start = LocalDateTime.now().minusDays(daysAgo)
        val event = TrainingEvent().apply {
            id = UUID.randomUUID()
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = currentUser
        }
        val outcome = TrainingOutcome.from(status)
        val record = outcome?.let { confirmed ->
            recordOf(event.id!!, start, minutes, confirmed, type, segments)
        }
        return Session(event, record)
    }

    /** A record whose session has been deleted: history with no schedule entry behind it. */
    private fun orphanedSession(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): Session {
        val start = LocalDateTime.now().minusDays(daysAgo)
        val outcome = requireNotNull(TrainingOutcome.from(status)) {
            "Only a confirmed session can leave an orphaned record behind"
        }
        val record = recordOf(UUID.randomUUID(), start, minutes, outcome, type, segments).apply {
            orphanedAt = LocalDateTime.now()
        }
        return Session(event = null, record = record)
    }

    private fun recordOf(
        eventId: UUID,
        start: LocalDateTime,
        minutes: Long,
        outcome: TrainingOutcome,
        type: TrainingEventType,
        segments: List<Pair<DanceCategory, Int>>
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = eventId
        occurredAt = start
        durationMinutes = minutes.toInt()
        this.outcome = outcome
        title = "Session"
        eventType = type
        createdBy = currentUser
        // The back-reference to the parent record is left unset: nothing in the statistics
        // service reads it, and setting it here would only be ceremony.
        this.segments = segments.mapIndexed { index, (sliceCategory, sliceMinutes) ->
            TrainingRecordSegment().apply {
                danceCategory = sliceCategory
                categoryName = sliceCategory.name
                durationMinutes = sliceMinutes
                sortOrder = index
            }
        }.toMutableList()
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun given(vararg sessions: Session) {
        `when`(trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(currentUser))
            .thenReturn(sessions.mapNotNull { it.event })
        `when`(trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser))
            .thenReturn(sessions.mapNotNull { it.record }.sortedByDescending { it.occurredAt })
    }

    @Test
    fun `hours count attended sessions only`() {
        given(
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 90),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED, minutes = 60),
            session(daysAgo = -2, status = AttendanceStatus.PLANNED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(90L, stats.totalMinutesTrained)
        assertEquals("1h 30m", stats.totalTrainedLabel)
    }

    @Test
    fun `every session lands in exactly one count bucket`() {
        given(
            session(daysAgo = -2, status = AttendanceStatus.PLANNED),
            session(daysAgo = 2, status = AttendanceStatus.PLANNED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        val counts = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).counts

        assertEquals(1, counts.upcoming)
        assertEquals(1, counts.unconfirmed)
        assertEquals(1, counts.attended)
        assertEquals(1, counts.skipped)
        assertEquals(1, counts.cancelled)
        assertEquals(5, counts.total)
    }

    @Test
    fun `attendance rate ignores cancelled and unconfirmed sessions`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED),
            session(daysAgo = 6, status = AttendanceStatus.PLANNED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(75, stats.attendanceRatePercent)
    }

    @Test
    fun `attendance rate is null when nothing has been decided`() {
        given(
            session(daysAgo = -1, status = AttendanceStatus.PLANNED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        assertNull(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `attendance rate rounds half up`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 5, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 6, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 7, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 8, status = AttendanceStatus.SKIPPED)
        )

        // 5/8 = 62.5% exactly, which must round up rather than down.
        assertEquals(63, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `the period excludes sessions that start before its earliest day`() {
        given(
            session(daysAgo = 10, status = AttendanceStatus.ATTENDED, minutes = 60),
            session(daysAgo = 100, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(60L, stats.totalMinutesTrained)
        assertEquals(1, stats.counts.total)
    }

    @Test
    fun `a narrowed period still counts upcoming sessions`() {
        given(session(daysAgo = -5, status = AttendanceStatus.PLANNED))

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS).counts.upcoming)
    }

    @Test
    fun `durations format as hours and minutes`() {
        assertEquals("45m", formatMinutes(45))
        assertEquals("2h", formatMinutes(120))
        assertEquals("3h 15m", formatMinutes(195))
        assertEquals("0m", formatMinutes(0))
    }

    @Test
    fun `the streak counts attended sessions back to the first skip`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `cancelled and unconfirmed sessions do not break the streak`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.CANCELLED),
            session(daysAgo = 3, status = AttendanceStatus.PLANNED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `an upcoming session does not break the streak`() {
        given(
            session(daysAgo = -3, status = AttendanceStatus.PLANNED),
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the streak spans full history even when the period is narrowed`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 100, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 200, status = AttendanceStatus.ATTENDED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(1, stats.counts.total)
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `the streak is zero when the most recent decided session was skipped`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(0, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the this-year period excludes sessions from a previous year`() {
        given(
            session(daysAgo = 0, status = AttendanceStatus.ATTENDED, minutes = 60),
            session(daysAgo = 400, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_YEAR)

        assertEquals(1, stats.counts.total)
        assertEquals(60L, stats.totalMinutesTrained)
    }

    @Test
    fun `category minutes come from segments of attended sessions`() {
        val standard = category("Standard")
        val latin = category("Latin")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60, latin to 60)
            ),
            session(
                daysAgo = 2, status = AttendanceStatus.SKIPPED, minutes = 120,
                segments = listOf(standard to 120)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Latin", "Standard"), byCategory.map { it.label })
        assertEquals(60L, byCategory.first { it.label == "Standard" }.minutes)
        assertEquals(60L, byCategory.first { it.label == "Latin" }.minutes)
    }

    @Test
    fun `a session touching two categories counts once in each session count`() {
        val standard = category("Standard")
        val latin = category("Latin")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 30, standard to 30, latin to 60)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(1, byCategory.first { it.label == "Standard" }.sessionCount)
        assertEquals(60L, byCategory.first { it.label == "Standard" }.minutes)
        assertEquals(1, byCategory.first { it.label == "Latin" }.sessionCount)
    }

    @Test
    fun `time not covered by segments becomes an unassigned slice`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 90)
            ),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        val unassigned = byCategory.last()
        assertEquals("Unassigned", unassigned.label)
        assertEquals(90L, unassigned.minutes)
        assertEquals(180L, byCategory.sumOf { it.minutes })
    }

    @Test
    fun `no unassigned slice when segments fill every attended session`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Standard"), byCategory.map { it.label })
    }

    @Test
    fun `event types are broken down by wall-clock minutes and session count`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60, type = TrainingEventType.TRAINING),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90, type = TrainingEventType.TRAINING),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val byType = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byEventType

        assertEquals(listOf("TRAINING", "CAMP"), byType.map { it.label })
        assertEquals(150L, byType.first().minutes)
        assertEquals(2, byType.first().sessionCount)
        assertEquals(240L, byType.last().minutes)
    }

    @Test
    fun `every slice carries a colour, and the unassigned slice always the palette's unassigned colour`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60)
            )
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        (stats.byCategory + stats.byEventType).forEach { slice ->
            assertTrue(slice.color.startsWith("#"), "slice ${slice.label} has no colour")
        }
        val unassigned = stats.byCategory.first { it.label == "Unassigned" }
        assertEquals(TrainingEventPalette.UNASSIGNED_COLOR, unassigned.color)
    }

    @Test
    fun `event type minutes reconcile with the total minutes trained`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 90,
                type = TrainingEventType.TRAINING, segments = listOf(standard to 60)
            ),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            session(daysAgo = 3, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(stats.totalMinutesTrained, stats.byEventType.sumOf { it.minutes })
    }

    @Test
    fun `deleting a confirmed session leaves its hours and its streak standing`() {
        val standard = category("Standard")
        given(
            orphanedSession(
                daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90,
                segments = listOf(standard to 90)
            ),
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(150L, stats.totalMinutesTrained)
        assertEquals(2, stats.counts.attended)
        assertEquals(2, stats.currentStreak)
        assertEquals(90L, stats.byCategory.first { it.label == "Standard" }.minutes)
    }

    @Test
    fun `renaming a category relabels its history instead of splitting it in two`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            ),
            session(
                daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            )
        )
        // Renamed after the older session was recorded: the slices must still be one style.
        standard.name = "Ballroom"

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Ballroom"), byCategory.map { it.label })
        assertEquals(120L, byCategory.single().minutes)
        assertEquals(2, byCategory.single().sessionCount)
    }

    @Test
    fun `a deleted category keeps the name it had when the session was recorded`() {
        val standard = category("Standard")
        val session = session(
            daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
            segments = listOf(standard to 60)
        )
        // Deleting the category nulls the link, which is what ON DELETE SET NULL leaves behind.
        session.record!!.segments.forEach { it.danceCategory = null }
        given(session)

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Standard"), byCategory.map { it.label })
        assertEquals(60L, byCategory.single().minutes)
    }
}
