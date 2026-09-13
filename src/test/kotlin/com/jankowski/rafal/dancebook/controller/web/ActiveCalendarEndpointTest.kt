package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.UUID

class ActiveCalendarEndpointTest {

    private val activeCalendarService = mock(ActiveCalendarService::class.java)

    @Test
    fun `selecting a calendar stores it and asks htmx to refresh the page`() {
        val controller = ActiveCalendarController(activeCalendarService)
        val id = UUID.randomUUID()

        val response = controller.setActiveCalendar(id.toString())

        verify(activeCalendarService).setActive(id)
        assertEquals(204, response.statusCode.value())
        assertEquals("true", response.headers.getFirst("HX-Refresh"))
    }

    @Test
    fun `a malformed calendar id is rejected as bad input rather than a server error`() {
        val controller = ActiveCalendarController(activeCalendarService)

        val response = controller.setActiveCalendar("not-a-uuid")

        assertEquals(400, response.statusCode.value())
        verify(activeCalendarService, never()).setActive(any())
    }

    @Test
    fun `selecting All stores no calendar`() {
        val controller = ActiveCalendarController(activeCalendarService)

        controller.setActiveCalendar("ALL")

        verify(activeCalendarService).setActive(null)
    }
}
