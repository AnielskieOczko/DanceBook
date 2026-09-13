package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import java.util.UUID

/**
 * The calendar currently scoping the training views.
 *
 * A view preference rather than durable data, so it lives in the HTTP session and resets on
 * logout. Promoting it to a column on app_user later is a change behind this interface.
 */
interface ActiveCalendarService {

    /** The calendar scoping the training views, or null for "All calendars". */
    fun active(): TrainingCalendar?

    /** Sets the active calendar; null selects "All calendars". */
    fun setActive(calendarId: UUID?)

    /** Enabled calendars, plus any disabled calendar that still owns sessions. */
    fun selectable(): List<TrainingCalendar>

    /**
     * Where a new session goes: the active calendar, or the default under "All".
     * Throws [CalendarSyncException] when the active calendar is disabled.
     */
    fun creationTarget(): TrainingCalendar
}
