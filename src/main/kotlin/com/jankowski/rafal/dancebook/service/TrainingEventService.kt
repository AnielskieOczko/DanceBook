package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkAttendanceResult
import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import java.time.LocalDateTime
import java.util.UUID

interface TrainingEventService {

    companion object {
        /**
         * One web action becomes one Google API call per session on mutating paths,
         * so bulk actions over a selection are capped to fit comfortably in a normal request.
         */
        const val MAX_BULK_ACTION = 50

        fun bulkCapRefusal(count: Int, action: String = "delete"): String =
            "Cannot $action $count sessions at once: maximum is $MAX_BULK_ACTION."
    }

    fun findByCurrentUser(
        eventTypes: List<TrainingEventType>? = null,
        categoryIds: List<UUID>? = null,
        attendanceStatuses: List<AttendanceStatus>? = null,
        titleSearch: String? = null,
        awaitingConfirmation: Boolean? = null,
        calendarId: UUID? = null
    ): List<TrainingEvent>

    fun findById(id: UUID): TrainingEvent

    fun create(request: TrainingEventRequest): TrainingEvent

    fun update(id: UUID, request: TrainingEventRequest): TrainingEvent

    fun updateAttendance(id: UUID, status: AttendanceStatus): TrainingEvent

    fun bulkUpdateAttendance(sessionIds: List<UUID>, status: AttendanceStatus): BulkAttendanceResult

    fun bulkDelete(sessionIds: List<UUID>): BulkDeleteResult

    /** Moves a session to a new slot, e.g. after dragging it in the calendar view. */
    fun reschedule(id: UUID, start: LocalDateTime, end: LocalDateTime): TrainingEvent

    /** Sessions overlapping the given window, for the calendar view. */
    fun findInRange(
        from: LocalDateTime,
        to: LocalDateTime,
        calendarId: UUID? = null
    ): List<TrainingEvent>

    fun delete(id: UUID)
}
