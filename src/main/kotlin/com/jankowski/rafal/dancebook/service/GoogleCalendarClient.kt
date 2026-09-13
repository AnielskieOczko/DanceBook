package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingEvent

/**
 * Thin wrapper over the Google Calendar API for training calendars.
 * Exists as an interface so the sync failure paths can be unit tested without network I/O.
 */
interface GoogleCalendarClient {

    /** Creates the calendar event on the given calendar and returns its Google event id. */
    fun createEvent(calendarId: String, event: TrainingEvent): String

    fun updateEvent(calendarId: String, googleEventId: String, event: TrainingEvent)

    /** Deletes the calendar event. An already-deleted event (404/410) is not an error. */
    fun deleteEvent(calendarId: String, googleEventId: String)
}
