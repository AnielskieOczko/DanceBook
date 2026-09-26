package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.model.CalendarSource
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CalendarSourceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.Optional
import java.util.UUID

class TrainingCalendarServiceTest {

    private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingEventPersistence: TrainingEventPersistence
    private lateinit var calendarProperties: GoogleCalendarProperties
    private lateinit var appUserService: AppUserService
    private lateinit var appUserRepository: AppUserRepository
    private lateinit var calendarSourceRepository: CalendarSourceRepository
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private val currentUser = AppUser().apply { id = UUID.randomUUID(); username = "admin"; role = Role.ADMIN }
    private lateinit var service: TrainingCalendarServiceImpl

    @BeforeEach
    fun setUp() {
        currentUser.defaultCalendar = null
        trainingCalendarRepository = mock(TrainingCalendarRepository::class.java)
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingEventPersistence = mock(TrainingEventPersistence::class.java)
        calendarProperties = GoogleCalendarProperties(calendarId = "seed-cal@group.calendar.google.com")
        appUserService = mock(AppUserService::class.java)
        appUserRepository = mock(AppUserRepository::class.java)
        calendarSourceRepository = mock(CalendarSourceRepository::class.java)
        googleCalendarClient = mock(GoogleCalendarClient::class.java)
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)
        `when`(appUserService.getRootAdmin()).thenReturn(currentUser)
        `when`(googleCalendarClient.verifyCalendar(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn("Verified")
        service = TrainingCalendarServiceImpl(
            trainingCalendarRepository,
            trainingEventRepository,
            trainingEventPersistence,
            calendarProperties,
            appUserService,
            appUserRepository,
            calendarSourceRepository,
            googleCalendarClient
        )
    }

    @Test
    fun `bootstrap creates and backfills when seed calendar does not exist`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("seed-cal@group.calendar.google.com"))
            .thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }
        `when`(trainingEventRepository.assignMissingCalendar(any(TrainingCalendar())))
            .thenReturn(5)

        service.bootstrapDefaultCalendar()

        val captor = ArgumentCaptor.forClass(TrainingCalendar::class.java)
        verify(trainingCalendarRepository).save(capture(captor, TrainingCalendar()))
        val saved = captor.value
        assertEquals("seed-cal@group.calendar.google.com", saved.writeTarget?.googleCalendarId)
        assertTrue(saved.isDefaultFor(currentUser))
        assertTrue(saved.enabled)
        verify(trainingEventRepository).assignMissingCalendar(saved)
    }

    @Test
    fun `bootstrap a second call creates nothing and reuses existing calendar`() {
        val existing = TrainingCalendar().apply {
            id = UUID.randomUUID()
            addSource("seed-cal@group.calendar.google.com", isWriteTarget = true)
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
            blankProperties,
            appUserService,
            appUserRepository,
            calendarSourceRepository,
            googleCalendarClient
        )

        blankService.bootstrapDefaultCalendar()

        verifyNoInteractions(trainingCalendarRepository)
        verifyNoInteractions(trainingEventRepository)
    }

    @Test
    fun `bootstrap does not steal default from an admin-chosen calendar`() {
        val adminDefault = TrainingCalendar().apply {
            id = UUID.randomUUID()
            addSource("admin-cal@group.calendar.google.com", isWriteTarget = true)
        }
        currentUser.defaultCalendar = adminDefault
        `when`(trainingCalendarRepository.findByGoogleCalendarId("seed-cal@group.calendar.google.com"))
            .thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }

        service.bootstrapDefaultCalendar()

        val captor = ArgumentCaptor.forClass(TrainingCalendar::class.java)
        verify(trainingCalendarRepository).save(capture(captor, TrainingCalendar()))
        val saved = captor.value
        assertEquals("seed-cal@group.calendar.google.com", saved.writeTarget?.googleCalendarId)
        assertFalse(saved.isDefaultFor(currentUser), "Seed must not steal default when an admin default already exists")
    }

    @Test
    fun `add rejects a duplicate Google id`() {
        val existing = TrainingCalendar().apply {
            id = UUID.randomUUID()
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
        assertTrue(first.isDefaultFor(currentUser))

        `when`(trainingCalendarRepository.findByGoogleCalendarId("second@group.calendar.google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(1L)

        val second = service.add(TrainingCalendarRequest("second@group.calendar.google.com", "Second Calendar"))
        assertFalse(second.isDefaultFor(currentUser))
    }

    @Test
    fun `add with enabled false saves a disabled and non-default calendar even if first`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("disabled@group.calendar.google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.count()).thenReturn(0L)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar())))
            .thenAnswer { it.getArgument<TrainingCalendar>(0).apply { id = UUID.randomUUID() } }

        val calendar = service.add(TrainingCalendarRequest("disabled@group.calendar.google.com", "Disabled"), enabled = false)
        assertFalse(calendar.enabled)
        assertFalse(calendar.isDefaultFor(currentUser))
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
    fun `setDefault sets user default and enables the calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            enabled = false
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))

        val result = service.setDefault(id)

        assertEquals(calendar, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        assertTrue(result.enabled)
    }

    @Test
    fun `disabling the default calendar falls back to another calendar when another exists`() {
        val id = UUID.randomUUID()
        val otherCalendar = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            this.owner = currentUser
            enabled = true
        }
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar, otherCalendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val updated = service.setEnabled(id, false)

        assertFalse(updated.enabled)
        assertEquals(otherCalendar, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `disabling the default calendar clears default when it is the only calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val updated = service.setEnabled(id, false)

        assertFalse(updated.enabled)
        assertEquals(null, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `requireDefault throws CalendarSyncException with actionable message when no default exists`() {
        currentUser.defaultCalendar = null
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser)).thenReturn(emptyList())
        `when`(trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()).thenReturn(emptyList())

        val exception = assertThrows(CalendarSyncException::class.java) {
            service.requireDefault(currentUser)
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
            this.owner = currentUser
            displayName = "Old Name"
            addSource("cal@google.com", isWriteTarget = true)
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("cal@google.com", "New Name")
        val updated = service.update(id, request)

        assertEquals("New Name", updated.displayName)
        assertEquals("cal@google.com", updated.writeTarget?.googleCalendarId)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `update changes Google ID when calendar owns no sessions`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            displayName = "Calendar"
            addSource("old@google.com", isWriteTarget = true)
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val updated = service.update(id, request, enabled = true)

        assertEquals("new@google.com", updated.writeTarget?.googleCalendarId)
        assertTrue(updated.enabled)
    }

    @Test
    fun `update rejects Google ID change when calendar owns sessions`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            displayName = "Calendar"
            addSource("old@google.com", isWriteTarget = true)
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
            this.owner = currentUser
            displayName = "Calendar"
            addSource("old@google.com", isWriteTarget = true)
        }
        val other = TrainingCalendar().apply {
            this.id = otherId
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
            this.owner = currentUser
            displayName = "Calendar"
            enabled = true
            addSource("old@google.com", isWriteTarget = true)
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
    fun `update with enabled false on default calendar falls back when another exists`() {
        val id = UUID.randomUUID()
        val otherCalendar = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            this.owner = currentUser
            enabled = true
        }
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
            displayName = "Default Calendar"
            enabled = true
            addSource("old@google.com", isWriteTarget = true)
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar, otherCalendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Default Calendar")
        val updated = service.update(id, request, enabled = false)

        assertFalse(updated.enabled)
        assertEquals(otherCalendar, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `update with enabled false on default calendar saves disabled when it is the only calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
            displayName = "Only Default Calendar"
            enabled = true
            addSource("old@google.com", isWriteTarget = true)
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.countByCalendarId(id)).thenReturn(0L)
        `when`(trainingCalendarRepository.findByGoogleCalendarId("new@google.com")).thenReturn(null)
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar))
        `when`(trainingCalendarRepository.save(any(TrainingCalendar()))).thenAnswer { it.getArgument(0) }

        val request = TrainingCalendarRequest("new@google.com", "Only Default Calendar")
        val updated = service.update(id, request, enabled = false)

        assertEquals("new@google.com", updated.writeTarget?.googleCalendarId)
        assertFalse(updated.enabled)
        assertEquals(null, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).save(calendar)
    }

    @Test
    fun `delete default calendar falls back to another calendar when another exists`() {
        val id = UUID.randomUUID()
        val otherCalendar = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            this.owner = currentUser
            enabled = true
        }
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar, otherCalendar))
        `when`(trainingEventRepository.findAllByCalendarId(id)).thenReturn(emptyList())

        service.delete(id, currentUser)

        assertEquals(otherCalendar, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).delete(calendar)
    }

    @Test
    fun `delete removes calendar and its sessions through persistence without calling Google`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
        }
        val event1 = TrainingEvent().apply { this.id = UUID.randomUUID(); title = "Event 1" }
        val event2 = TrainingEvent().apply { this.id = UUID.randomUUID(); title = "Event 2" }

        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar, TrainingCalendar()))
        `when`(trainingEventRepository.findAllByCalendarId(id)).thenReturn(listOf(event1, event2))

        service.delete(id, currentUser)

        verify(trainingEventPersistence).remove(event1, currentUser)
        verify(trainingEventPersistence).remove(event2, currentUser)
        verify(trainingCalendarRepository).delete(calendar)
    }

    @Test
    fun `delete allows deleting the last remaining calendar`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            currentUser.defaultCalendar = this
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(currentUser))
            .thenReturn(listOf(calendar))
        `when`(trainingEventRepository.findAllByCalendarId(id)).thenReturn(emptyList())

        service.delete(id, currentUser)

        assertEquals(null, currentUser.defaultCalendar)
        verify(appUserRepository).save(currentUser)
        verify(trainingCalendarRepository).delete(calendar)
    }

    @Test
    fun `add verifies Google Calendar access and throws if verification fails`() {
        `when`(trainingCalendarRepository.findByGoogleCalendarId("inaccessible@group.calendar.google.com")).thenReturn(null)
        `when`(googleCalendarClient.verifyCalendar("inaccessible@group.calendar.google.com", true))
            .thenThrow(CalendarSyncException("Access denied"))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.add(TrainingCalendarRequest("inaccessible@group.calendar.google.com", "Inaccessible"))
        }

        assertTrue(ex.message!!.contains("Cannot access Google Calendar"))
        verify(trainingCalendarRepository, never()).save(any(TrainingCalendar()))
    }

    @Test
    fun `addSource verifies Google Calendar access for write target and throws if verification fails`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(googleCalendarClient.verifyCalendar("bad@google.com", true))
            .thenThrow(CalendarSyncException("Access denied"))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.addSource(id, "bad@google.com", "Bad", isWriteTarget = true, actor = currentUser)
        }

        assertTrue(ex.message!!.contains("Cannot access Google Calendar"))
        verify(calendarSourceRepository, never()).save(any(CalendarSource()))
    }

    @Test
    fun `addSource verifies Google Calendar read access for non-write target`() {
        val id = UUID.randomUUID()
        val existingSource = CalendarSource().apply {
            googleCalendarId = "primary@google.com"
            isWriteTarget = true
        }
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            sources.add(existingSource)
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))
        `when`(calendarSourceRepository.save(any(CalendarSource()))).thenAnswer { it.getArgument(0) }

        val result = service.addSource(id, "secondary@google.com", "Secondary", isWriteTarget = false, actor = currentUser)

        assertFalse(result.isWriteTarget)
        verify(googleCalendarClient).verifyCalendar("secondary@google.com", false)
        verify(calendarSourceRepository).save(any(CalendarSource()))
    }

    @Test
    fun `setWriteTarget verifies Google Calendar access with requireWrite and throws if verification fails`() {
        val calId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        val source = CalendarSource().apply {
            id = sourceId
            googleCalendarId = "readonly@google.com"
            isWriteTarget = false
        }
        val calendar = TrainingCalendar().apply {
            this.id = calId
            this.owner = currentUser
            sources.add(source)
            source.calendar = this
        }
        `when`(trainingCalendarRepository.findById(calId)).thenReturn(Optional.of(calendar))
        `when`(googleCalendarClient.verifyCalendar("readonly@google.com", true))
            .thenThrow(CalendarSyncException("Read only permission"))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.setWriteTarget(calId, sourceId, currentUser)
        }

        assertTrue(ex.message!!.contains("Cannot set 'readonly@google.com' as write target"))
        verify(calendarSourceRepository, never()).clearWriteTargets(calId)
    }

    @Test
    fun `setDefault throws when non-owner tries to set disabled calendar`() {
        val otherUser = AppUser().apply { id = UUID.randomUUID(); username = "other"; role = Role.USER }
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            this.owner = currentUser
            this.visibility = com.jankowski.rafal.dancebook.model.Visibility.PUBLIC
            enabled = false
        }
        `when`(trainingCalendarRepository.findById(id)).thenReturn(Optional.of(calendar))

        val ex = assertThrows(IllegalStateException::class.java) {
            service.setDefault(id, otherUser)
        }

        assertEquals("Cannot set a disabled calendar as default.", ex.message)
        verify(trainingCalendarRepository, never()).save(calendar)
        verify(appUserRepository, never()).save(otherUser)
    }

    @Test
    fun `findDefault falls back in memory without saving to repository`() {
        val otherUser = AppUser().apply { id = UUID.randomUUID(); username = "user"; role = Role.USER }
        val calendar = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            this.owner = otherUser
            this.enabled = true
        }
        `when`(trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(otherUser)).thenReturn(listOf(calendar))

        val result = service.findDefault(otherUser)

        assertEquals(calendar, result)
        verify(appUserRepository, never()).save(otherUser)
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
