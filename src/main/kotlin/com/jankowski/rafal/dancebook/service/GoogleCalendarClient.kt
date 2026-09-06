package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingEvent

/**
 * Thin wrapper over the Google Calendar API for the one dedicated training calendar.
 * Exists as an interface so the sync failure paths can be unit tested without network I/O.
 */
interface GoogleCalendarClient {

    /** Creates the calendar event and returns its Google event id. */
    fun createEvent(event: TrainingEvent): String

    fun updateEvent(googleEventId: String, event: TrainingEvent)

    /** Deletes the calendar event. An already-deleted event (404/410) is not an error. */
    fun deleteEvent(googleEventId: String)
}
