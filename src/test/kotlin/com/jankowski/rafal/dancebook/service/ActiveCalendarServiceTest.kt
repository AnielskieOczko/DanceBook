package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    private lateinit var service: ActiveCalendarService

    private fun calendar(name: String, isEnabled: Boolean = true) = TrainingCalendar().apply {
        id = UUID.randomUUID()
        googleCalendarId = "$name@group.calendar.google.com"
        displayName = name
        enabled = isEnabled
    }

    @BeforeEach
    fun setUp() {
        service = ActiveCalendarServiceImpl(session, trainingCalendarService, trainingEventRepository)
    }

    @Test
    fun `an unset session resolves to the default calendar`() {
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn(null)
        `when`(trainingCalendarService.findDefault()).thenReturn(default)

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
        `when`(trainingCalendarService.requireDefault()).thenReturn(default)

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
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(enabled, disabledUnused))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(emptyList())

        assertEquals(listOf(enabled.id), service.selectable().map { it.id })
    }

    @Test
    fun `selectable keeps a disabled calendar that still owns sessions`() {
        val enabled = calendar("Club")
        val disabledUsed = calendar("Retired", isEnabled = false)
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(enabled, disabledUsed))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(listOf(disabledUsed.id!!))
        assertEquals(listOf(enabled.id, disabledUsed.id), service.selectable().map { it.id })
    }

    @Test
    fun `a stale calendar id in session falls back to the default calendar`() {
        val staleId = UUID.randomUUID()
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn(staleId.toString())
        `when`(trainingCalendarService.findById(staleId)).thenReturn(null)
        `when`(trainingCalendarService.findDefault()).thenReturn(default)

        assertEquals(default.id, service.active()?.id)
    }
}
