package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
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
    private lateinit var trainingEventPersistence: TrainingEventPersistence
    private lateinit var calendarProperties: GoogleCalendarProperties
    private lateinit var service: TrainingCalendarServiceImpl

    @BeforeEach
    fun setUp() {
        trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingEventPersistence = mock(TrainingEventPersistence::class.java)
        calendarProperties = GoogleCalendarProperties(calendarId = "seed-cal@group.calendar.google.com")
        service = TrainingCalendarServiceImpl(
            trainingCalendarRepository,
            trainingEventRepository,
            trainingEventPersistence,
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
            trainingEventPersistence,
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
    fun `disabling the default calendar is refused when another exists`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.count()).thenReturn(2L)

        val exception = assertThrows(IllegalStateException::class.java) {
            service.setEnabled(id, false)
        }

        assertEquals("Make another calendar the default before disabling this one.", exception.message)
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `disabling the default calendar is allowed and clears default when it is the only calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.count()).thenReturn(1L)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val updated = service.setEnabled(id, false)

        assertFalse(updated.enabled)
        assertFalse(updated.isDefault)
        verify(trainingCalendarRepository).save(calendar)
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

    @Test
    fun `update renames calendar while keeping Google ID untouched`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "cal@google.com"
            displayName = "Old Name"
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("cal@google.com", "New Name")
        val updated = service.update(id, request)

        assertEquals("New Name", updated.displayName)
        assertEquals("cal@google.com", updated.googleCalendarId)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `update changes Google ID when calendar owns no sessions`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val updated = service.update(id, request, enabled = true)

        assertEquals("new@google.com", updated.googleCalendarId)
        assertTrue(updated.enabled)
    }

    @Test
    fun `update rejects Google ID change when calendar owns sessions`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(3L)

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val ex = assertThrows(IllegalStateException::class.java) {
            service.update(id, request)
        }

        assertTrue(ex.message!!.contains("A calendar's Google ID may only be changed while it owns no sessions."))
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `update rejects duplicate Google ID`() {
        val id = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        val other = TrainingCalendar().apply {
            this.id = otherId
            googleCalendarId = "dup@google.com"
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("dup@google.com")).thenReturn(other)

        val request = TrainingCalendarRequest("dup@google.com", "Calendar")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.update(id, request)
        }

        assertTrue(ex.message!!.contains("already exists"))
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `update with enabled false saves calendar disabled`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val updated = service.update(id, request, enabled = false)

        assertFalse(updated.enabled)
    }

    @Test
    fun `update with enabled false on default calendar refuses when another exists`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Default Calendar"
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(2L)

        val request = TrainingCalendarRequest("new@google.com", "Default Calendar")
        val ex = assertThrows(IllegalStateException::class.java) {
            service.update(id, request, enabled = false)
        }

        assertEquals("Make another calendar the default before disabling this one.", ex.message)
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `update with enabled false on default calendar saves disabled and clears default when it is the only calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Only Default Calendar"
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(1L)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Only Default Calendar")
        val updated = service.update(id, request, enabled = false)

        assertEquals("new@google.com", updated.googleCalendarId)
        assertFalse(updated.enabled)
        assertFalse(updated.isDefault)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `delete refuses to delete default calendar when another exists`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.count()).thenReturn(2L)

        val actor = AppUser()
        val ex = assertThrows(IllegalStateException::class.java) {
            service.delete(id, actor)
        }

        assertEquals("Make another calendar the default before deleting this one.", ex.message)
        verify(trainingCalendarRepository, never()).delete(any(TrainingCalendar()))
    }

    @Test
    fun `delete removes calendar and its sessions through persistence without calling Google`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = false
        }
        val event1 = TrainingEvent().apply { this.id = UUID.randomUUID(); title = "Event 1" }
        val event2 = TrainingEvent().apply { this.id = UUID.randomUUID(); title = "Event 2" }

        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.count()).thenReturn(2L)
        `when`(trainingEventRepository.findAllByCalendarId(id)).thenReturn(listOf(event1, event2))

        val actor = AppUser()
        service.delete(id, actor)

        verify(trainingEventPersistence).remove(event1, actor)
        verify(trainingEventPersistence).remove(event2, actor)
        verify(trainingCalendarRepository).delete(calendar)
    }

    @Test
    fun `delete allows deleting the last remaining calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.count()).thenReturn(1L)
        `when`(trainingEventRepository.findAllByCalendarId(id)).thenReturn(emptyList())

        val actor = AppUser()
        service.delete(id, actor)

        verify(trainingCalendarRepository).delete(calendar)
    }

    @Test
    fun `countSessions delegates to repository`() {
        val id = UUID.randomUUID()
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(5L)

        val count = service.countSessions(id)

        assertEquals(5L, count)
        verify(trainingEventRepository).countByCalendarId(id)
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
