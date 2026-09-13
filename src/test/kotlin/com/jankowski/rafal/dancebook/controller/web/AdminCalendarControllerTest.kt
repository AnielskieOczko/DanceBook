package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncException
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.ui.ConcurrentModel
import org.springframework.validation.BeanPropertyBindingResult
import java.util.UUID

class AdminCalendarControllerTest {

    private lateinit var trainingCalendarService: TrainingCalendarService
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var appUserService: AppUserService
    private lateinit var controller: AdminCalendarController

    @BeforeEach
    fun setUp() {
        trainingCalendarService = mock(TrainingCalendarService::class.java)
        googleCalendarClient = mock(GoogleCalendarClient::class.java)
        appUserService = mock(AppUserService::class.java)
        controller = AdminCalendarController(trainingCalendarService, googleCalendarClient, appUserService)
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

    @Test
    fun `setEnabled disabling only default calendar states that it is no longer default`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            isDefault = true
            enabled = true
        }
        val updated = TrainingCalendar().apply {
            this.id = id
            isDefault = false
            enabled = false
        }
        val calendars = listOf(updated)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.setEnabled(id, false)).thenReturn(updated)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.setEnabled(id, false, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("This calendar has been disabled and is no longer the default.", model["calendarError"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `showEditForm returns editCalendarRow fragment with calendar and sessionCount`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            displayName = "Cal"
        }
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(4L)

        val view = controller.showEditForm(id, model)

        assertEquals("admin/dashboard :: editCalendarRow", view)
        assertEquals(calendar, model["calendar"])
        assertEquals(4L, model["sessionCount"])
    }

    @Test
    fun `showEditForm unknown id returns calendarsSection with error`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendars = listOf(TrainingCalendar())
        `when`(trainingCalendarService.findById(id)).thenReturn(null)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.showEditForm(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Training calendar with id $id not found", model["calendarError"])
    }

    @Test
    fun `cancelEdit returns calendarRow fragment with calendar and all calendars`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            displayName = "Cal"
        }
        val calendars = listOf(calendar)
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.cancelEdit(id, model)

        assertEquals("admin/dashboard :: calendarRow", view)
        assertEquals(calendar, model["cal"])
        assertEquals(calendars, model["calendars"])
    }

    @Test
    fun `update renames calendar when Google ID unchanged without calling GoogleClient`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "cal@google.com"
            displayName = "Old Name"
        }
        val calendars = listOf(existing)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("cal@google.com", "New Name")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).update(id, request)
        verifyNoInteractions(googleCalendarClient)
        assertEquals("Calendar updated", model["calendarSuccess"])
    }

    @Test
    fun `update rejects Google ID change when calendar owns sessions`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        val calendars = listOf(existing)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(2L)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("A calendar's Google ID may only be changed while it owns no sessions.", model["calendarError"])
        verifyNoInteractions(googleCalendarClient)
    }

    @Test
    fun `update changes Google ID when session-free and verification succeeds`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        val calendars = listOf(existing)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(0L)
        `when`(googleCalendarClient.verifyCalendar("new@google.com")).thenReturn("New Summary")
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("new@google.com", "Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).update(id, request, enabled = true)
        assertEquals("Connected — \"New Summary\"", model["calendarSuccess"])
    }

    @Test
    fun `update changes Google ID when unreachable saves disabled and surfaces error`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Calendar"
        }
        val calendars = listOf(existing)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(0L)
        `when`(googleCalendarClient.verifyCalendar("unreachable@google.com")).thenThrow(
            CalendarSyncException("Could not reach Google Calendar")
        )
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("unreachable@google.com", "Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).update(id, request, enabled = false)
        assertEquals("Could not reach Google Calendar", model["calendarError"])
    }

    @Test
    fun `update changes Google ID on default calendar when another exists surfaces verification failure and refusal`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Default Calendar"
            isDefault = true
        }
        val calendars = listOf(existing, TrainingCalendar())
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(0L)
        `when`(googleCalendarClient.verifyCalendar("unreachable@google.com")).thenThrow(
            CalendarSyncException("Could not reach Google Calendar (404)")
        )
        `when`(trainingCalendarService.update(id, TrainingCalendarRequest("unreachable@google.com", "Default Calendar"), enabled = false)).thenThrow(
            IllegalStateException("Make another calendar the default before disabling this one.")
        )
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("unreachable@google.com", "Default Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals(
            "Could not reach Google Calendar (404). Make another calendar the default before disabling this one.",
            model["calendarError"]
        )
    }

    @Test
    fun `update changes Google ID on only default calendar states plainly that it is no longer default`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val existing = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "old@google.com"
            displayName = "Only Default Calendar"
            isDefault = true
        }
        val updated = TrainingCalendar().apply {
            this.id = id
            googleCalendarId = "unreachable@google.com"
            displayName = "Only Default Calendar"
            enabled = false
            isDefault = false
        }
        val calendars = listOf(updated)
        `when`(trainingCalendarService.findById(id)).thenReturn(existing)
        `when`(trainingCalendarService.countSessions(id)).thenReturn(0L)
        `when`(googleCalendarClient.verifyCalendar("unreachable@google.com")).thenThrow(
            CalendarSyncException("Could not reach Google Calendar (404)")
        )
        `when`(trainingCalendarService.update(id, TrainingCalendarRequest("unreachable@google.com", "Only Default Calendar"), enabled = false)).thenReturn(updated)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val request = TrainingCalendarRequest("unreachable@google.com", "Only Default Calendar")
        val bindingResult = BeanPropertyBindingResult(request, "request")

        val view = controller.update(id, request, bindingResult, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals(
            "Could not reach Google Calendar (404). This calendar has been disabled and is no longer the default.",
            model["calendarError"]
        )
    }

    @Test
    fun `showDeleteDialog returns confirmModal with session count consequence message`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            displayName = "Team Calendar"
            isDefault = false
        }
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(calendar, TrainingCalendar()))
        `when`(trainingCalendarService.countSessions(id)).thenReturn(3L)

        val view = controller.showDeleteDialog(id, model = model)

        assertEquals("fragments/confirm-dialog :: confirmModal", view)
        assertEquals("Delete Training Calendar", model["dialogTitle"])
        val message = model["dialogMessage"] as String
        assertTrue(message.contains("3 training sessions"))
        assertTrue(message.contains("orphaned records"))
        assertTrue(message.contains("Events in Google Calendar will not be touched"))
        assertEquals("/admin/calendars/$id/delete", model["confirmUrl"])
    }

    @Test
    fun `showDeleteDialog refuses default calendar when another exists`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            isDefault = true
        }
        val other = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            isDefault = false
        }
        val calendars = listOf(calendar, other)
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.showDeleteDialog(id, model = model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Make another calendar the default before deleting this one.", model["calendarError"])
    }

    @Test
    fun `delete delegates to service with current user and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val user = AppUser()
        val calendars = listOf(TrainingCalendar())
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.delete(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        verify(trainingCalendarService).delete(id, user)
        assertEquals("Calendar deleted", model["calendarSuccess"])
    }

    @Test
    fun `delete error sets calendarError and returns calendarsSection`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val user = AppUser()
        val calendars = listOf(TrainingCalendar())
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(trainingCalendarService.delete(id, user)).thenThrow(
            IllegalStateException("Make another calendar the default before deleting this one.")
        )
        `when`(trainingCalendarService.findAll()).thenReturn(calendars)

        val view = controller.delete(id, model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Make another calendar the default before deleting this one.", model["calendarError"])
    }
}
