package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.CalendarSyncException
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.ui.ConcurrentModel
import org.springframework.validation.BeanPropertyBindingResult
import java.util.UUID

class AdminCalendarControllerTest {

    private lateinit var trainingCalendarService: TrainingCalendarService
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var controller: AdminCalendarController

    @BeforeEach
    fun setUp() {
        trainingCalendarService = mock(TrainingCalendarService::class.java)
        googleCalendarClient = mock(GoogleCalendarClient::class.java)
        controller = AdminCalendarController(trainingCalendarService, googleCalendarClient)
    }

    @Test
    fun `list returns calendarsSection fragment with all calendars`() {
        val model = ConcurrentModel()
        val calendars = listOf(TrainingCalendar().apply { displayName = "Test Cal" })
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.list(model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `showAddForm returns addCalendarForm fragment`() {
        val model = ConcurrentModel()
        val view = controller.showAddForm(model)
        assertEquals("admin/dashboard :: addCalendarForm", view)
    }

    @Test
    fun `cancelAddForm returns empty fragment`() {
        val view = controller.cancelAddForm()
        assertEquals("admin/dashboard :: empty", view)
    }

    @Test
    fun `add successfully verifies, saves enabled calendar and returns calendarsSection`() {
        val model = ConcurrentModel()
        val request = TrainingCalendarRequest("cal@google.com", "Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")
        val calendars = listOf(TrainingCalendar())
        `when`(googleCalendarClient.verifyCalendar("cal@google.com")).thenReturn("Calendar Summary")
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.add(request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(googleCalendarClient).verifyCalendar("cal@google.com")
        verify(trainingCalendarService).add(request, enabled = true)
        assertEquals(calendars, model["calendars"])
        assertEquals("Connected — \"Calendar Summary\"", model["calendarSuccess"])
    }

    @Test
    fun `add saves calendar disabled when verification fails and surfaces reason`() {
        val model = ConcurrentModel()
        val request = TrainingCalendarRequest("unshared@google.com", "Unshared")
        val bindingResult = BeanPropertyBindingResult(request, "request")
        val calendars = listOf(TrainingCalendar())
        `when`(googleCalendarClient.verifyCalendar("unshared@google.com")).thenThrow(
            CalendarSyncException("Google Calendar verify failed (403): not shared with this app's Google account")
        )
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.add(request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).add(request, enabled = false)
        assertEquals("Google Calendar verify failed (403): not shared with this app's Google account", model["calendarError"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `add duplicate id sets calendarError and showAddForm and returns calendarsSection`() {
        val model = ConcurrentModel()
        val request = TrainingCalendarRequest("dup@google.com", "Duplicate")
        val bindingResult = BeanPropertyBindingResult(request, "request")
        `when`(googleCalendarClient.verifyCalendar("dup@google.com")).thenReturn("Duplicate")
        `when`(trainingCalendarService.add(request, enabled = true)).thenThrow(
            IllegalArgumentException("A calendar with Google Calendar ID 'dup@google.com' already exists.")
        )
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.add(request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("A calendar with Google Calendar ID 'dup@google.com' already exists.", model["calendarError"])
        assertEquals(true, model["showAddForm"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `verify endpoint returns calendarsSection and reports success`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "cal@google.com"
            displayName = "My Calendar"
        }
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(googleCalendarClient.verifyCalendar("cal@google.com")).thenReturn("My Calendar Google Summary")
        val calendars = listOf(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.verify(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Connected — \"My Calendar Google Summary\"", model["calendarSuccess"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `verify endpoint returns calendarsSection and reports failure`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "missing@google.com"
            displayName = "Missing Calendar"
        }
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(googleCalendarClient.verifyCalendar("missing@google.com")).thenThrow(
            CalendarSyncException("Google Calendar verify failed (404): no such calendar")
        )
        val calendars = listOf(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.verify(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Google Calendar verify failed (404): no such calendar", model["calendarError"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `makeDefault delegates to service and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.makeDefault(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).setDefault(id)
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `makeDefault error sets calendarError and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        `when`(trainingCalendarService.setDefault(id)).thenThrow(
            IllegalArgumentException("Calendar not found")
        )
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.makeDefault(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Calendar not found", model["calendarError"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `setEnabled delegates to service and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.setEnabled(id, true, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).setEnabled(id, true)
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `setEnabled error when disabling default sets calendarError and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        `when`(trainingCalendarService.setEnabled(id, false)).thenThrow(
            IllegalStateException("Make another calendar the default before disabling this one.")
        )
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.setEnabled(id, false, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Make another calendar the default before disabling this one.", model["calendarError"])
        assertEquals(calendars, model["calendars"])
    }
}
