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

    /** Verifies the calendar is reachable and returns Google's own summary for the calendar. */
    fun verifyCalendar(calendarId: String, requireWrite: Boolean = false): String

    /**
     * Lists changes on the given calendar since [syncToken], or performs a full listing if [syncToken] is null.
     */
    fun listChanges(calendarId: String, syncToken: String?): CalendarChangeSet
}

sealed class CalendarChange {
    abstract val googleEventId: String
    open val iCalUid: String? = null

    data class Upserted(
        override val googleEventId: String,
        override val iCalUid: String? = null,
        val title: String,
        val start: java.time.LocalDateTime,
        val end: java.time.LocalDateTime,
        val description: String?
    ) : CalendarChange()

    data class Cancelled(
        override val googleEventId: String,
        override val iCalUid: String? = null
    ) : CalendarChange()
}

data class CalendarChangeSet(
    val changes: List<CalendarChange>,
    /** Null when a full resync is required; the caller must clear its stored token. */
    val nextSyncToken: String?,
    val fullResyncRequired: Boolean,
    /** True only for a completed full sync: the window below was fetched in full. */
    val isCompleteWindow: Boolean,
    /** The window a full sync covered, for scoping deletions. Null on an incremental sync. */
    val windowStart: java.time.LocalDateTime?
)
