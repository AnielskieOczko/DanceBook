package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.Optional
import java.util.UUID

class TrainingCalendarServiceTest {

    private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var calendarProperties: GoogleCalendarProperties
    private lateinit var service: TrainingCalendarServiceImpl

    @BeforeEach
    fun setUp() {
        trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        calendarProperties = GoogleCalendarProperties(calendarId = "seed-cal@group.calendar.google.com")
        service = TrainingCalendarServiceImpl(
            trainingCalendarRepository,
            trainingEventRepository,
            calendarProperties
        )
    }

    @Test
    fun `bootstrap creates and backfills when seed calendar does not exist`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("seed-cal@group.calendar.google.com"))
            .thenReturn(null)
        `when`(trainingCalendarRepository.findByIsDefaultTrue()).thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }
        `when`(trainingEventRepository.assignMissingCalendar(any(TrainingCalendar())))
            .thenReturn(5)

        service.bootstrapDefaultCalendar()

        val captor = ArgumentCaptor.forClass(TrainingCalendar::class.java)
        verify(trainingCalendarRepository).save(capture(captor, TrainingCalendar()))
        val saved = captor.value
        assertEquals("seed-cal@group.calendar.google.com", saved.googleCalendarId)
        assertTrue(saved.isDefault)
        assertTrue(saved.enabled)
        verify(trainingEventRepository).assignMissingCalendar(saved)
    }

    @Test
    fun `bootstrap a second call creates nothing and reuses existing calendar`() {
        val existing = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "seed-cal@group.calendar.google.com"
            isDefault = true
        }
        `when`(trainingCalendarRepository.findByGoogleCalendarId("seed-cal@group.calendar.google.com"))
            .thenReturn(existing)
        `when`(trainingEventRepository.assignMissingCalendar(existing)).thenReturn(0)

        service.bootstrapDefaultCalendar()

        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
        verify(trainingEventRepository).assignMissingCalendar(existing)
    }

    @Test
    fun `bootstrap a blank seed does nothing`() {
        val blankProperties = GoogleCalendarProperties(calendarId = "   ")
        val blankService = TrainingCalendarServiceImpl(
            trainingCalendarRepository,
            trainingEventRepository,
            blankProperties
        )

        blankService.bootstrapDefaultCalendar()

        verifyNoInteractions(trainingCalendarRepository)
        verifyNoInteractions(trainingEventRepository)
    }

    @Test
    fun `bootstrap does not steal default from an admin-chosen calendar`() {
        val adminDefault = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "admin-cal@group.calendar.google.com"
            isDefault = true
        }
        `when`(trainingCalendarRepository.findByGoogleCalendarId("seed-cal@group.calendar.google.com"))
            .thenReturn(null)
        `when`(trainingCalendarRepository.findByIsDefaultTrue()).thenReturn(adminDefault)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }

        service.bootstrapDefaultCalendar()

        val captor = ArgumentCaptor.forClass(TrainingCalendar::class.java)
        verify(trainingCalendarRepository).save(capture(captor, TrainingCalendar()))
        val saved = captor.value
        assertEquals("seed-cal@group.calendar.google.com", saved.googleCalendarId)
        assertFalse(saved.isDefault, "Seed must not steal default when an admin default already exists")
    }

    @Test
    fun `add rejects a duplicate Google id`() {
        val existing = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "dup@group.calendar.google.com"
        }
        `when`(trainingCalendarRepository.findByGoogleCalendarId("dup@group.calendar.google.com"))
            .thenReturn(existing)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.add(TrainingCalendarRequest("dup@group.calendar.google.com", "Duplicate"))
        }

        assertTrue(exception.message!!.contains("already exists"))
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `add makes the first calendar default and subsequent calendars non-default`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("first@group.calendar.google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(0L)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }

        val first = service.add(TrainingCalendarRequest("first@group.calendar.google.com", "First Calendar"))
        assertTrue(first.isDefault)

        `when`(trainingCalendarRepository.findByGoogleCalendarId("second@group.calendar.google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(1L)

        val second = service.add(TrainingCalendarRequest("second@group.calendar.google.com", "Second Calendar"))
        assertFalse(second.isDefault)
    }

    @Test
    fun `add with enabled false saves a disabled and non-default calendar even if first`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("disabled@group.calendar.google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(0L)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }

        val calendar = service.add(TrainingCalendarRequest("disabled@group.calendar.google.com", "Disabled"), enabled = false)
        assertFalse(calendar.enabled)
        assertFalse(calendar.isDefault)
    }

    @Test
    fun `findAllEnabled returns enabled calendars in order`() {
        val enabledList = listOf(
            TrainingCalendar().apply { displayName = "Cal A"; enabled = true },
            TrainingCalendar().apply { displayName = "Cal B"; enabled = true }
        )
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()).thenReturn(enabledList)

        val result = service.findAllEnabled()

        assertEquals(enabledList, result)
        verify(trainingCalendarRepository).findAllByEnabledTrueOrderByDisplayNameAsc()
    }

    @Test
    fun `setDefault clears before it sets and enables the calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "cal@group.calendar.google.com"
            isDefault = false
            enabled = false
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))

        val result = service.setDefault(id)

        val inOrder = inOrder(trainingCalendarRepository)
        inOrder.verify(trainingCalendarRepository).clearDefaultExcept(id)
        inOrder.verify(trainingCalendarRepository).markDefault(id)

        assertTrue(result.isDefault)
        assertTrue(result.enabled)
    }

    @Test
    fun `disabling the only default is rejected`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))

        val exception = assertThrows(IllegalStateException::class.java) {
            service.setEnabled(id, false)
        }

        assertEquals("Make another calendar the default before disabling this one.", exception.message)
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `requireDefault throws CalendarSyncException with actionable message when no default exists`() {
        `when`(trainingCalendarRepository.findByIsDefaultTrue()).thenReturn(null)

        val exception = assertThrows(CalendarSyncException::class.java) {
            service.requireDefault()
        }

        assertEquals(
            "No default training calendar is configured. Add one under Admin → Training calendars before creating a session.",
            exception.message
        )
    }

    private fun <T> any(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }
}
