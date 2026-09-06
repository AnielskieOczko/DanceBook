package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import java.util.UUID

interface TrainingEventService {

    fun findByCurrentUser(
        eventTypes: List<TrainingEventType>? = null,
        categoryIds: List<UUID>? = null,
        attendanceStatuses: List<AttendanceStatus>? = null,
        titleSearch: String? = null
    ): List<TrainingEvent>

    fun findById(id: UUID): TrainingEvent

    fun create(request: TrainingEventRequest): TrainingEvent

    fun update(id: UUID, request: TrainingEventRequest): TrainingEvent

    fun updateAttendance(id: UUID, status: AttendanceStatus): TrainingEvent

    fun delete(id: UUID)
}
