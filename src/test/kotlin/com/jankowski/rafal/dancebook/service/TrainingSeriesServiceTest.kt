package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
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
    private lateinit var appUserService: AppUserService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var materialService: MaterialService
    private lateinit var service: TrainingSeriesServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingSeriesPersistence = mock(TrainingSeriesPersistence::class.java)
        calendarClient = mock(GoogleCalendarClient::class.java)
        appUserService = mock(AppUserService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        materialService = mock(MaterialService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        service = TrainingSeriesServiceImpl(
            trainingEventRepository,
            trainingSeriesPersistence,
            calendarClient,
            appUserService,
            danceCategoryService,
            materialService
        )
    }

    @Test
    fun `should generate one occurrence per matching weekday`() {
        // 2026-09-14 is a Monday; through 2026-10-05 inclusive that is four Mondays.
        var counter = 0
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java)))
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
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java))).thenReturn("google-1")

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
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java)))
            .thenReturn("google-1")
            .thenReturn("google-2")
            .thenThrow(CalendarSyncException("Google Calendar create failed (500): boom"))

        assertThrows(CalendarSyncException::class.java) {
            service.create(weeklyRequest(until = LocalDate.of(2026, 9, 28)))
        }

        verify(calendarClient).deleteEvent("google-1")
        verify(calendarClient).deleteEvent("google-2")
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
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java))).thenReturn("google-new")
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
        verify(calendarClient).deleteEvent("google-old")
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
        verify(calendarClient, never()).deleteEvent(org.mockito.Mockito.anyString())
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

    private fun weeklyRequest(until: LocalDate) = TrainingEventRequest(
        title = "Monday practice",
        date = LocalDate.of(2026, 9, 14),
        startTime = LocalTime.of(18, 0),
        endTime = LocalTime.of(20, 0),
        eventType = "TRAINING",
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
        createdBy = currentUser
        this.series = series
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)

    private fun <T> anyList(): List<T> = org.mockito.Mockito.anyList()
}
