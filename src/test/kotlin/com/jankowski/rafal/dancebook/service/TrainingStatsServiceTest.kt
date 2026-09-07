package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.formatMinutes
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.LocalDateTime
import java.util.UUID

class TrainingStatsServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingStatsService: TrainingStatsServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingStatsService = TrainingStatsServiceImpl(trainingEventRepository, appUserService)
    }

    /**
     * @param daysAgo negative values put the session in the future.
     */
    private fun event(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): TrainingEvent {
        val start = LocalDateTime.now().minusDays(daysAgo)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = currentUser
            // The back-reference to the parent event is left unset: nothing in the
            // statistics service reads it, and setting it here would only be ceremony.
            this.segments = segments.mapIndexed { index, (sliceCategory, segmentMinutes) ->
                TrainingEventSegment().apply {
                    danceCategory = sliceCategory
                    durationMinutes = segmentMinutes
                    sortOrder = index
                }
            }.toMutableList()
        }
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun given(vararg events: TrainingEvent) {
        `when`(trainingEventRepository.findAllByCreatedBy(currentUser)).thenReturn(events.toList())
    }

    @Test
    fun `hours count attended sessions only`() {
        given(
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 90),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED, minutes = 60),
            event(daysAgo = -2, status = AttendanceStatus.PLANNED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(90L, stats.totalMinutesTrained)
        assertEquals("1h 30m", stats.totalTrainedLabel)
    }

    @Test
    fun `every session lands in exactly one count bucket`() {
        given(
            event(daysAgo = -2, status = AttendanceStatus.PLANNED),
            event(daysAgo = 2, status = AttendanceStatus.PLANNED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED)
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
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED),
            event(daysAgo = 6, status = AttendanceStatus.PLANNED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(75, stats.attendanceRatePercent)
    }

    @Test
    fun `attendance rate is null when nothing has been decided`() {
        given(
            event(daysAgo = -1, status = AttendanceStatus.PLANNED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        assertNull(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `attendance rate rounds half up`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 5, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 6, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 7, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 8, status = AttendanceStatus.SKIPPED)
        )

        // 5/8 = 62.5% exactly, which must round up rather than down.
        assertEquals(63, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `the period excludes sessions that start before its earliest day`() {
        given(
            event(daysAgo = 10, status = AttendanceStatus.ATTENDED, minutes = 60),
            event(daysAgo = 100, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(60L, stats.totalMinutesTrained)
        assertEquals(1, stats.counts.total)
    }

    @Test
    fun `a narrowed period still counts upcoming sessions`() {
        given(event(daysAgo = -5, status = AttendanceStatus.PLANNED))

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
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `cancelled and unconfirmed sessions do not break the streak`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.CANCELLED),
            event(daysAgo = 3, status = AttendanceStatus.PLANNED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `an upcoming session does not break the streak`() {
        given(
            event(daysAgo = -3, status = AttendanceStatus.PLANNED),
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the streak spans full history even when the period is narrowed`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 100, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 200, status = AttendanceStatus.ATTENDED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(1, stats.counts.total)
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `the streak is zero when the most recent decided session was skipped`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(0, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the this-year period excludes sessions from a previous year`() {
        given(
            event(daysAgo = 0, status = AttendanceStatus.ATTENDED, minutes = 60),
            event(daysAgo = 400, status = AttendanceStatus.ATTENDED, minutes = 60)
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
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60, latin to 60)
            ),
            event(
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
            event(
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
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 90)
            ),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60)
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
            event(
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
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60, type = TrainingEventType.TRAINING),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90, type = TrainingEventType.TRAINING),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val byType = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byEventType

        assertEquals(listOf("TRAINING", "CAMP"), byType.map { it.label })
        assertEquals(150L, byType.first().minutes)
        assertEquals(2, byType.first().sessionCount)
        assertEquals(240L, byType.last().minutes)
    }

    @Test
    fun `every slice carries a colour`() {
        val standard = category("Standard")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60)
            )
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        (stats.byCategory + stats.byEventType).forEach { slice ->
            assertTrue(slice.color.startsWith("#"), "slice ${slice.label} has no colour")
        }
    }
}
