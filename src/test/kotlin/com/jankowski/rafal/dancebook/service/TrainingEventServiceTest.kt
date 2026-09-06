package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
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
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class TrainingEventServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingEventPersistence: TrainingEventPersistence
    private lateinit var calendarClient: GoogleCalendarClient
    private lateinit var appUserService: AppUserService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var materialService: MaterialService
    private lateinit var trainingEventService: TrainingEventServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingEventPersistence = mock(TrainingEventPersistence::class.java)
        calendarClient = mock(GoogleCalendarClient::class.java)
        appUserService = mock(AppUserService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        materialService = mock(MaterialService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingEventService = TrainingEventServiceImpl(
            trainingEventRepository,
            trainingEventPersistence,
            calendarClient,
            appUserService,
            danceCategoryService,
            materialService
        )
    }

    @Test
    fun `should create training event and store the returned google event id`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java))).thenReturn("google-123")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenAnswer { it.getArgument<TrainingEvent>(0) }

        val result = trainingEventService.create(request)

        assertNotNull(result)
        assertEquals("Monday practice", result.title)
        assertEquals("google-123", result.googleEventId)
        assertEquals(TrainingEventType.TRAINING, result.eventType)
        assertEquals(currentUser, result.createdBy)
        verify(calendarClient).createEvent(any(TrainingEvent::class.java))
        verify(trainingEventPersistence).insert(any(TrainingEvent::class.java), any(AppUser::class.java))
    }

    @Test
    fun `should not persist anything when the calendar create fails`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java)))
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
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java))).thenReturn("google-orphan")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenThrow(RuntimeException("DB down"))

        val exception = assertThrows(RuntimeException::class.java) {
            trainingEventService.create(request)
        }

        assertEquals("DB down", exception.message)
        // The compensating delete is what stops an unmanageable orphan calendar event.
        verify(calendarClient).deleteEvent("google-orphan")
    }

    @Test
    fun `should still rethrow when the compensating delete itself fails`() {
        val request = validRequest()
        `when`(calendarClient.createEvent(any(TrainingEvent::class.java))).thenReturn("google-orphan")
        `when`(trainingEventPersistence.insert(any(TrainingEvent::class.java), any(AppUser::class.java)))
            .thenThrow(RuntimeException("DB down"))
        `when`(calendarClient.deleteEvent("google-orphan")).thenThrow(RuntimeException("Google also down"))

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

        verify(calendarClient).deleteEvent("google-gone")
        verify(trainingEventPersistence).remove(event, currentUser)
    }

    @Test
    fun `should not call the calendar when deleting an event that never synced`() {
        val event = existingEvent(null)
        `when`(trainingEventRepository.findById(event.id!!)).thenReturn(Optional.of(event))

        trainingEventService.delete(event.id!!)

        verify(calendarClient, never()).deleteEvent(org.mockito.Mockito.anyString())
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
        verify(calendarClient).updateEvent(eq("google-123"), any(TrainingEvent::class.java))
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

        verify(calendarClient).deleteEvent("google-123")
    }

    @Test
    fun `should reject an end time that is not after the start time`() {
        val start = LocalDateTime.of(2026, 9, 10, 18, 0)
        val request = validRequest().copy(startTime = start, endTime = start)

        assertThrows(IllegalArgumentException::class.java) {
            trainingEventService.create(request)
        }

        verifyNoInteractions(calendarClient)
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

    private fun validRequest(title: String = "Monday practice") = TrainingEventRequest(
        title = title,
        startTime = LocalDateTime.of(2026, 9, 10, 18, 0),
        endTime = LocalDateTime.of(2026, 9, 10, 20, 0),
        eventType = "TRAINING",
        attendanceStatus = "PLANNED"
    )

    private fun existingEvent(googleEventId: String?) = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        startTime = LocalDateTime.of(2026, 9, 10, 18, 0)
        endTime = LocalDateTime.of(2026, 9, 10, 20, 0)
        this.googleEventId = googleEventId
        createdBy = currentUser
    }

    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)

    // Mockito.eq returns null, which Kotlin rejects for a non-null parameter type.
    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value
}
