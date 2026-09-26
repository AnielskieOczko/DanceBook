package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class ActiveCalendarServiceTest {

    private val session = mock(HttpSession::class.java)
    private val trainingCalendarService = mock(TrainingCalendarService::class.java)
    private val trainingEventRepository = mock(TrainingEventRepository::class.java)
    private val appUserService = mock(AppUserService::class.java)
    private val currentUser = com.jankowski.rafal.dancebook.model.AppUser().apply {
        id = UUID.randomUUID()
        username = "testuser"
        role = com.jankowski.rafal.dancebook.model.Role.ADMIN
    }

    private lateinit var service: ActiveCalendarService

    private fun calendar(name: String, isEnabled: Boolean = true) = TrainingCalendar().apply {
        id = UUID.randomUUID()
        displayName = name
        enabled = isEnabled
    }

    @BeforeEach
    fun setUp() {
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        service = ActiveCalendarServiceImpl(session, trainingCalendarService, trainingEventRepository, appUserService)
    }

    @Test
    fun `an unset session resolves to the default calendar`() {
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn(null)
        `when`(trainingCalendarService.findDefault(currentUser)).thenReturn(default)

        assertEquals(default.id, service.active()?.id)
    }

    @Test
    fun `ALL resolves to no calendar`() {
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")

        assertNull(service.active())
    }

    @Test
    fun `setting All stores the ALL sentinel`() {
        service.setActive(null)

        verify(session).setAttribute("activeCalendarId", "ALL")
    }

    @Test
    fun `creationTarget falls back to the default under All`() {
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")
        `when`(trainingCalendarService.requireDefault(currentUser)).thenReturn(default)

        assertEquals(default.id, service.creationTarget().id)
    }

    @Test
    fun `creationTarget refuses a disabled active calendar rather than silently using the default`() {
        val disabled = calendar("Retired", isEnabled = false)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(disabled.id.toString())
        `when`(trainingCalendarService.findById(disabled.id!!)).thenReturn(disabled)

        val error = assertThrows(CalendarSyncException::class.java) { service.creationTarget() }
        assertEquals(
            "Retired is disabled — choose another calendar to create a session.",
            error.message
        )
    }

    @Test
    fun `selectable includes enabled calendars and excludes a disabled one with no sessions`() {
        val enabled = calendar("Club")
        val disabledUnused = calendar("Typo", isEnabled = false)
        `when`(trainingCalendarService.findAllVisibleTo(currentUser)).thenReturn(listOf(enabled, disabledUnused))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(emptyList())

        assertEquals(listOf(enabled.id), service.selectable().map { it.id })
    }

    @Test
    fun `selectable keeps a disabled calendar that still owns sessions`() {
        val enabled = calendar("Club")
        val disabledUsed = calendar("Retired", isEnabled = false)
        `when`(trainingCalendarService.findAllVisibleTo(currentUser)).thenReturn(listOf(enabled, disabledUsed))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(listOf(disabledUsed.id!!))
        assertEquals(listOf(enabled.id, disabledUsed.id), service.selectable().map { it.id })
    }

    @Test
    fun `a stale calendar id in session falls back to the default calendar`() {
        val staleId = UUID.randomUUID()
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn(staleId.toString())
        `when`(trainingCalendarService.findById(staleId)).thenReturn(null)
        `when`(trainingCalendarService.findDefault(currentUser)).thenReturn(default)

        assertEquals(default.id, service.active()?.id)
    }

    @Test
    fun `validateCreationTarget under All calendars refuses null calendarId`() {
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")

        val error = assertThrows(CalendarSyncException::class.java) {
            service.validateCreationTarget(null)
        }
        assertEquals(
            "No target calendar specified — choose a calendar to create a session.",
            error.message
        )
    }

    @Test
    fun `validateCreationTarget under All calendars refuses calendar that does not exist`() {
        val missingId = UUID.randomUUID()
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")
        `when`(trainingCalendarService.findById(missingId)).thenReturn(null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.validateCreationTarget(missingId)
        }
        assertTrue(error.message!!.contains("not found"))
    }

    @Test
    fun `validateCreationTarget under All calendars refuses disabled calendar`() {
        val disabled = calendar("Retired", isEnabled = false)
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")
        `when`(trainingCalendarService.findById(disabled.id!!)).thenReturn(disabled)

        val error = assertThrows(CalendarSyncException::class.java) {
            service.validateCreationTarget(disabled.id)
        }
        assertEquals(
            "Retired is disabled — choose another calendar to create a session.",
            error.message
        )
    }

    @Test
    fun `validateCreationTarget under All calendars accepts enabled calendar`() {
        val enabled = calendar("Club", isEnabled = true)
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")
        `when`(trainingCalendarService.findById(enabled.id!!)).thenReturn(enabled)

        assertEquals(enabled.id, service.validateCreationTarget(enabled.id).id)
    }

    @Test
    fun `validateCreationTarget under specific active calendar refuses when active calendar is disabled`() {
        val disabled = calendar("Retired", isEnabled = false)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(disabled.id.toString())
        `when`(trainingCalendarService.findById(disabled.id!!)).thenReturn(disabled)

        val error = assertThrows(CalendarSyncException::class.java) {
            service.validateCreationTarget(disabled.id)
        }
        assertEquals(
            "Retired is disabled — choose another calendar to create a session.",
            error.message
        )
    }

    @Test
    fun `validateCreationTarget under specific active calendar refuses different calendarId`() {
        val club = calendar("Club", isEnabled = true)
        val other = calendar("Home", isEnabled = true)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(club.id.toString())
        `when`(trainingCalendarService.findById(club.id!!)).thenReturn(club)
        `when`(trainingCalendarService.findById(other.id!!)).thenReturn(other)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.validateCreationTarget(other.id)
        }
        assertTrue(error.message!!.contains("active calendar is 'Club'"))
    }

    @Test
    fun `validateCreationTarget under specific active calendar refuses non-existent calendarId`() {
        val club = calendar("Club", isEnabled = true)
        val missingId = UUID.randomUUID()
        `when`(session.getAttribute("activeCalendarId")).thenReturn(club.id.toString())
        `when`(trainingCalendarService.findById(club.id!!)).thenReturn(club)
        `when`(trainingCalendarService.findById(missingId)).thenReturn(null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.validateCreationTarget(missingId)
        }
        assertTrue(error.message!!.contains("not found"))
    }

    @Test
    fun `validateCreationTarget under specific active calendar accepts matching calendarId`() {
        val club = calendar("Club", isEnabled = true)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(club.id.toString())
        `when`(trainingCalendarService.findById(club.id!!)).thenReturn(club)

        assertEquals(club.id, service.validateCreationTarget(club.id).id)
    }

    @Test
    fun `validateCreationTarget under specific active calendar allows null calendarId and resolves to active`() {
        val club = calendar("Club", isEnabled = true)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(club.id.toString())
        `when`(trainingCalendarService.findById(club.id!!)).thenReturn(club)

        assertEquals(club.id, service.validateCreationTarget(null).id)
    }
}
