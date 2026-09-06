package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventSpecification
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * The app is the only writer: every mutation goes to Google Calendar first, then to the
 * local database. None of the mutating methods are @Transactional — the calendar call is
 * network I/O and must not run with a DB connection held open. The short local write is
 * delegated to [TrainingEventPersistence], which is transactional.
 */
@Service
class TrainingEventServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingEventPersistence: TrainingEventPersistence,
    private val calendarClient: GoogleCalendarClient,
    private val appUserService: AppUserService,
    private val danceCategoryService: DanceCategoryService,
    private val materialService: MaterialService
) : TrainingEventService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingEventServiceImpl::class.java)
    }

    override fun findByCurrentUser(
        eventTypes: List<TrainingEventType>?,
        categoryIds: List<UUID>?,
        attendanceStatuses: List<AttendanceStatus>?,
        titleSearch: String?
    ): List<TrainingEvent> {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Retrieving training events for user '{}'", currentUser.username)

        val specification = TrainingEventSpecification.withFilters(
            createdBy = currentUser,
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = titleSearch
        )
        return trainingEventRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "startTime"))
    }

    override fun findById(id: UUID): TrainingEvent {
        log.debug("Retrieving training event for id {}", id)
        return trainingEventRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find training event with id $id")
        }
    }

    override fun create(request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        log.debug("User '{}' creating training event '{}'", currentUser.username, request.title)

        val event = TrainingEvent().apply {
            createdBy = currentUser
            createdAt = LocalDateTime.now()
        }
        applyRequest(event, request)

        val googleEventId = calendarClient.createEvent(event)
        event.googleEventId = googleEventId

        return try {
            trainingEventPersistence.insert(event, currentUser)
        } catch (e: Exception) {
            // The calendar event exists but the local row does not. Roll the calendar back
            // so we don't leave an orphan the app can never see or manage again.
            log.error("Local write failed after creating calendar event {} — compensating", googleEventId, e)
            runCatching { calendarClient.deleteEvent(googleEventId) }
                .onFailure { log.error("Compensating delete failed for {}; orphan calendar event left behind", googleEventId, it) }
            throw e
        }
    }

    override fun update(id: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val event = findById(id)
        checkOwnership(event, currentUser)

        log.debug("User '{}' updating training event '{}'", currentUser.username, event.title)
        applyRequest(event, request)

        val googleEventId = event.googleEventId
        if (googleEventId != null) {
            calendarClient.updateEvent(googleEventId, event)
        } else {
            // Only reachable if a previous create was interrupted between the two writes.
            log.warn("Training event {} has no google event id; creating one now", id)
            event.googleEventId = calendarClient.createEvent(event)
        }

        return try {
            trainingEventPersistence.applyUpdate(event, currentUser)
        } catch (e: Exception) {
            // No compensation is possible without a snapshot of the previous calendar
            // state, so the calendar is now ahead of the database. Log it loudly.
            log.error(
                "Local write failed after updating calendar event {}; calendar is ahead of the database",
                event.googleEventId, e
            )
            throw e
        }
    }

    override fun updateAttendance(id: UUID, status: AttendanceStatus): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val event = findById(id)
        checkOwnership(event, currentUser)

        log.debug("User '{}' marking training event '{}' as {}", currentUser.username, event.title, status)
        event.attendanceStatus = status
        event.updatedAt = LocalDateTime.now()

        // Attendance is app-only metadata Google Calendar has no field for, so this path
        // deliberately does not touch the calendar.
        return trainingEventPersistence.applyUpdate(event, currentUser)
    }

    override fun delete(id: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val event = findById(id)
        checkOwnership(event, currentUser)

        log.debug("User '{}' deleting training event '{}'", currentUser.username, event.title)
        event.googleEventId?.let { calendarClient.deleteEvent(it) }
        trainingEventPersistence.remove(event, currentUser)
    }

    private fun applyRequest(event: TrainingEvent, request: TrainingEventRequest) {
        val start = requireNotNull(request.startTime) { "Start time is required" }
        val end = requireNotNull(request.endTime) { "End time is required" }
        require(end.isAfter(start)) { "End time must be after the start time" }

        event.title = request.title
        event.startTime = start
        event.endTime = end
        event.eventType = TrainingEventType.valueOf(request.eventType)
        event.danceCategory = request.danceCategoryId?.let { danceCategoryService.findById(it) }
        event.description = request.description?.takeIf { it.isNotBlank() }
        event.material = request.materialId?.let { materialService.findById(it) }
        event.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        event.attendanceStatus = AttendanceStatus.valueOf(request.attendanceStatus)
        event.updatedAt = LocalDateTime.now()
    }

    private fun checkOwnership(event: TrainingEvent, currentUser: AppUser) {
        if (event.createdBy?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to modify this training event")
        }
    }
}
