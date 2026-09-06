package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.LocalDateTime
import java.util.UUID

class TrainingCalendarApiControllerTest {

    private lateinit var trainingEventService: TrainingEventService
    private lateinit var controller: TrainingCalendarApiController

    @BeforeEach
    fun setUp() {
        trainingEventService = mock(TrainingEventService::class.java)
        controller = TrainingCalendarApiController(trainingEventService)
    }

    @Test
    fun `should accept the window as an offset ISO string and strip the offset`() {
        `when`(trainingEventService.findInRange(any(LocalDateTime::class.java), any(LocalDateTime::class.java)))
            .thenReturn(emptyList())

        controller.calendarFeed("2026-09-01T00:00:00+02:00", "2026-10-01T00:00:00+02:00")

        // The offset is dropped rather than converted: the app stores LocalDateTime and
        // the calendar renders in local time, so converting here would shift the window.
        verify(trainingEventService).findInRange(
            LocalDateTime.of(2026, 9, 1, 0, 0),
            LocalDateTime.of(2026, 10, 1, 0, 0)
        )
    }

    @Test
    fun `should accept the window as a plain local ISO string`() {
        `when`(trainingEventService.findInRange(any(LocalDateTime::class.java), any(LocalDateTime::class.java)))
            .thenReturn(emptyList())

        controller.calendarFeed("2026-09-01T00:00:00", "2026-10-01T00:00:00")

        verify(trainingEventService).findInRange(
            LocalDateTime.of(2026, 9, 1, 0, 0),
            LocalDateTime.of(2026, 10, 1, 0, 0)
        )
    }

    @Test
    fun `should emit times without an offset so the calendar does not shift them`() {
        `when`(trainingEventService.findInRange(any(LocalDateTime::class.java), any(LocalDateTime::class.java)))
            .thenReturn(listOf(event(AttendanceStatus.PLANNED, LocalDateTime.of(2026, 9, 14, 18, 0))))

        val result = controller.calendarFeed("2026-09-01T00:00:00", "2026-10-01T00:00:00")

        assertEquals("2026-09-14T18:00", result[0].start)
        assertFalse(result[0].start.contains("+"))
        assertFalse(result[0].start.endsWith("Z"))
    }

    @Test
    fun `should colour each attendance state distinctly`() {
        val past = LocalDateTime.now().minusDays(3)
        val future = LocalDateTime.now().plusDays(3)
        `when`(trainingEventService.findInRange(any(LocalDateTime::class.java), any(LocalDateTime::class.java)))
            .thenReturn(
                listOf(
                    event(AttendanceStatus.PLANNED, future),
                    event(AttendanceStatus.PLANNED, past),      // past and unconfirmed
                    event(AttendanceStatus.ATTENDED, past),
                    event(AttendanceStatus.SKIPPED, past),
                    event(AttendanceStatus.CANCELLED, past)
                )
            )

        val colours = controller.calendarFeed("2026-01-01T00:00:00", "2027-01-01T00:00:00")
            .map { it.backgroundColor }

        assertEquals(5, colours.distinct().size)
        // A past PLANNED session gets its own colour rather than looking merely planned.
        assertTrue(colours[0] != colours[1])
    }

    @Test
    fun `should expose the style breakdown and link to the app's own detail page`() {
        val id = UUID.randomUUID()
        val standard = DanceCategory().apply { this.id = UUID.randomUUID(); name = "Standard" }
        val event = event(AttendanceStatus.PLANNED, LocalDateTime.of(2026, 9, 14, 18, 0), id).apply {
            segments.add(TrainingEventSegment().apply {
                danceCategory = standard
                durationMinutes = 60
                sortOrder = 0
            })
        }
        `when`(trainingEventService.findInRange(any(LocalDateTime::class.java), any(LocalDateTime::class.java)))
            .thenReturn(listOf(event))

        val result = controller.calendarFeed("2026-09-01T00:00:00", "2026-10-01T00:00:00")

        assertEquals("/training-events/$id", result[0].url)
        assertEquals(listOf("Standard 60min"), result[0].extendedProps.styles)
    }

    private fun event(
        status: AttendanceStatus,
        start: LocalDateTime,
        eventId: UUID = UUID.randomUUID()
    ) = TrainingEvent().apply {
        id = eventId
        title = "Monday practice"
        startTime = start
        endTime = start.plusHours(2)
        attendanceStatus = status
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
}
