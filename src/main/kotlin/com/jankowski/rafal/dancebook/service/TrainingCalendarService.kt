package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import java.util.UUID

interface TrainingCalendarService {

    fun findAll(): List<TrainingCalendar>

    fun findAllEnabled(): List<TrainingCalendar>

    fun findById(id: UUID): TrainingCalendar?

    fun findDefault(): TrainingCalendar?

    fun requireDefault(): TrainingCalendar

    fun add(request: TrainingCalendarRequest, enabled: Boolean = true): TrainingCalendar

    fun update(id: UUID, request: TrainingCalendarRequest, enabled: Boolean? = null): TrainingCalendar

    fun delete(id: UUID, actor: AppUser)

    fun countSessions(id: UUID): Long

    fun setDefault(id: UUID): TrainingCalendar

    fun setEnabled(id: UUID, enabled: Boolean): TrainingCalendar

    /**
     * Seeds the default training calendar from `google.calendar.calendar-id` on startup
     * if set, and backfills any existing training events missing a calendar link.
     */
    fun bootstrapDefaultCalendar()
}
