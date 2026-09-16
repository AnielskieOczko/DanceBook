package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional
import java.util.UUID

class TrainingEventServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingEventPersistence: TrainingEventPersistence
    private lateinit var calendarClient: GoogleCalendarClient
    private lateinit var trainingCalendarService: TrainingCalendarService
    private lateinit var appUserService: AppUserService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var materialService: MaterialService
    private lateinit var entityManager: jakarta.persistence.EntityManager
    private lateinit var trainingEventService: TrainingEventServiceImpl
    private lateinit var currentUser: AppUser
    private lateinit var defaultCalendar: TrainingCalendar

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingEventPersistence = mock(TrainingEventPersistence::class.java)
        calendarClient = mock(GoogleCalendarClient::class.java)
        trainingCalendarService = mock(TrainingCalendarService::class.java)
        appUserService = mock(AppUserService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        materialService = mock(MaterialService::class.java)
        entityManager = mock(jakarta.persistence.EntityManager::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
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

        trainingEventService = TrainingEventServiceImpl(
            trainingEventRepository,
            trainingEventPersistence,
            calendarClient,
            trainingCalendarService,
            appUserService,
            danceCategoryService,
            materialService,
            entityManager
        )
    }

    @Test
    fun `should create training event and store the returned google event id`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-123")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(request)

        assertNotNull(result)
        assertEquals("Monday practice", result.title)
        assertEquals("google-123", result.googleEventId)
        assertEquals(TrainingEventType.TRAINING, result.eventType)
        assertEquals(currentUser, result.createdBy)
        verify(calendarClient).createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))
        verify(trainingEventPersistence).insert(any(TrainingEvent::class.java), any(AppUser::class.java))
    }

    @Test
    fun `should not persist anything when the calendar create fails`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenThrow(RuntimeException("Google API unavailable"))

        val exception = assertThrows(RuntimeException::class.java) {
            trainingEventService.create(request)
        }

        assertEquals("Google API unavailable", exception.message)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `should delete the calendar event when the local write fails after creating it`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-orphan")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenThrow(RuntimeException("DB down"))

        val exception = assertThrows(RuntimeException::class.java) {
            trainingEventService.create(request)
        }

        assertEquals("DB down", exception.message)
        // The compensating delete is what stops an unmanageable orphan calendar event.
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-orphan")
    }

    @Test
    fun `should still rethrow when the compensating delete itself fails`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-orphan")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenThrow(RuntimeException("DB down"))
        `when`(calendarClient.deleteEvent(defaultCalendar.googleCalendarId, "google-orphan"))
            .thenThrow(RuntimeException("Google also down"))

        val exception = assertThrows(RuntimeException::class.java) {
            trainingEventService.create(request)
        }

        assertEquals("DB down", exception.message)
    }

    @Test
    fun `should delete locally when the calendar event was already deleted`() {
        val event = existingEvent("google-gone")
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))
        // The client treats 404/410 as success, so delete returns normally.

        trainingEventService.delete(event.id!!)

        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-gone")
        verify(trainingEventPersistence).remove(event, currentUser)
    }

    @Test
    fun `should not call the calendar when deleting an event that never synced`() {
        val event = existingEvent(null)
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))

        trainingEventService.delete(event.id!!)

        verify(calendarClient, never()).deleteEvent(anyString(), anyString())
        verify(trainingEventPersistence).remove(event, currentUser)
    }

    @Test
    fun `should update the calendar before the local write`() {
        val event = existingEvent("google-123")
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))
        `when`(trainingEventPersistence.applyUpdate(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.update(event.id!!, validRequest(title = "Renamed practice"))

        assertEquals("Renamed practice", result.title)
        verify(calendarClient).updateEvent(eq(defaultCalendar.googleCalendarId), eq("google-123"), any(TrainingEvent::class.java))
        verify(trainingEventPersistence).applyUpdate(any(TrainingEvent::class.java), any(AppUser::class.java))
    }

    @Test
    fun `should not touch the calendar when only attendance changes`() {
        val event = existingEvent("google-123")
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))
        `when`(trainingEventPersistence.applyUpdate(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.updateAttendance(event.id!!, AttendanceStatus.ATTENDED)

        assertEquals(AttendanceStatus.ATTENDED, result.attendanceStatus)
        verifyNoInteractions(calendarClient)
    }

    @Test
    fun `should reject a mutation by a user who does not own the event`() {
        val owner = AppUser().apply {
            id = UUID.randomUUID()
            username = "someone-else"
        }
        val event = existingEvent("google-123").apply { createdBy = owner }
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))

        val exception = assertThrows(IllegalStateException::class.java) {
            trainingEventService.delete(event.id!!)
        }

        assertEquals("You don't have permission to modify this training event", exception.message)
        verifyNoInteractions(calendarClient)
    }

    @Test
    fun `should allow an admin to modify an event owned by someone else`() {
        val owner = AppUser().apply {
            id = UUID.randomUUID()
            username = "someone-else"
        }
        currentUser.role = Role.ADMIN
        val event = existingEvent("google-123").apply { createdBy = owner }
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))

        trainingEventService.delete(event.id!!)

        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-123")
    }

    @Test
    fun `should reject an end time that is not after the start time`() {
        val request = validRequest().copy(startTime = LocalTime.of(18, 0), endTime = LocalTime.of(18, 0))

        assertThrows(IllegalArgumentException::class.java) {
            trainingEventService.create(request)
        }

        verifyNoInteractions(calendarClient)
    }

    @Test
    fun `should span days when an end date is given`() {
        val request = validRequest().copy(
            endDate = LocalDate.of(2026, 9, 12),
            endTime = LocalTime.of(16, 0)
        )
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-camp")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(request)

        assertEquals(LocalDateTime.of(2026, 9, 10, 18, 0), result.startTime)
        assertEquals(LocalDateTime.of(2026, 9, 12, 16, 0), result.endTime)
    }

    @Test
    fun `should record a style breakdown in order`() {
        val standard = category("Standard")
        val latin = category("Latin")
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        `when`(danceCategoryService.findById(latin.id!!)).thenReturn(latin)
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-mixed")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(
            validRequest(
                segments = listOf(
                    TrainingEventSegmentRequest(standard.id, 60),
                    TrainingEventSegmentRequest(latin.id, 60)
                )
            )
        )

        assertEquals(2, result.segments.size)
        assertEquals("Standard", result.segments[0].danceCategory?.name)
        assertEquals(0, result.segments[0].sortOrder)
        assertEquals("Latin", result.segments[1].danceCategory?.name)
        assertEquals(1, result.segments[1].sortOrder)
        assertEquals(listOf("Standard", "Latin"), result.danceCategories.map { it.name })
    }

    @Test
    fun `should reject a style breakdown longer than the session`() {
        val standard = category("Standard")
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)

        // The slot is two hours; claiming three hours of Standard cannot be right.
        val exception = assertThrows(IllegalArgumentException::class.java) {
            trainingEventService.create(
                validRequest(segments = listOf(TrainingEventSegmentRequest(standard.id, 180)))
            )
        }

        assertTrue(exception.message!!.contains("180 minutes"))
        assertTrue(exception.message!!.contains("120-minute"))
        verifyNoInteractions(calendarClient)
    }

    @Test
    fun `should allow a style breakdown shorter than the session to leave room for breaks`() {
        val standard = category("Standard")
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-break")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(
            validRequest(segments = listOf(TrainingEventSegmentRequest(standard.id, 90)))
        )

        assertEquals(1, result.segments.size)
        assertEquals(120, result.durationMinutes.toInt())
    }

    @Test
    fun `should skip incomplete style rows the user never filled in`() {
        val standard = category("Standard")
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-partial")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(
            validRequest(
                segments = listOf(
                    TrainingEventSegmentRequest(standard.id, 60),
                    TrainingEventSegmentRequest(null, null),
                    TrainingEventSegmentRequest(standard.id, 0)
                )
            )
        )

        assertEquals(1, result.segments.size)
    }

    @Test
    fun `should throw when the training event does not exist`() {
        val id = UUID.randomUUID()
        `when`(trainingEventRepository.findById(id)).thenReturn(Optional.empty())

        val exception = assertThrows(EntityNotFoundException::class.java) {
            trainingEventService.findById(id)
        }

        assertEquals("Could not find training event with id $id", exception.message)
    }

    @Test
    fun `should flag a past planned event as awaiting confirmation`() {
        val past = TrainingEvent().apply {
            startTime = LocalDateTime.now().minusDays(2)
            endTime = LocalDateTime.now().minusDays(2).plusHours(1)
            attendanceStatus = AttendanceStatus.PLANNED
        }
        assertTrue(past.isAwaitingConfirmation)

        past.attendanceStatus = AttendanceStatus.ATTENDED
        assertFalse(past.isAwaitingConfirmation)

        val upcoming = TrainingEvent().apply {
            startTime = LocalDateTime.now().plusDays(2)
            endTime = LocalDateTime.now().plusDays(2).plusHours(1)
            attendanceStatus = AttendanceStatus.PLANNED
        }
        assertFalse(upcoming.isAwaitingConfirmation)
    }

    @Test
    fun `creates in the default calendar`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-default")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val created = trainingEventService.create(request)

        assertEquals(defaultCalendar, created.calendar)
        verify(calendarClient).createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))
    }

    @Test
    fun `updates in the event's own calendar while the default is a different one`() {
        val ownCalendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "own-cal@group.calendar.google.com"
            isDefault = false
        }
        val newDefault = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "new-default@group.calendar.google.com"
            isDefault = true
        }
        `when`(trainingCalendarService.requireDefault()).thenReturn(newDefault)

        val event = existingEvent("google-own").apply { calendar = ownCalendar }
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))
        `when`(trainingEventPersistence.applyUpdate(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val updated = trainingEventService.update(event.id!!, validRequest(title = "Updated Title"))

        assertEquals(ownCalendar, updated.calendar)
        verify(calendarClient).updateEvent(eq("own-cal@group.calendar.google.com"), eq("google-own"), any(TrainingEvent::class.java))
        verify(calendarClient, never()).updateEvent(eq("new-default@group.calendar.google.com"), anyString(), any(TrainingEvent::class.java))
    }

    @Test
    fun `a null-calendar event adopts the default and persists the adoption`() {
        val event = existingEvent("google-legacy").apply { calendar = null }
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))
        `when`(trainingEventPersistence.applyUpdate(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val updated = trainingEventService.update(event.id!!, validRequest(title = "Updated Legacy"))

        assertEquals(defaultCalendar, updated.calendar)
        verify(calendarClient).updateEvent(eq(defaultCalendar.googleCalendarId), eq("google-legacy"), any(TrainingEvent::class.java))

        val captor = ArgumentCaptor.forClass(TrainingEvent::class.java)
        verify(trainingEventPersistence).applyUpdate(capture(captor, TrainingEvent()), any(AppUser::class.java))
        assertEquals(defaultCalendar, captor.value.calendar)
    }

    @Test
    fun `create fails with readable message when there is no default`() {
        `when`(trainingCalendarService.requireDefault()).thenThrow(
            CalendarSyncException(
                "No default training calendar is configured. Add one under Admin → Training calendars before creating a session."
            )
        )

        val exception = assertThrows(CalendarSyncException::class.java) {
            trainingEventService.create(validRequest())
        }

        assertEquals(
            "No default training calendar is configured. Add one under Admin → Training calendars before creating a session.",
            exception.message
        )
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `creating with explicit calendarId uses that calendar, not the default`() {
        val customCalId = UUID.randomUUID()
        val customCalendar = TrainingCalendar().apply {
            id = customCalId
            googleCalendarId = "custom-cal@group.calendar.google.com"
            displayName = "Custom Calendar"
            enabled = true
        }
        `when`(trainingCalendarService.findById(customCalId)).thenReturn(customCalendar)
        `when`(calendarClient.createEvent(eq(customCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-custom")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val request = validRequest(calendarId = customCalId)
        val result = trainingEventService.create(request)

        assertEquals(customCalendar, result.calendar)
        assertEquals("google-custom", result.googleEventId)
        verify(calendarClient).createEvent(eq("custom-cal@group.calendar.google.com"), any(TrainingEvent::class.java))
        verify(calendarClient, never()).createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))
    }

    @Test
    fun `creating with null calendarId uses the default calendar`() {
        `when`(calendarClient.createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java)))
            .thenReturn("google-def")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val request = validRequest(calendarId = null)
        val result = trainingEventService.create(request)

        assertEquals(defaultCalendar, result.calendar)
        verify(trainingCalendarService).requireDefault()
        verify(calendarClient).createEvent(eq(defaultCalendar.googleCalendarId), any(TrainingEvent::class.java))
    }

    @Test
    fun `creating with disabled calendar is rejected`() {
        val disabledCalId = UUID.randomUUID()
        val disabledCalendar = TrainingCalendar().apply {
            id = disabledCalId
            googleCalendarId = "disabled-cal@group.calendar.google.com"
            displayName = "Disabled Calendar"
            enabled = false
        }
        `when`(trainingCalendarService.findById(disabledCalId)).thenReturn(disabledCalendar)

        val request = validRequest(calendarId = disabledCalId)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            trainingEventService.create(request)
        }

        assertTrue(exception.message!!.contains("is disabled"))
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `findInRange scopes to a calendar when one is active`() {
        val calendarId = UUID.randomUUID()
        val from = LocalDateTime.now()
        val to = from.plusDays(7)
        `when`(
            trainingEventRepository
                .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                    currentUser, calendarId, to, from
                )
        ).thenReturn(emptyList())

        trainingEventService.findInRange(from, to, calendarId)

        verify(trainingEventRepository)
            .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                currentUser, calendarId, to, from
            )
        verify(trainingEventRepository, never())
            .findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(currentUser, to, from)
    }

    @Test
    fun `findInRange stays unscoped under All calendars`() {
        val from = LocalDateTime.now()
        val to = from.plusDays(7)
        `when`(
            trainingEventRepository
                .findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(currentUser, to, from)
        ).thenReturn(emptyList())

        trainingEventService.findInRange(from, to, null)

        verify(trainingEventRepository)
            .findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(currentUser, to, from)
        verify(trainingEventRepository, never())
            .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                eq(currentUser), any(UUID::class.java), eq(to), eq(from)
            )
    }

    @Test
    fun `bulkUpdateAttendance updates past sessions and skips future ones`() {
        val pastEvent = existingEvent(null).apply {
            startTime = LocalDateTime.now().minusDays(2)
            endTime = LocalDateTime.now().minusDays(2).plusHours(2)
            attendanceStatus = AttendanceStatus.PLANNED
        }
        val futureEvent = existingEvent(null).apply {
            startTime = LocalDateTime.now().plusDays(2)
            endTime = LocalDateTime.now().plusDays(2).plusHours(2)
            attendanceStatus = AttendanceStatus.PLANNED
        }
        val ids = listOf(pastEvent.id!!, futureEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(pastEvent, futureEvent))

        val result = trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.ATTENDED)

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.futureSkippedCount)
        assertEquals(AttendanceStatus.ATTENDED, result.status)
        assertTrue(result.message.contains("Marked 1 session as attended"))
        assertTrue(result.message.contains("1 future session was skipped"))
        verify(trainingEventPersistence).bulkUpdateAttendance(listOf(pastEvent), AttendanceStatus.ATTENDED, currentUser)
    }

    @Test
    fun `bulkUpdateAttendance can update past sessions whose outcome was already recorded`() {
        val pastAttended = existingEvent(null).apply {
            startTime = LocalDateTime.now().minusDays(1)
            endTime = LocalDateTime.now().minusDays(1).plusHours(2)
            attendanceStatus = AttendanceStatus.ATTENDED
        }
        val ids = listOf(pastAttended.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(pastAttended))

        val result = trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.SKIPPED)

        assertEquals(1, result.updatedCount)
        assertEquals(0, result.futureSkippedCount)
        assertEquals(AttendanceStatus.SKIPPED, result.status)
        assertEquals("Marked 1 session as skipped.", result.message)
        verify(trainingEventPersistence).bulkUpdateAttendance(listOf(pastAttended), AttendanceStatus.SKIPPED, currentUser)
    }

    @Test
    fun `bulkUpdateAttendance never modifies sessions belonging to another user`() {
        val otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "other"
        }
        val foreignEvent = existingEvent(null).apply {
            createdBy = otherUser
            startTime = LocalDateTime.now().minusDays(1)
            endTime = LocalDateTime.now().minusDays(1).plusHours(2)
        }
        val ids = listOf(foreignEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignEvent))

        val result = trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.ATTENDED)

        assertEquals(0, result.updatedCount)
        assertEquals(0, result.futureSkippedCount)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `bulkUpdateAttendance allows admin to modify sessions belonging to another user`() {
        currentUser.role = Role.ADMIN
        val otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "other"
        }
        val foreignPastEvent = existingEvent(null).apply {
            createdBy = otherUser
            startTime = LocalDateTime.now().minusDays(1)
            endTime = LocalDateTime.now().minusDays(1).plusHours(2)
        }
        val ids = listOf(foreignPastEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignPastEvent))

        val result = trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.ATTENDED)

        assertEquals(1, result.updatedCount)
        assertEquals(0, result.futureSkippedCount)
        verify(trainingEventPersistence).bulkUpdateAttendance(listOf(foreignPastEvent), AttendanceStatus.ATTENDED, currentUser)
    }

    @Test
    fun `bulkUpdateAttendance returns empty result for empty session list`() {
        val result = trainingEventService.bulkUpdateAttendance(emptyList(), AttendanceStatus.ATTENDED)

        assertEquals(0, result.updatedCount)
        assertEquals(0, result.futureSkippedCount)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `bulkDelete deletes sessions from Google Calendar and calls bulkRemove on persistence`() {
        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-2")
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(2, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals("Deleted 2 sessions.", result.message)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-1")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-2")
        verify(trainingEventPersistence).bulkRemove(listOf(event1, event2), currentUser)
    }

    @Test
    fun `bulkDelete handles partial failure when Google Calendar delete fails for one session`() {
        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-fail")
        val event3 = existingEvent("google-3")
        val ids = listOf(event1.id!!, event2.id!!, event3.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2, event3))
        doThrow(RuntimeException("Google API 500 error")).`when`(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-fail")

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(2, result.deletedCount)
        assertEquals(1, result.failedCount)
        assertEquals("Deleted 2 sessions (1 session could not be deleted).", result.message)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-1")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-fail")
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-3")
        verify(trainingEventPersistence).bulkRemove(listOf(event1, event3), currentUser)
    }

    @Test
    fun `bulkDelete refuses selection larger than the cap`() {
        val ids = (1..51).map { UUID.randomUUID() }

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(0, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals("Cannot delete 51 sessions at once: maximum is 50.", result.message)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `bulkDelete never deletes sessions belonging to another user`() {
        val otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "other"
        }
        val foreignEvent = existingEvent("google-foreign").apply { createdBy = otherUser }
        val ownEvent = existingEvent("google-own")
        val ids = listOf(foreignEvent.id!!, ownEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignEvent, ownEvent))

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals("Deleted 1 session.", result.message)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-own")
        verify(calendarClient, never()).deleteEvent(defaultCalendar.googleCalendarId, "google-foreign")
        verify(trainingEventPersistence).bulkRemove(listOf(ownEvent), currentUser)
    }

    @Test
    fun `bulkDelete allows admin to delete sessions belonging to another user`() {
        currentUser.role = Role.ADMIN
        val otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "other"
        }
        val foreignEvent = existingEvent("google-foreign").apply { createdBy = otherUser }
        val ids = listOf(foreignEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignEvent))

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.failedCount)
        verify(calendarClient).deleteEvent(defaultCalendar.googleCalendarId, "google-foreign")
        verify(trainingEventPersistence).bulkRemove(listOf(foreignEvent), currentUser)
    }

    @Test
    fun `bulkDelete deletes sessions that do not have a googleEventId`() {
        val localEvent = existingEvent(null)
        val ids = listOf(localEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(localEvent))

        val result = trainingEventService.bulkDelete(ids)

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals("Deleted 1 session.", result.message)
        verifyNoInteractions(calendarClient)
        verify(trainingEventPersistence).bulkRemove(listOf(localEvent), currentUser)
    }

    @Test
    fun `bulkDelete returns empty result for empty session list`() {
        val result = trainingEventService.bulkDelete(emptyList())

        assertEquals(0, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals("No sessions were selected.", result.message)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    // ── Bulk Event Type ──────────────────────────────────────────────────────

    @Test
    fun `bulkUpdateEventType updates sessions, syncs Google Calendar, and persists bulk update`() {
        val event1 = existingEvent("google-1").apply { eventType = TrainingEventType.TRAINING }
        val event2 = existingEvent("google-2").apply { eventType = TrainingEventType.TRAINING }
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))

        val result = trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)

        assertEquals(2, result.updatedCount)
        assertEquals(0, result.failedCount)
        assertEquals("Updated event type for 2 sessions.", result.message)
        assertEquals(TrainingEventType.WORKSHOP, event1.eventType)
        assertEquals(TrainingEventType.WORKSHOP, event2.eventType)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-1", event1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-2", event2)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1, event2), "event type", currentUser)
    }

    @Test
    fun `bulkUpdateEventType handles partial failure when Google Calendar update fails for one session`() {
        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-fail")
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))
        doThrow(RuntimeException("Google 500")).`when`(calendarClient)
            .updateEvent(defaultCalendar.googleCalendarId, "google-fail", event2)

        val result = trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.failedCount)
        assertEquals("Updated event type for 1 session (1 session could not be updated).", result.message)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1), "event type", currentUser)
    }

    @Test
    fun `bulkUpdateEventType refuses selection larger than cap`() {
        val ids = (1..51).map { UUID.randomUUID() }

        val result = trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)

        assertEquals(0, result.updatedCount)
        assertEquals("Cannot update 51 sessions at once: maximum is 50.", result.message)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    @Test
    fun `bulkUpdateEventType skips sessions belonging to another user`() {
        val foreignUser = AppUser().apply { id = UUID.randomUUID() }
        val foreignEvent = existingEvent("google-foreign").apply { createdBy = foreignUser }
        val ownEvent = existingEvent("google-own")
        val ids = listOf(foreignEvent.id!!, ownEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignEvent, ownEvent))

        val result = trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)

        assertEquals(1, result.updatedCount)
        assertEquals("Updated event type for 1 session.", result.message)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-own", ownEvent)
        verify(calendarClient, never()).updateEvent(defaultCalendar.googleCalendarId, "google-foreign", foreignEvent)
        verify(trainingEventPersistence).bulkUpdate(listOf(ownEvent), "event type", currentUser)
    }

    @Test
    fun `bulkUpdateEventType returns empty result for empty session list`() {
        val result = trainingEventService.bulkUpdateEventType(emptyList(), TrainingEventType.WORKSHOP)

        assertEquals(0, result.updatedCount)
        assertEquals("No sessions were selected.", result.message)
        verifyNoInteractions(trainingEventRepository)
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    // ── Bulk Style Segments ──────────────────────────────────────────────────

    @Test
    fun `bulkUpdateSegments updates style breakdown across sessions`() {
        val cat = category("Standard")
        `when`(danceCategoryService.findById(cat.id!!)).thenReturn(cat)

        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-2")
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))

        val segments = listOf(TrainingEventSegmentRequest(cat.id, 60))
        val result = trainingEventService.bulkUpdateSegments(ids, segments)

        assertEquals(2, result.updatedCount)
        assertEquals(0, result.failedCount)
        assertEquals(0, result.skippedCount)
        assertEquals("Updated style breakdown for 2 sessions.", result.message)
        assertEquals(1, event1.segments.size)
        assertEquals(60, event1.segments[0].durationMinutes)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-1", event1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-2", event2)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1, event2), "style breakdown", currentUser)
    }

    @Test
    fun `bulkUpdateSegments skips sessions whose duration is shorter than breakdown and reports them`() {
        val cat = category("Standard")
        `when`(danceCategoryService.findById(cat.id!!)).thenReturn(cat)

        val longEvent = existingEvent("google-long")
        val shortEvent = existingEvent("google-short").apply {
            endTime = startTime.plusMinutes(45)
        }
        val ids = listOf(longEvent.id!!, shortEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(longEvent, shortEvent))

        val segments = listOf(TrainingEventSegmentRequest(cat.id, 60))
        val result = trainingEventService.bulkUpdateSegments(ids, segments)

        assertEquals(1, result.updatedCount)
        assertEquals(0, result.failedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(
            "Updated style breakdown for 1 session (1 session was skipped because the style breakdown exceeds its duration).",
            result.message
        )
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-long", longEvent)
        verify(calendarClient, never()).updateEvent(defaultCalendar.googleCalendarId, "google-short", shortEvent)
        verify(trainingEventPersistence).bulkUpdate(listOf(longEvent), "style breakdown", currentUser)
    }

    @Test
    fun `bulkUpdateSegments reports message correctly when all sessions are skipped`() {
        val cat = category("Standard")
        val shortEvent = existingEvent("google-short").apply {
            endTime = startTime.plusMinutes(30)
        }
        val ids = listOf(shortEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(shortEvent))

        val segments = listOf(TrainingEventSegmentRequest(cat.id, 60))
        val result = trainingEventService.bulkUpdateSegments(ids, segments)

        assertEquals(0, result.updatedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(
            "No sessions were updated (1 session was skipped because the style breakdown exceeds its duration).",
            result.message
        )
        verifyNoInteractions(calendarClient)
        verifyNoInteractions(trainingEventPersistence)
    }

    // ── Bulk Material ────────────────────────────────────────────────────────

    @Test
    fun `bulkUpdateMaterial sets Note and external link across sessions`() {
        val material = Material().apply {
            id = UUID.randomUUID()
            name = "Rumba Walk Notes"
        }
        `when`(materialService.findById(material.id!!)).thenReturn(material)

        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-2")
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))

        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = ids,
            materialId = material.id,
            materialsUrl = "https://example.com/video",
            clearMaterial = false
        )

        assertEquals(2, result.updatedCount)
        assertEquals("Updated material for 2 sessions.", result.message)
        assertEquals(material, event1.material)
        assertEquals("https://example.com/video", event1.materialsUrl)
        assertEquals(material, event2.material)
        assertEquals("https://example.com/video", event2.materialsUrl)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-1", event1)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-2", event2)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1, event2), "material", currentUser)
    }

    @Test
    fun `bulkUpdateMaterial clears material when clearMaterial is true`() {
        val material = Material().apply { id = UUID.randomUUID(); name = "Old Note" }
        val event1 = existingEvent("google-1").apply {
            this.material = material
            this.materialsUrl = "https://old.com"
        }
        val ids = listOf(event1.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1))

        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = ids,
            materialId = null,
            materialsUrl = null,
            clearMaterial = true
        )

        assertEquals(1, result.updatedCount)
        assertEquals("Updated material for 1 session.", result.message)
        assertEquals(null, event1.material)
        assertEquals(null, event1.materialsUrl)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-1", event1)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1), "material", currentUser)
    }

    @Test
    fun `bulkUpdateMaterial handles partial failure when Google Calendar update fails`() {
        val material = Material().apply { id = UUID.randomUUID(); name = "Note" }
        `when`(materialService.findById(material.id!!)).thenReturn(material)

        val event1 = existingEvent("google-1")
        val event2 = existingEvent("google-fail")
        val ids = listOf(event1.id!!, event2.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(event1, event2))
        doThrow(RuntimeException("Google 500")).`when`(calendarClient)
            .updateEvent(defaultCalendar.googleCalendarId, "google-fail", event2)

        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = ids,
            materialId = material.id,
            materialsUrl = null,
            clearMaterial = false
        )

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.failedCount)
        assertEquals("Updated material for 1 session (1 session could not be updated).", result.message)
        verify(trainingEventPersistence).bulkUpdate(listOf(event1), "material", currentUser)
    }

    @Test
    fun `bulkUpdateMaterial skips sessions belonging to another user`() {
        val foreignUser = AppUser().apply { id = UUID.randomUUID() }
        val foreignEvent = existingEvent("google-foreign").apply { createdBy = foreignUser }
        val ownEvent = existingEvent("google-own")
        val ids = listOf(foreignEvent.id!!, ownEvent.id!!)
        `when`(trainingEventRepository.findAllByIdIn(ids)).thenReturn(listOf(foreignEvent, ownEvent))

        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = ids,
            materialId = null,
            materialsUrl = "https://link.com",
            clearMaterial = false
        )

        assertEquals(1, result.updatedCount)
        verify(calendarClient).updateEvent(defaultCalendar.googleCalendarId, "google-own", ownEvent)
        verify(calendarClient, never()).updateEvent(defaultCalendar.googleCalendarId, "google-foreign", foreignEvent)
        verify(trainingEventPersistence).bulkUpdate(listOf(ownEvent), "material", currentUser)
    }

    private fun validRequest(
        title: String = "Monday practice",
        segments: List<TrainingEventSegmentRequest> = emptyList(),
        calendarId: UUID? = null
    ) = TrainingEventRequest(
        title = title,
        date = LocalDate.of(2026, 9, 10),
        startTime = LocalTime.of(18, 0),
        endTime = LocalTime.of(20, 0),
        eventType = "TRAINING",
        calendarId = calendarId,
        segments = segments.toMutableList(),
        attendanceStatus = "PLANNED"
    )

    private fun existingEvent(googleEventId: String?) = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        startTime = LocalDateTime.of(2026, 9, 10, 18, 0)
        endTime = LocalDateTime.of(2026, 9, 10, 20, 0)
        this.googleEventId = googleEventId
        this.calendar = defaultCalendar
        createdBy = currentUser
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }

    // Mockito.eq returns null, which Kotlin rejects for a non-null parameter type.
    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value
}
