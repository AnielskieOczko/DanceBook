package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import java.util.UUID

class TrainingTimelineServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingTimelineService: TrainingTimelineServiceImpl
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

        trainingTimelineService = TrainingTimelineServiceImpl(trainingEventRepository, appUserService)
    }

    @Test
    fun `orders upcoming sessions above past ones`() {
        givenWindow(
            event(daysAgo = -7, status = AttendanceStatus.PLANNED),
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 40, status = AttendanceStatus.SKIPPED)
        )

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        val statuses = timeline.months.flatMap { it.entries }.map { it.event.attendanceStatus }
        assertEquals(
            listOf(AttendanceStatus.PLANNED, AttendanceStatus.ATTENDED, AttendanceStatus.SKIPPED),
            statuses
        )
    }

    @Test
    fun `flags the today boundary on the first entry that is not in the future`() {
        givenWindow(
            event(daysAgo = -7, status = AttendanceStatus.PLANNED),
            event(daysAgo = -2, status = AttendanceStatus.PLANNED),
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED)
        )

        val entries = trainingTimelineService.timelineForCurrentUser(page = 0)
            .months.flatMap { it.entries }

        assertEquals(listOf(true, true, false, false), entries.map { it.isFuture })
        assertEquals(listOf(false, false, true, false), entries.map { it.startsTodayBoundary })
    }

    @Test
    fun `marks no boundary when every session is still in the future`() {
        givenWindow(
            event(daysAgo = -7, status = AttendanceStatus.PLANNED),
            event(daysAgo = -2, status = AttendanceStatus.PLANNED)
        )

        val entries = trainingTimelineService.timelineForCurrentUser(page = 0)
            .months.flatMap { it.entries }

        assertTrue(entries.all { it.isFuture })
        assertTrue(entries.none { it.startsTodayBoundary })
    }

    @Test
    fun `marks the boundary on the very first entry when nothing is upcoming`() {
        givenWindow(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED)
        )

        val entries = trainingTimelineService.timelineForCurrentUser(page = 0)
            .months.flatMap { it.entries }

        assertEquals(listOf(true, false), entries.map { it.startsTodayBoundary })
    }

    @Test
    fun `the today boundary is only ever marked on the first window`() {
        givenWindow(
            event(daysAgo = 100, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 130, status = AttendanceStatus.SKIPPED)
        )

        val entries = trainingTimelineService.timelineForCurrentUser(page = 2)
            .months.flatMap { it.entries }

        assertTrue(entries.none { it.startsTodayBoundary })
    }

    @Test
    fun `reports more to load when the window comes back full`() {
        // One more than the page size: the probe row proves there is another window behind it.
        val events = (1L..(TrainingTimelineServiceImpl.PAGE_SIZE + 1))
            .map { event(daysAgo = it, status = AttendanceStatus.ATTENDED) }
        givenWindow(*events.toTypedArray())

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertTrue(timeline.hasMore)
        assertEquals(1, timeline.nextPage)
        // The probe row itself is not drawn.
        assertEquals(
            TrainingTimelineServiceImpl.PAGE_SIZE,
            timeline.months.sumOf { it.entries.size }
        )
    }

    @Test
    fun `reports nothing more to load on a short window`() {
        givenWindow(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.SKIPPED)
        )

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertFalse(timeline.hasMore)
        assertEquals(2, timeline.months.sumOf { it.entries.size })
    }

    @Test
    fun `groups by month with session counts and wall-clock totals`() {
        val now = LocalDateTime.now()
        val sameMonth = now.withDayOfMonth(1).plusDays(1)
        givenWindow(
            eventAt(sameMonth.plusDays(2), AttendanceStatus.ATTENDED, minutes = 90),
            eventAt(sameMonth, AttendanceStatus.ATTENDED, minutes = 30),
            eventAt(sameMonth.minusMonths(1), AttendanceStatus.SKIPPED, minutes = 60)
        )

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertEquals(2, timeline.months.size)
        assertEquals(2, timeline.months[0].sessionCount)
        assertEquals("2 sessions", timeline.months[0].sessionLabel)
        assertEquals(120L, timeline.months[0].totalMinutes)
        assertEquals("2h", timeline.months[0].totalLabel)
        assertEquals("1 session", timeline.months[1].sessionLabel)
    }

    @Test
    fun `counts a session by its wall clock even when its segments are shorter`() {
        // Segments skip warm-ups and breaks, so they may cover less than the session's slot.
        val event = event(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120).apply {
            segments = mutableListOf(
                TrainingEventSegment().apply {
                    durationMinutes = 45
                    sortOrder = 0
                }
            )
        }
        givenWindow(event)

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertEquals(120L, timeline.months.single().totalMinutes)
    }

    @Test
    fun `carries the last month rendered so the next window can continue it`() {
        val now = LocalDateTime.now()
        givenWindow(
            eventAt(now.minusDays(1), AttendanceStatus.ATTENDED),
            eventAt(now.minusMonths(2), AttendanceStatus.ATTENDED)
        )

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertEquals(timeline.months.last().label, timeline.lastMonthLabel)
    }

    @Test
    fun `an empty history has no months and nothing to continue`() {
        givenWindow()

        val timeline = trainingTimelineService.timelineForCurrentUser(page = 0)

        assertTrue(timeline.isEmpty)
        assertFalse(timeline.hasMore)
        assertNull(timeline.lastMonthLabel)
        assertTrue(timeline.isFirstPage)
    }

    @Test
    fun `carries the swatch so the page never re-derives a colour from a status`() {
        givenWindow(event(daysAgo = 1, status = AttendanceStatus.SKIPPED))

        val entry = trainingTimelineService.timelineForCurrentUser(page = 0)
            .months.single().entries.single()

        assertEquals("skipped", entry.swatch.key)
    }

    /**
     * Stubs the two-step fetch: the window query returns the events in the order the database
     * would, and the entity-graph re-read returns the same rows in an arbitrary order, which is
     * what an `IN` query is entitled to do.
     */
    private fun givenWindow(vararg events: TrainingEvent) {
        val ordered = events.sortedByDescending { it.startTime }
        `when`(
            trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(
                any(AppUser::class.java) ?: currentUser,
                any(Pageable::class.java) ?: Pageable.unpaged()
            )
        ).thenReturn(ordered)
        `when`(trainingEventRepository.findAllByIdIn(anyCollection()))
            .thenAnswer { invocation ->
                val ids = invocation.getArgument<Collection<UUID>>(0).toSet()
                ordered.filter { it.id in ids }.shuffled()
            }
    }

    /** @param daysAgo negative values put the session in the future. */
    private fun event(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60
    ): TrainingEvent = eventAt(LocalDateTime.now().minusDays(daysAgo), status, minutes)

    private fun eventAt(
        start: LocalDateTime,
        status: AttendanceStatus,
        minutes: Long = 60
    ): TrainingEvent = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Session"
        startTime = start
        endTime = start.plusMinutes(minutes)
        attendanceStatus = status
        eventType = TrainingEventType.TRAINING
        createdBy = currentUser
    }
}
