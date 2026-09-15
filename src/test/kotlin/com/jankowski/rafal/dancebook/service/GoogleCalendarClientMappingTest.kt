package com.jankowski.rafal.dancebook.service

import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GoogleCalendarClientMappingTest {

    private lateinit var client: GoogleCalendarClientImpl

    @BeforeEach
    fun setUp() {
        val properties = GoogleCalendarProperties(
            timeZone = "Europe/Warsaw"
        )
        // Instantiation must NOT trigger lazy credentials or transport
        client = GoogleCalendarClientImpl(properties)
    }

    @Test
    fun `maps timed event to Upserted change`() {
        val event = Event().apply {
            id = "event-123"
            summary = "Waltz Lesson"
            description = "Bring shoes"
            start = EventDateTime().setDateTime(DateTime("2026-09-15T18:00:00+02:00"))
            end = EventDateTime().setDateTime(DateTime("2026-09-15T19:30:00+02:00"))
            status = "confirmed"
        }

        val change = client.toCalendarChange(event)
        assertNotNull(change)
        assertTrue(change is CalendarChange.Upserted)
        val upserted = change as CalendarChange.Upserted

        assertEquals("event-123", upserted.googleEventId)
        assertEquals("Waltz Lesson", upserted.title)
        assertEquals("Bring shoes", upserted.description)
        assertEquals(LocalDateTime.of(2026, 9, 15, 18, 0), upserted.start)
        assertEquals(LocalDateTime.of(2026, 9, 15, 19, 30), upserted.end)
    }

    @Test
    fun `maps all-day event midnight to midnight accounting for exclusive end date`() {
        // Google all-day event for Sept 15: start is 2026-09-15, end is 2026-09-16 (exclusive)
        val event = Event().apply {
            id = "all-day-1"
            summary = "Dance Workshop"
            start = EventDateTime().setDate(DateTime(true, DateTime.parseRfc3339("2026-09-15").value, null))
            end = EventDateTime().setDate(DateTime(true, DateTime.parseRfc3339("2026-09-16").value, null))
            status = "confirmed"
        }

        val change = client.toCalendarChange(event)
        assertNotNull(change)
        assertTrue(change is CalendarChange.Upserted)
        val upserted = change as CalendarChange.Upserted

        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0), upserted.start)
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), upserted.end)
        assertEquals("Dance Workshop", upserted.title)
    }

    @Test
    fun `all-day event with non-after end defaults to plus one day`() {
        val event = Event().apply {
            id = "all-day-same-day"
            summary = "One Day Camp"
            start = EventDateTime().setDate(DateTime(true, DateTime.parseRfc3339("2026-09-15").value, null))
            end = EventDateTime().setDate(DateTime(true, DateTime.parseRfc3339("2026-09-15").value, null))
        }

        val change = client.toCalendarChange(event)
        assertNotNull(change)
        assertTrue(change is CalendarChange.Upserted)
        val upserted = change as CalendarChange.Upserted

        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0), upserted.start)
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), upserted.end)
    }

    @Test
    fun `maps cancelled status to Cancelled change`() {
        val event = Event().apply {
            id = "deleted-event"
            status = "cancelled"
        }

        val change = client.toCalendarChange(event)
        assertNotNull(change)
        assertTrue(change is CalendarChange.Cancelled)
        assertEquals("deleted-event", change!!.googleEventId)
    }

    @Test
    fun `returns null if event has no id`() {
        val event = Event().apply {
            summary = "No ID event"
            status = "confirmed"
        }

        assertNull(client.toCalendarChange(event))
    }

    @Test
    fun `event with null summary defaults title to empty string`() {
        val event = Event().apply {
            id = "no-summary"
            summary = null
            start = EventDateTime().setDateTime(DateTime("2026-09-15T18:00:00+02:00"))
            end = EventDateTime().setDateTime(DateTime("2026-09-15T19:00:00+02:00"))
        }

        val change = client.toCalendarChange(event) as CalendarChange.Upserted
        assertEquals("", change.title)
    }

    @Test
    fun `isWindowComplete is false for incremental sync even if drain completed`() {
        assertFalse(client.isWindowComplete(isFullSync = false, drainCompleted = true))
    }

    @Test
    fun `isWindowComplete is false for full sync if drain did not complete`() {
        assertFalse(client.isWindowComplete(isFullSync = true, drainCompleted = false))
    }

    @Test
    fun `isWindowComplete is true only for full sync when drain completed`() {
        assertTrue(client.isWindowComplete(isFullSync = true, drainCompleted = true))
    }
}
