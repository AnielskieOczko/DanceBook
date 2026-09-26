package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.CalendarSource
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.SeriesScope
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
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

    private fun createCalendar(
        id: UUID = UUID.randomUUID(),
        displayName: String = "Test Calendar",
        enabled: Boolean = true,
        googleCalendarId: String? = "test@group.calendar.google.com"
    ): TrainingCalendar {
        val cal = TrainingCalendar().apply {
            this.id = id
            this.displayName = displayName
            this.enabled = enabled
        }
        if (googleCalendarId != null) {
            cal.addSource(googleCalendarId, isWriteTarget = true)
        }
        return cal
    }

    private val TrainingCalendar.googleCalendarId: String
        get() = writeTarget?.googleCalendarId ?: sources.firstOrNull()?.googleCalendarId ?: ""

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

        defaultCalendar = createCalendar(
            displayName = "Default Calendar",
            googleCalendarId = "default-cal@group.calendar.google.com"
        )
        `when`(trainingCalendarService.requireDefault(currentUser)).thenReturn(defaultCalendar)
        `when`(trainingCalendarService.requireDefault()).thenReturn(defaultCalendar)
        `when`(trainingCalendarService.findDefault(currentUser)).thenReturn(defaultCalendar)
        `when`(trainingCalendarService.findDefault()).thenReturn(defaultCalendar)

        service = TrainingSeriesServiceImpl(
            trainingEventRepository,
            trainingSeriesPersistence,
            calendarClient,
            trainingCalendarService,
            appUserService,
            danceCategoryService,
            materialService,
            RichTextServiceImpl()
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
    fun `should update occurrences in place from the edited one forward keeping IDs and Google event IDs`() {
        val series = seriesFor()
        val edited = occurrence(series, LocalDate.of(2026, 9, 21))
        val originalId = edited.id
        val originalGoogleEventId = edited.googleEventId
        `when`(trainingEventRepository.findById(edited.id!!)).thenReturn(Optional.of(edited))
        `when`(
            trainingEventRepository.findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
                series, LocalDate.of(2026, 9, 21).atStartOfDay()
            )
        ).thenReturn(listOf(edited))
        `when`(
            trainingSeriesPersistence.updateOccurrencesInPlace(
                any(TrainingSeries::class.java), anyList(), any(AppUser::class.java)
            )
        ).thenAnswer { it.getArgument<List<TrainingEvent>>(1) }

        val result = service.updateThisAndFollowing(
            edited.id!!,
            weeklyRequest(until = LocalDate.of(2026, 9, 28)).copy(
                title = "Updated in place",
                date = LocalDate.of(2026, 9, 21)
            )
        )

        // The repository was asked only for occurrences at or after the edited date
        verify(trainingEventRepository).findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
            series, LocalDate.of(2026, 9, 21).atStartOfDay()
        )
        // Google calendar event was updated in place, never deleted or recreated
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-old", edited)
        verify(calendarClient, never()).deleteEvent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString())
        verify(calendarClient, never()).createEvent(org.mockito.Mockito.anyString(), any(TrainingEvent::class.java))

        // Same row ID and same Google event ID
        assertEquals(originalId, result.id)
        assertEquals(originalGoogleEventId, result.googleEventId)
        assertEquals("Updated in place", result.title)
    }

    @Test
    fun `should update all occurrences in place including past ones with recorded outcomes`() {
        val series = seriesFor()
        val pastAttended = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
            googleEventId = "google-past"
        }
        val futurePlanned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "google-future"
        }
        val pastId = pastAttended.id
        val futureId = futurePlanned.id

        `when`(trainingEventRepository.findById(futurePlanned.id!!)).thenReturn(Optional.of(futurePlanned))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(pastAttended, futurePlanned))
        `when`(
            trainingSeriesPersistence.updateOccurrencesInPlace(
                any(TrainingSeries::class.java), anyList(), any(AppUser::class.java)
            )
        ).thenAnswer { it.getArgument<List<TrainingEvent>>(1) }

        service.updateAll(
            futurePlanned.id!!,
            weeklyRequest(until = LocalDate.of(2026, 9, 28)).copy(title = "All updated")
        )

        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-past", pastAttended)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-future", futurePlanned)
        verify(calendarClient, never()).deleteEvent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString())
        verify(calendarClient, never()).createEvent(org.mockito.Mockito.anyString(), any(TrainingEvent::class.java))

        assertEquals(pastId, pastAttended.id)
        assertEquals(futureId, futurePlanned.id)
        assertEquals("google-past", pastAttended.googleEventId)
        assertEquals("google-future", futurePlanned.googleEventId)
        assertEquals(AttendanceStatus.ATTENDED, pastAttended.attendanceFor(currentUser), "recorded outcome is kept")
        assertEquals(AttendanceStatus.PLANNED, futurePlanned.attendanceFor(currentUser))
        assertEquals("All updated", pastAttended.title)
        assertEquals("All updated", futurePlanned.title)
    }

    @Test
    fun `updateThisEvent should detach occurrence from series and update in place`() {
        val series = seriesFor()
        val occurrence = occurrence(series, LocalDate.of(2026, 9, 21))
        val originalId = occurrence.id
        val originalGoogleEventId = occurrence.googleEventId

        `when`(trainingEventRepository.findById(occurrence.id!!)).thenReturn(Optional.of(occurrence))
        `when`(
            trainingSeriesPersistence.detachAndSave(
                any(TrainingEvent::class.java), eq(series), any(AppUser::class.java)
            )
        ).thenAnswer { it.getArgument<TrainingEvent>(0) }

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 28)).copy(
            title = "Detached one-off",
            date = LocalDate.of(2026, 9, 21),
            startTime = LocalTime.of(17, 0),
            endTime = LocalTime.of(19, 0)
        )
        val result = service.updateThisEvent(occurrence.id!!, request)

        assertNull(result.series, "occurrence must be detached from series")
        assertEquals(originalId, result.id)
        assertEquals(originalGoogleEventId, result.googleEventId)
        assertEquals("Detached one-off", result.title)
        assertEquals(LocalTime.of(17, 0), result.startTime.toLocalTime())
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-old", occurrence)
        verify(trainingSeriesPersistence).detachAndSave(occurrence, series, currentUser)
    }

    @Test
    fun `partial failure in Google Calendar during update throws and logs without saving to database`() {
        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply { googleEventId = "g-1" }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply { googleEventId = "g-2" }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(
            eq(series), any(LocalDateTime::class.java)
        )).thenReturn(listOf(occ1, occ2))
        `when`(calendarClient.updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2))
            .thenThrow(RuntimeException("Google API 500 error"))

        assertThrows(RuntimeException::class.java) {
            service.updateThisAndFollowing(occ1.id!!, weeklyRequest(until = LocalDate.of(2026, 9, 28)))
        }

        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-1", occ1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2)
        verifyNoInteractions(trainingSeriesPersistence)
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

        val req = weeklyRequest(until = LocalDate.of(2026, 9, 28))
        assertThrows(IllegalStateException::class.java) { service.updateThisEvent(standalone.id!!, req) }
        assertThrows(IllegalStateException::class.java) { service.updateThisAndFollowing(standalone.id!!, req) }
        assertThrows(IllegalStateException::class.java) { service.updateAll(standalone.id!!, req) }
        assertThrows(IllegalStateException::class.java) { service.deleteThisAndFollowing(standalone.id!!) }
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
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
        }
        val currentPlanned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
        }
        val futureSkipped = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            setAttendance(currentUser, AttendanceStatus.SKIPPED)
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
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
            googleEventId = "google-attended"
        }
        val planned = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "google-planned"
        }
        val cancelled = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            setAttendance(currentUser, AttendanceStatus.CANCELLED)
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
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "google-fail"
        }
        val planned2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
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
        val customCal = createCalendar(
            id = customCalId,
            displayName = "Custom Series Calendar",
            enabled = true,
            googleCalendarId = "custom-series-cal@group.calendar.google.com"
        )
        `when`(trainingCalendarService.findById(customCalId)).thenReturn(customCal)
        `when`(trainingCalendarService.findByIdVisibleTo(eq(customCalId), any(AppUser::class.java))).thenReturn(customCal)

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
        val disabledCal = createCalendar(
            id = disabledCalId,
            displayName = "Disabled Calendar",
            enabled = false,
            googleCalendarId = "disabled-series-cal@group.calendar.google.com"
        )
        `when`(trainingCalendarService.findById(disabledCalId)).thenReturn(disabledCal)
        `when`(trainingCalendarService.findByIdVisibleTo(eq(disabledCalId), any(AppUser::class.java))).thenReturn(disabledCal)

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 28), calendarId = disabledCalId)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.create(request)
        }

        assertTrue(exception.message!!.contains("is disabled"))
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `extending repeatUntil generates missing occurrences each with its Google event`() {
        val series = seriesFor() // startsOn: Sep 14, endsOn: Sep 28 (3 Mondays)
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply { googleEventId = "g-1" }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply { googleEventId = "g-2" }
        val occ3 = occurrence(series, LocalDate.of(2026, 9, 28)).apply { googleEventId = "g-3" }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2, occ3))
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("g-new-1", "g-new-2")
        `when`(
            trainingSeriesPersistence.reconcileSeries(
                eq(series), anyList(), anyList(), anyList(), anyList(), eq(currentUser)
            )
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.getArgument<List<TrainingEvent>>(1) + it.getArgument<List<TrainingEvent>>(2))
        }

        // Extend to Oct 12 (5 Mondays total: Sep 14, 21, 28, Oct 5, Oct 12)
        val request = weeklyRequest(until = LocalDate.of(2026, 10, 12))
        service.updateAll(occ1.id!!, request)

        // 3 surviving updated
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-1", occ1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-3", occ3)

        // 2 new created in Google Calendar
        verify(calendarClient, org.mockito.Mockito.times(2))
            .createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))

        @Suppress("UNCHECKED_CAST")
        val newOccsCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<TrainingEvent>>
        verify(trainingSeriesPersistence).reconcileSeries(
            eq(series),
            eq(listOf(occ1, occ2, occ3)),
            capture(newOccsCaptor, emptyList()),
            eq(emptyList()),
            eq(emptyList()),
            eq(currentUser)
        )
        val newOccs = newOccsCaptor.value
        assertEquals(2, newOccs.size)
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), newOccs.map { it.startTime.toLocalDate() })
    }

    @Test
    fun `shortening repeatUntil removes planned occurrences and leaves recorded ones standing as standalone`() {
        val series = seriesFor().apply { endsOn = LocalDate.of(2026, 10, 5) }
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
            googleEventId = "g-1"
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "g-2"
        }
        val occ3 = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            setAttendance(currentUser, AttendanceStatus.ATTENDED) // Recorded past session outside new range
            googleEventId = "g-3"
        }
        val occ4 = occurrence(series, LocalDate.of(2026, 10, 5)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED) // Unrecorded session outside new range
            googleEventId = "g-4"
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2, occ3, occ4))
        `when`(
            trainingSeriesPersistence.reconcileSeries(
                eq(series), anyList(), anyList(), anyList(), anyList(), eq(currentUser)
            )
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            it.getArgument<List<TrainingEvent>>(1)
        }

        // Shorten until Sep 21 (2 target dates: Sep 14, Sep 21)
        val request = weeklyRequest(until = LocalDate.of(2026, 9, 21))
        service.updateAll(occ1.id!!, request)

        // Surviving occurrences updated in Google Calendar
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-1", occ1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2)

        // Recorded excess occ3 is NOT deleted from Google Calendar
        verify(calendarClient, never()).deleteEvent(defaultCalendar.googleCalendarId, "g-3")

        // Unrecorded excess occ4 IS deleted from Google Calendar
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "g-4")

        // Persistence called with occ3 detached and occ4 deleted
        verify(trainingSeriesPersistence).reconcileSeries(
            eq(series),
            eq(listOf(occ1, occ2)),
            eq(emptyList()),
            eq(listOf(occ3)),
            eq(listOf(occ4)),
            eq(currentUser)
        )
    }

    @Test
    fun `changing weekday moves every occurrence keeping rows Google event IDs and recorded attendance`() {
        val series = seriesFor() // Mondays: Sep 14, 21, 28
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
            googleEventId = "g-1"
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "g-2"
        }
        val occ3 = occurrence(series, LocalDate.of(2026, 9, 28)).apply {
            setAttendance(currentUser, AttendanceStatus.SKIPPED)
            googleEventId = "g-3"
        }
        val origId1 = occ1.id
        val origId2 = occ2.id
        val origId3 = occ3.id

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2, occ3))
        `when`(
            trainingSeriesPersistence.reconcileSeries(
                eq(series), anyList(), anyList(), anyList(), anyList(), eq(currentUser)
            )
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            it.getArgument<List<TrainingEvent>>(1)
        }

        // Change weekday to Wednesday (startsOn becomes Sep 16, repeatUntil Sep 30)
        val request = weeklyRequest(until = LocalDate.of(2026, 9, 30)).copy(
            dayOfWeek = DayOfWeek.WEDNESDAY
        )
        service.updateAll(occ1.id!!, request)

        // All 3 moved to Wednesdays
        assertEquals(LocalDate.of(2026, 9, 16), occ1.startTime.toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 23), occ2.startTime.toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 30), occ3.startTime.toLocalDate())

        // Preserves rows, Google IDs, attendance
        assertEquals(origId1, occ1.id)
        assertEquals(origId2, occ2.id)
        assertEquals(origId3, occ3.id)
        assertEquals("g-1", occ1.googleEventId)
        assertEquals("g-2", occ2.googleEventId)
        assertEquals("g-3", occ3.googleEventId)
        assertEquals(AttendanceStatus.ATTENDED, occ1.attendanceFor(currentUser))
        assertEquals(AttendanceStatus.PLANNED, occ2.attendanceFor(currentUser))
        assertEquals(AttendanceStatus.SKIPPED, occ3.attendanceFor(currentUser))

        // Google Calendar was updated with new times, never deleted
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-1", occ1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-3", occ3)
        verify(calendarClient, never()).deleteEvent(any(String::class.java), any(String::class.java))
    }

    @Test
    fun `changing times moves every occurrence keeping rows Google event IDs and attendance`() {
        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            setAttendance(currentUser, AttendanceStatus.ATTENDED)
            googleEventId = "g-1"
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            setAttendance(currentUser, AttendanceStatus.PLANNED)
            googleEventId = "g-2"
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2))
        `when`(
            trainingSeriesPersistence.reconcileSeries(
                eq(series), anyList(), anyList(), anyList(), anyList(), eq(currentUser)
            )
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            it.getArgument<List<TrainingEvent>>(1)
        }

        // Change time from 18:00-20:00 to 19:30-21:30
        val request = weeklyRequest(until = LocalDate.of(2026, 9, 21)).copy(
            startTime = LocalTime.of(19, 30),
            endTime = LocalTime.of(21, 30)
        )
        service.updateAll(occ1.id!!, request)

        assertEquals(LocalTime.of(19, 30), occ1.startTime.toLocalTime())
        assertEquals(LocalTime.of(21, 30), occ1.endTime.toLocalTime())
        assertEquals(LocalTime.of(19, 30), occ2.startTime.toLocalTime())
        assertEquals(LocalTime.of(21, 30), occ2.endTime.toLocalTime())

        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-1", occ1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "g-2", occ2)
    }

    @Test
    fun `resulting occurrence set exceeding 52 is refused with informative message`() {
        val series = seriesFor()
        val occ = occurrence(series, LocalDate.of(2026, 9, 14))
        `when`(trainingEventRepository.findById(occ.id!!)).thenReturn(Optional.of(occ))

        // 53 weeks later: 2026-09-14 to 2027-09-20 is 54 Mondays
        val request = weeklyRequest(until = LocalDate.of(2027, 9, 20))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.updateAll(occ.id!!, request)
        }
        assertTrue(ex.message!!.contains("the limit is 52"))
        assertTrue(ex.message!!.contains("Choose an earlier end date"))

        val exCalc = assertThrows(IllegalArgumentException::class.java) {
            service.calculatePatternReconcile(occ.id!!, request)
        }
        assertTrue(exCalc.message!!.contains("the limit is 52"))
        assertTrue(exCalc.message!!.contains("Choose an earlier end date"))

        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `partial failure in Google Calendar during pattern update rolls back and leaves no half-applied series`() {
        val standard = category("Standard")
        val latin = category("Latin")
        `when`(danceCategoryService.findById(latin.id!!)).thenReturn(latin)

        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            googleEventId = "g-1"
            title = "Original Title"
            eventType = TrainingEventType.COMPETITION
            description = "Original Description"
            materialsUrl = "https://example.com/original"
            segments.add(TrainingEventSegment().apply {
                danceCategory = standard
                durationMinutes = 60
                sortOrder = 0
            })
        }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply {
            googleEventId = "g-2"
            title = "Original Title"
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2))

        // Update on occ2 fails in Google Calendar
        `when`(calendarClient.updateEvent(eq(defaultCalendar.googleCalendarId), eq("g-2"), any(TrainingEvent::class.java)))
            .thenThrow(RuntimeException("Google 500 API error"))

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 28)).copy(
            dayOfWeek = DayOfWeek.TUESDAY,
            title = "Updated Title",
            eventType = "WORKSHOP",
            description = "Updated Description",
            materialsUrl = "https://example.com/updated",
            segments = mutableListOf(TrainingEventSegmentRequest(latin.id, 90))
        )

        assertThrows(RuntimeException::class.java) {
            service.updateAll(occ1.id!!, request)
        }

        // Verify compensating rollback: occ1 was updated then restored back to original in Google Calendar
        val eventCaptor = ArgumentCaptor.forClass(TrainingEvent::class.java)
        verify(calendarClient, org.mockito.Mockito.times(2))
            .updateEvent(eq(defaultCalendar.googleCalendarId), eq("g-1"), capture(eventCaptor, occ1))
        val rolledBack = eventCaptor.allValues[1]
        assertEquals(LocalDate.of(2026, 9, 14), rolledBack.startTime.toLocalDate())
        assertEquals("Original Title", rolledBack.title)
        assertEquals(TrainingEventType.COMPETITION, rolledBack.eventType)
        assertEquals("Original Description", rolledBack.description)
        assertEquals("https://example.com/original", rolledBack.materialsUrl)
        assertEquals(1, rolledBack.segments.size)
        assertEquals("Standard", rolledBack.segments[0].danceCategory?.name)
        assertEquals(60, rolledBack.segments[0].durationMinutes)

        verifyNoInteractions(trainingSeriesPersistence)
    }

    @Test
    fun `reconcile creates Google event for surviving occurrence that had no googleEventId`() {
        val series = seriesFor()
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply {
            googleEventId = null
        }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1))
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("new-google-id")
        `when`(
            trainingSeriesPersistence.reconcileSeries(
                eq(series), anyList(), anyList(), anyList(), anyList(), any(AppUser::class.java)
            )
        ).thenAnswer { listOf(occ1) }

        val request = weeklyRequest(until = LocalDate.of(2026, 9, 15)).copy(
            dayOfWeek = DayOfWeek.TUESDAY
        )

        service.updateAll(occ1.id!!, request)

        verify(calendarClient).createEvent(eq(defaultCalendar.googleCalendarId), eq(occ1))
        assertEquals("new-google-id", occ1.googleEventId)
    }

    @Test
    fun `calculatePatternReconcile returns correct counts for created moved removed and dropped recorded`() {
        val series = seriesFor().apply { endsOn = LocalDate.of(2026, 10, 5) } // 4 Mondays: Sep 14, 21, 28, Oct 5
        val occ1 = occurrence(series, LocalDate.of(2026, 9, 14)).apply { setAttendance(currentUser, AttendanceStatus.ATTENDED) }
        val occ2 = occurrence(series, LocalDate.of(2026, 9, 21)).apply { setAttendance(currentUser, AttendanceStatus.PLANNED) }
        val occ3 = occurrence(series, LocalDate.of(2026, 9, 28)).apply { setAttendance(currentUser, AttendanceStatus.SKIPPED) }
        val occ4 = occurrence(series, LocalDate.of(2026, 10, 5)).apply { setAttendance(currentUser, AttendanceStatus.PLANNED) }

        `when`(trainingEventRepository.findById(occ1.id!!)).thenReturn(Optional.of(occ1))
        `when`(trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)).thenReturn(listOf(occ1, occ2, occ3, occ4))

        // Case 1: Shorten to 2 sessions (Sep 14, 21).
        // Occ3 (SKIPPED) and Occ4 (PLANNED) are removed. 1 has recorded outcome (occ3).
        val shortenReq = weeklyRequest(until = LocalDate.of(2026, 9, 21))
        val plan1 = service.calculatePatternReconcile(occ1.id!!, shortenReq)
        assertEquals(0, plan1.createdCount)
        assertEquals(0, plan1.movedCount)
        assertEquals(2, plan1.removedCount)
        assertEquals(1, plan1.droppedRecordedCount)
        assertEquals(2, plan1.targetTotalCount)

        // Case 2: Change weekday to Wednesday and extend to Oct 14 (5 Wednesdays: Sep 16, 23, 30, Oct 7, 14)
        val moveAndExtendReq = weeklyRequest(until = LocalDate.of(2026, 10, 14)).copy(dayOfWeek = DayOfWeek.WEDNESDAY)
        val plan2 = service.calculatePatternReconcile(occ1.id!!, moveAndExtendReq)
        assertEquals(1, plan2.createdCount) // 5 target dates vs 4 existing occurrences
        assertEquals(4, plan2.movedCount)   // all 4 existing moved to Wednesday
        assertEquals(0, plan2.removedCount)
        assertEquals(0, plan2.droppedRecordedCount)
        assertEquals(5, plan2.targetTotalCount)
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

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)

    private fun <T> anyList(): List<T> = org.mockito.Mockito.anyList()

    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value
}
