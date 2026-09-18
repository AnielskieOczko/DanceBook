package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.SeriesScope
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional
import java.util.UUID

class TrainingSeriesServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingSeriesPersistence: TrainingSeriesPersistence
    private lateinit var calendarClient: GoogleCalendarClient
    private lateinit var trainingCalendarService: TrainingCalendarService
    private lateinit var appUserService: AppUserService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var materialService: MaterialService
    private lateinit var service: TrainingSeriesServiceImpl
    private lateinit var currentUser: AppUser
    private lateinit var defaultCalendar: TrainingCalendar

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingSeriesPersistence = mock(TrainingSeriesPersistence::class.java)
        calendarClient = mock(GoogleCalendarClient::class.java)
        trainingCalendarService = mock(TrainingCalendarService::class.java)
        appUserService = mock(AppUserService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        materialService = mock(MaterialService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        defaultCalendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "default-cal@group.calendar.google.com"
            displayName = "Default Calendar"
            isDefault = true
        }
        `when`(trainingCalendarService.requireDefault()).thenReturn(defaultCalendar)
        `when`(trainingCalendarService.findDefault()).thenReturn(defaultCalendar)

        service = TrainingSeriesServiceImpl(
            trainingEventRepository,
            trainingSeriesPersistence,
            calendarClient,
            trainingCalendarService,
            appUserService,
            danceCategoryService,
            materialService
        )
    }

    @Test
    fun `should generate one occurrence per matching weekday`() {
        // 2026-09-14 is a Monday; through 2026-10-05 inclusive that is four Mondays.
        var counter = 0
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenAnswer { "google-" + (counter++) }

        val captured = mutableListOf<TrainingEvent>()
        `when`(
            trainingSeriesPersistence.insertSeries(
                any(TrainingSeries::class.java),
                anyList(),
                any(AppUser::class.java)
            )
        ).thenAnswer {
            val list = it.getArgument<List<TrainingEvent>>(1)
            captured.addAll(list)
            list
        }

        service.create(weeklyRequest(until = LocalDate.of(2026, 10, 5)))

        assertEquals(4, captured.size)
        val occurrences = captured
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)
            ),
            occurrences.map { it.startTime.toLocalDate() }
        )
        occurrences.forEach { assertEquals(DayOfWeek.MONDAY, it.startTime.dayOfWeek) }
        // Each occurrence is an independent Google event, so each gets its own id.
        assertEquals(4, occurrences.mapNotNull { it.googleEventId }.distinct().size)
    }

    @Test
    fun `should copy the style template onto every occurrence`() {
        val standard = category("Standard")
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))).thenReturn("google-1")

        val captured = mutableListOf<TrainingEvent>()
        `when`(
            trainingSeriesPersistence.insertSeries(
                any(TrainingSeries::class.java), anyList(), any(AppUser::class.java)
            )
        ).thenAnswer {
            val list = it.getArgument<List<TrainingEvent>>(1)
            captured.addAll(list)
            list
        }

        service.create(
            weeklyRequest(until = LocalDate.of(2026, 9, 28))
                .copy(segments = mutableListOf(TrainingEventSegmentRequest(standard.id, 90)))
        )

        assertEquals(3, captured.size)
        captured.forEach { occurrence ->
            assertEquals(1, occurrence.segments.size)
            assertEquals("Standard", occurrence.segments[0].danceCategory?.name)
            assertEquals(90, occurrence.segments[0].durationMinutes)
        }
    }

    @Test
    fun `should refuse a span longer than the occurrence cap`() {
        // Two years of Mondays is well past the 52-session limit.
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.create(weeklyRequest(until = LocalDate.of(2028, 9, 14)))
        }

        assertTrue(exception.message!!.contains("the limit is ${TrainingSeriesServiceImpl.MAX_OCCURRENCES}"))
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `should refuse an end date before the first session`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(weeklyRequest(until = LocalDate.of(2026, 9, 1)))
        }
        verifyNoInteractions(calendarClient)
    }

    @Test
    fun `should remove already-created calendar events when generation fails part way`() {
        // Two succeed, the third fails: both survivors must be cleaned up so no
        // half-built series is left behind in Google Calendar.
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-1")
            .thenReturn("google-2")
            .thenThrow(CalendarSyncException("Google Calendar create failed (500): boom"))

        assertThrows(CalendarSyncException::class.java) {
            service.create(weeklyRequest(until = LocalDate.of(2026, 9, 28)))
        }

        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-1")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-2")
        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `should only regenerate occurrences from the edited one forward`() {
        val series = seriesFor()
        val edited = occurrence(series, LocalDate.of(2026, 9, 21))
        `when`(trainingEventRepository.findById(edited.id!!)).thenReturn(Optional.of(edited))
        `when`(
            trainingEventRepository.findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
                series, LocalDate.of(2026, 9, 21).atStartOfDay()
            )
        ).thenReturn(listOf(edited))
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))).thenReturn("google-new")
        `when`(
            trainingSeriesPersistence.replaceOccurrences(
                any(TrainingSeries::class.java), anyList(), anyList()
            )
        ).thenAnswer { it.getArgument<List<TrainingEvent>>(2) }

        service.updateThisAndFollowing(
            edited.id!!,
            weeklyRequest(until = LocalDate.of(2026, 9, 28)).copy(date = LocalDate.of(2026, 9, 21))
        )

        // The repository was asked only for occurrences at or after the edited date, so
        // completed sessions are never even loaded, let alone rewritten.
        verify(trainingEventRepository).findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
            series, LocalDate.of(2026, 9, 21).atStartOfDay()
        )
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-old")
    }

    @Test
    fun `should reject a scope change on an event with no series`() {
        val standalone = TrainingEvent().apply {
            id = UUID.randomUUID()
            createdBy = currentUser
            startTime = LocalDateTime.of(2026, 9, 21, 18, 0)
            endTime = LocalDateTime.of(2026, 9, 21, 20, 0)
        }
        `when`(trainingEventRepository.findById(standalone.id!!)).thenReturn(Optional.of(standalone))

        val exception = assertThrows(IllegalStateException::class.java) {
            service.deleteThisAndFollowing(standalone.id!!)
        }

        assertEquals("This session is not part of a repeating series", exception.message)
        verify(calendarClient, never()).deleteEvent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString())
    }

    @Test
    fun `should reject a series operation by a user who does not own it`() {
        val series = seriesFor()
        val owned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            createdBy = AppUser().apply { id = UUID.randomUUID() }
        }
        `when`(trainingEventRepository.findById(owned.id!!)).thenReturn(Optional.of(owned))

        assertThrows(IllegalStateException::class.java) {
            service.deleteThisAndFollowing(owned.id!!)
        }
        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `calculateDeleteScopeOptions calculates counts and outcomes correctly`() {
        val series = seriesFor()
        val pastAttended = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            attendanceStatus = AttendanceStatus.ATTENDED
        }
        val currentPlanned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            attendanceStatus = AttendanceStatus.PLANNED
        }
        val futureSkipped = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            attendanceStatus = AttendanceStatus.SKIPPED
        }
        `when`(trainingEventRepository.findById(currentPlanned.id!!)).thenReturn(Optional.of(currentPlanned))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(
            listOf(pastAttended, currentPlanned, futureSkipped)
        )

        val options = service.calculateDeleteScopeOptions(currentPlanned.id!!)

        assertEquals(3, options.size)
        val thisOption = options[0]
        assertEquals(SeriesScope.THIS_EVENT, thisOption.scope)
        assertEquals(1, thisOption.count)
        assertEquals(0, thisOption.outcomeCount)

        val followingOption = options[1]
        assertEquals(SeriesScope.THIS_AND_FOLLOWING, followingOption.scope)
        assertEquals(2, followingOption.count)
        assertEquals(1, followingOption.outcomeCount)

        val allOption = options[2]
        assertEquals(SeriesScope.ALL_EVENTS, allOption.scope)
        assertEquals(3, allOption.count, "all sessions in series: 3")
        assertEquals(2, allOption.outcomeCount, "sessions with recorded outcomes (attended + skipped)")
    }

    @Test
    fun `deleteAll separates surviving events with outcomes from unhappened events to delete`() {
        val series = seriesFor()
        val attended = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            attendanceStatus = AttendanceStatus.ATTENDED
            googleEventId = "google-attended"
        }
        val planned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            attendanceStatus = AttendanceStatus.PLANNED
            googleEventId = "google-planned"
        }
        val cancelled = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            attendanceStatus = AttendanceStatus.CANCELLED
            googleEventId = "google-cancelled"
        }

        `when`(trainingEventRepository.findById(planned.id!!)).thenReturn(Optional.of(planned))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(
            listOf(attended, planned, cancelled)
        )

        val result = service.deleteAll(planned.id!!)

        assertEquals(2, result.deletedCount)
        assertEquals(0, result.failedCount)
        // Google calendar events are deleted only for unhappened occurrences, not surviving ones
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-planned")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-cancelled")
        verify(calendarClient, never()).deleteEvent(defaultCalendar.googleCalendarId, "google-attended")

        verify(trainingSeriesPersistence).deleteAll(
            eq(series),
            eq(listOf(attended)),
            eq(listOf(planned, cancelled)),
            eq(currentUser)
        )
    }

    @Test
    fun `deleteAll continues and deletes database rows even if Google Calendar fails on some events`() {
        val series = seriesFor()
        val planned1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            attendanceStatus = AttendanceStatus.PLANNED
            googleEventId = "google-fail"
        }
        val planned2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            attendanceStatus = AttendanceStatus.PLANNED
            googleEventId = "google-succeed"
        }

        `when`(trainingEventRepository.findById(planned1.id!!)).thenReturn(Optional.of(planned1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(planned1, planned2))
        `when`(calendarClient.deleteEvent(defaultCalendar.googleCalendarId, "google-fail"))
            .thenThrow(RuntimeException("Google API 500 error"))

        val result = service.deleteAll(planned1.id!!)

        assertEquals(2, result.deletedCount)
        assertEquals(1, result.failedCount)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-fail")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-succeed")
        verify(trainingSeriesPersistence).deleteAll(
            eq(series),
            eq(emptyList()),
            eq(listOf(planned1, planned2)),
            eq(currentUser)
        )
    }

    @Test
    fun `deleteThisAndFollowing continues even if Google Calendar fails on an event`() {
        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            googleEventId = "google-fail"
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            googleEventId = "google-succeed"
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
            eq(series), any(LocalDateTime::class.java)
        )).thenReturn(listOf(occ1, occ2))
        `when`(calendarClient.deleteEvent(defaultCalendar.googleCalendarId, "google-fail"))
            .thenThrow(RuntimeException("Google API 500 error"))

        val result = service.deleteThisAndFollowing(occ1.id!!)

        assertEquals(2, result.deletedCount)
        assertEquals(1, result.failedCount)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-fail")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-succeed")
        verify(trainingSeriesPersistence).removeOccurrences(
            eq(listOf(occ1, occ2)),
            eq(series),
            eq(currentUser)
        )
    }

    @Test
    fun `deleteThisAndFollowing deletes occurrences and returns BulkDeleteResult with zero failures`() {
        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            googleEventId = "google-1"
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            googleEventId = "google-2"
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
            eq(series), any(LocalDateTime::class.java)
        )).thenReturn(listOf(occ1, occ2))

        val result = service.deleteThisAndFollowing(occ1.id!!)

        assertEquals(2, result.deletedCount)
        assertEquals(0, result.failedCount)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-1")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-2")
        verify(trainingSeriesPersistence).removeOccurrences(
            eq(listOf(occ1, occ2)),
            eq(series),
            eq(currentUser)
        )
    }

    @Test
    fun `creating series with explicit calendarId assigns chosen calendar to every occurrence`() {
        val customCalId = UUID.randomUUID()
        val customCal = TrainingCalendar().apply {
            id = customCalId
            googleCalendarId = "custom-series-cal@group.calendar.google.com"
            displayName = "Custom Series Calendar"
            enabled = true
        }
        `when`(trainingCalendarService.findById(customCalId)).thenReturn(customCal)

        var counter = 0
        `when`(calendarClient.createEvent(eq(customCal.googleCalendarId), any(TrainingEvent::class.java)))
            .thenAnswer { "google-" + (counter++) }

        val captured = mutableListOf<TrainingEvent>()
        `when`(
            trainingSeriesPersistence.insertSeries(
                any(TrainingSeries::class.java),
                anyList(),
                any(AppUser::class.java)
            )
        ).thenAnswer {
            val list = it.getArgument<List<TrainingEvent>>(1)
            captured.addAll(list)
            list
        }

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 28), calendarId = customCalId)
        val first = service.create(request)

        assertEquals(3, captured.size)
        captured.forEach { assertEquals(customCal, it.calendar) }
        assertEquals(customCal, first.calendar)
        verify(calendarClient, org.mockito.Mockito.times(3))
            .createEvent(eq(customCal.googleCalendarId), any(TrainingEvent::class.java))
        verify(calendarClient, never())
            .createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))
    }

    @Test
    fun `creating series with disabled calendar is rejected`() {
        val disabledCalId = UUID.randomUUID()
        val disabledCal = TrainingCalendar().apply {
            id = disabledCalId
            googleCalendarId = "disabled-series-cal@group.calendar.google.com"
            displayName = "Disabled Calendar"
            enabled = false
        }
        `when`(trainingCalendarService.findById(disabledCalId)).thenReturn(disabledCal)

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 28), calendarId = disabledCalId)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.create(request)
        }

        assertTrue(exception.message!!.contains("is disabled"))
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingSeriesPersistence)
    }

    private fun weeklyRequest(until: LocalDate, calendarId: UUID? = null) = TrainingEventRequest(
        title = "Monday practice",
        date = LocalDate.of(2026, 9, 14),
        startTime = LocalTime.of(18, 0),
        endTime = LocalTime.of(20, 0),
        eventType = "TRAINING",
        calendarId = calendarId,
        repeat = "WEEKLY",
        repeatUntil = until
    )

    private fun seriesFor() = TrainingSeries().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        dayOfWeek = DayOfWeek.MONDAY
        startTime = LocalTime.of(18, 0)
        endTime = LocalTime.of(20, 0)
        startsOn = LocalDate.of(2026, 9, 14)
        endsOn = LocalDate.of(2026, 9, 28)
        createdBy = currentUser
    }

    private fun occurrence(series: TrainingSeries, date: LocalDate) = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = series.title
        startTime = LocalDateTime.of(date, series.startTime)
        endTime = LocalDateTime.of(date, series.endTime)
        googleEventId = "google-old"
        calendar = defaultCalendar
        createdBy = currentUser
        this.series = series
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)

    private fun <T> anyList(): List<T> = org.mockito.Mockito.anyList()

    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value
}
