package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarSource
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import java.util.UUID

interface TrainingCalendarService {

    fun findAll(): List<TrainingCalendar>

    fun findAllVisibleTo(user: AppUser?): List<TrainingCalendar>

    fun findAllEnabled(): List<TrainingCalendar>

    fun findById(id: UUID): TrainingCalendar?

    fun findByIdVisibleTo(id: UUID, user: AppUser?): TrainingCalendar?

    fun findDefault(user: AppUser? = null): TrainingCalendar?

    fun requireDefault(user: AppUser? = null): TrainingCalendar

    fun add(request: TrainingCalendarRequest, enabled: Boolean = true): TrainingCalendar

    fun add(request: TrainingCalendarRequest, actor: AppUser, enabled: Boolean = true): TrainingCalendar

    fun update(id: UUID, request: TrainingCalendarRequest, enabled: Boolean? = null): TrainingCalendar

    fun update(id: UUID, request: TrainingCalendarRequest, actor: AppUser, enabled: Boolean? = null): TrainingCalendar

    fun delete(id: UUID, actor: AppUser)

    fun countSessions(id: UUID): Long

    fun setDefault(id: UUID): TrainingCalendar

    fun setDefault(id: UUID, user: AppUser): TrainingCalendar

    fun setEnabled(id: UUID, enabled: Boolean): TrainingCalendar

    fun setVisibility(id: UUID, visibility: Visibility, actor: AppUser): TrainingCalendar

    fun addSource(
        calendarId: UUID,
        googleCalendarId: String,
        displayName: String? = null,
        isWriteTarget: Boolean = false,
        actor: AppUser
    ): CalendarSource

    fun removeSource(calendarId: UUID, sourceId: UUID, actor: AppUser)

    fun setWriteTarget(calendarId: UUID, sourceId: UUID, actor: AppUser): CalendarSource

    /**
     * Seeds the default training calendar from `google.calendar.calendar-id` on startup
     * if set, and backfills any existing training events missing a calendar link.
     */
    fun bootstrapDefaultCalendar()
}
