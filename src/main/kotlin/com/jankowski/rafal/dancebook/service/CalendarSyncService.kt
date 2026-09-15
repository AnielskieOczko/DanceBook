package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import java.util.UUID

data class CalendarSyncOutcome(
    val calendar: TrainingCalendar,
    val success: Boolean,
    val errorMessage: String? = null,
    val adoptedCount: Int = 0,
    val updatedCount: Int = 0,
    val deletedCount: Int = 0
)

data class SyncReport(
    val outcomes: List<CalendarSyncOutcome>
) {
    val hasFailures: Boolean get() = outcomes.any { !it.success }
    val failureMessages: List<String> get() = outcomes.filter { !it.success }.mapNotNull { it.errorMessage }
}

interface CalendarSyncService {
    /**
     * Synchronizes all enabled training calendars with Google Calendar.
     * Failures on individual calendars are isolated and recorded in the report.
     */
    fun syncAll(): SyncReport

    /**
     * Synchronizes a single calendar by its ID.
     */
    fun syncCalendar(calendarId: UUID): CalendarSyncOutcome
}
