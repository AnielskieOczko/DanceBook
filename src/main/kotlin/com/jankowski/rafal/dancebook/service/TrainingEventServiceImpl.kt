package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkAttendanceResult
import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventSpecification
import jakarta.persistence.EntityManager
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
    private val trainingCalendarService: TrainingCalendarService,
    private val appUserService: AppUserService,
    private val danceCategoryService: DanceCategoryService,
    private val materialService: MaterialService,
    private val entityManager: EntityManager,
    private val richTextService: RichTextService
) : TrainingEventService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingEventServiceImpl::class.java)
    }

    override fun findByCurrentUser(
        eventTypes: List<TrainingEventType>?,
        categoryIds: List<UUID>?,
        attendanceStatuses: List<AttendanceStatus>?,
        titleSearch: String?,
        awaitingConfirmation: Boolean?,
        calendarId: UUID?
    ): List<TrainingEvent> {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Retrieving training events for user '{}'", currentUser.username)

        val specification = TrainingEventSpecification.withFilters(
            createdBy = currentUser,
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = titleSearch,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = calendarId
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

        val cal = resolveCalendar(request.calendarId)
        event.calendar = cal
        val googleEventId = calendarClient.createEvent(cal.googleCalendarId, event)
        event.googleEventId = googleEventId

        return try {
            trainingEventPersistence.insert(event, currentUser)
        } catch (e: Exception) {
            // The calendar event exists but the local row does not. Roll the calendar back
            // so we don't leave an orphan the app can never see or manage again.
            log.error("Local write failed after creating calendar event {} — compensating", googleEventId, e)
            runCatching { calendarClient.deleteEvent(cal.googleCalendarId, googleEventId) }
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

        val cal = calendarOf(event)
        val googleEventId = event.googleEventId
        if (googleEventId != null) {
            calendarClient.updateEvent(cal.googleCalendarId, googleEventId, event)
        } else {
            // Only reachable if a previous create was interrupted between the two writes.
            log.warn("Training event {} has no google event id; creating one now", id)
            event.googleEventId = calendarClient.createEvent(cal.googleCalendarId, event)
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

    override fun bulkUpdateAttendance(sessionIds: List<UUID>, status: AttendanceStatus): BulkAttendanceResult {
        if (sessionIds.isEmpty()) {
            return BulkAttendanceResult(updatedCount = 0, futureSkippedCount = 0, status = status)
        }

        val currentUser = appUserService.getCurrentUser()
        val events = trainingEventRepository.findAllByIdIn(sessionIds)

        // Only modify sessions belonging to the current user (or if admin)
        val ownedEvents = events.filter { it.createdBy?.id == currentUser.id || currentUser.role == Role.ADMIN }

        val now = LocalDateTime.now()
        // Only past sessions can have attendance set. A session has happened if its end time is not after now.
        val (pastEvents, futureEvents) = ownedEvents.partition { !it.endTime.isAfter(now) }

        if (pastEvents.isNotEmpty()) {
            log.debug("User '{}' marking {} sessions as {}", currentUser.username, pastEvents.size, status)
            trainingEventPersistence.bulkUpdateAttendance(pastEvents, status, currentUser)
        }

        return BulkAttendanceResult(
            updatedCount = pastEvents.size,
            futureSkippedCount = futureEvents.size,
            status = status
        )
    }

    override fun bulkDelete(sessionIds: List<UUID>): BulkDeleteResult {
        if (sessionIds.isEmpty()) {
            return BulkDeleteResult(deletedCount = 0, failedCount = 0)
        }

        if (sessionIds.size > TrainingEventService.MAX_BULK_ACTION) {
            return BulkDeleteResult(
                deletedCount = 0,
                failedCount = 0,
                errorMessage = TrainingEventService.bulkCapRefusal(sessionIds.size, "delete")
            )
        }

        val currentUser = appUserService.getCurrentUser()
        val events = trainingEventRepository.findAllByIdIn(sessionIds)

        // Only delete sessions belonging to the current user (or if admin)
        val ownedEvents = events.filter { it.createdBy?.id == currentUser.id || currentUser.role == Role.ADMIN }

        val defaultGoogleCalId = trainingCalendarService.findDefault()?.googleCalendarId
        val succeededEvents = mutableListOf<TrainingEvent>()
        var failedCount = 0

        for (event in ownedEvents) {
            try {
                val googleCalId = event.calendar?.googleCalendarId ?: defaultGoogleCalId
                if (event.googleEventId != null) {
                    if (googleCalId != null) {
                        calendarClient.deleteEvent(googleCalId, event.googleEventId!!)
                    } else {
                        log.error("Cannot delete calendar event {}: no calendar resolved; skipping Google delete and removing local row", event.googleEventId)
                    }
                }
                succeededEvents.add(event)
            } catch (e: Exception) {
                log.error("Failed to delete training event '{}' ({}) from Google Calendar: {}", event.title, event.id, e.message)
                failedCount++
            }
        }

        if (succeededEvents.isNotEmpty()) {
            log.debug("User '{}' deleting {} training events", currentUser.username, succeededEvents.size)
            trainingEventPersistence.bulkRemove(succeededEvents, currentUser)
        }

        return BulkDeleteResult(
            deletedCount = succeededEvents.size,
            failedCount = failedCount
        )
    }

    override fun bulkUpdateEventType(sessionIds: List<UUID>, eventType: TrainingEventType): BulkEditResult {
        if (sessionIds.isEmpty()) {
            return BulkEditResult(updatedCount = 0, failedCount = 0, actionDescription = "Updated event type for")
        }

        if (sessionIds.size > TrainingEventService.MAX_BULK_ACTION) {
            return BulkEditResult(
                updatedCount = 0,
                failedCount = 0,
                actionDescription = "Updated event type for",
                errorMessage = TrainingEventService.bulkCapRefusal(sessionIds.size, "update")
            )
        }

        val currentUser = appUserService.getCurrentUser()
        val events = trainingEventRepository.findAllByIdIn(sessionIds)
        val ownedEvents = events.filter { it.createdBy?.id == currentUser.id || currentUser.role == Role.ADMIN }

        val defaultGoogleCalId = trainingCalendarService.findDefault()?.googleCalendarId
        val succeededEvents = mutableListOf<TrainingEvent>()
        var failedCount = 0

        for (event in ownedEvents) {
            try {
                event.eventType = eventType
                event.updatedAt = LocalDateTime.now()
                val googleCalId = event.calendar?.googleCalendarId ?: defaultGoogleCalId
                if (event.googleEventId != null) {
                    if (googleCalId != null) {
                        calendarClient.updateEvent(googleCalId, event.googleEventId!!, event)
                    } else {
                        log.error("Cannot update calendar event {}: no calendar resolved; skipping Google update", event.googleEventId)
                    }
                }
                succeededEvents.add(event)
            } catch (e: Exception) {
                log.error("Failed to update training event '{}' ({}) in Google Calendar: {}", event.title, event.id, e.message)
                // The session was mutated before the calendar call, and open-in-view leaves it
                // managed, so the transaction that saves the sessions that *did* succeed would
                // flush this one too - changing the app while Google still shows the old value.
                // Evicting it is what keeps a reported failure from half-applying.
                entityManager.detach(event)
                failedCount++
            }
        }

        if (succeededEvents.isNotEmpty()) {
            log.debug("User '{}' updating event type for {} training events", currentUser.username, succeededEvents.size)
            trainingEventPersistence.bulkUpdate(succeededEvents, "event type", currentUser)
        }

        return BulkEditResult(
            updatedCount = succeededEvents.size,
            failedCount = failedCount,
            actionDescription = "Updated event type for"
        )
    }

    override fun bulkUpdateSegments(sessionIds: List<UUID>, segments: List<TrainingEventSegmentRequest>): BulkEditResult {
        if (sessionIds.isEmpty()) {
            return BulkEditResult(updatedCount = 0, failedCount = 0, actionDescription = "Updated style breakdown for")
        }

        if (sessionIds.size > TrainingEventService.MAX_BULK_ACTION) {
            return BulkEditResult(
                updatedCount = 0,
                failedCount = 0,
                actionDescription = "Updated style breakdown for",
                errorMessage = TrainingEventService.bulkCapRefusal(sessionIds.size, "update")
            )
        }

        val currentUser = appUserService.getCurrentUser()
        val events = trainingEventRepository.findAllByIdIn(sessionIds)
        val ownedEvents = events.filter { it.createdBy?.id == currentUser.id || currentUser.role == Role.ADMIN }

        val requested = segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        val totalSegmentMinutes = requested.sumOf { it.durationMinutes ?: 0 }

        val (validEvents, skippedEvents) = ownedEvents.partition {
            val slotMinutes = java.time.Duration.between(it.startTime, it.endTime).toMinutes()
            totalSegmentMinutes <= slotMinutes
        }

        val defaultGoogleCalId = trainingCalendarService.findDefault()?.googleCalendarId
        val succeededEvents = mutableListOf<TrainingEvent>()
        var failedCount = 0

        for (event in validEvents) {
            try {
                applySegmentsList(event, requested)
                event.updatedAt = LocalDateTime.now()
                val googleCalId = event.calendar?.googleCalendarId ?: defaultGoogleCalId
                if (event.googleEventId != null) {
                    if (googleCalId != null) {
                        calendarClient.updateEvent(googleCalId, event.googleEventId!!, event)
                    } else {
                        log.error("Cannot update calendar event {}: no calendar resolved; skipping Google update", event.googleEventId)
                    }
                }
                succeededEvents.add(event)
            } catch (e: Exception) {
                log.error("Failed to update training event '{}' ({}) in Google Calendar: {}", event.title, event.id, e.message)
                // The session was mutated before the calendar call, and open-in-view leaves it
                // managed, so the transaction that saves the sessions that *did* succeed would
                // flush this one too - changing the app while Google still shows the old value.
                // Evicting it is what keeps a reported failure from half-applying.
                entityManager.detach(event)
                failedCount++
            }
        }

        if (succeededEvents.isNotEmpty()) {
            log.debug("User '{}' updating style breakdown for {} training events", currentUser.username, succeededEvents.size)
            trainingEventPersistence.bulkUpdate(succeededEvents, "style breakdown", currentUser)
        }

        return BulkEditResult(
            updatedCount = succeededEvents.size,
            failedCount = failedCount,
            skippedCount = skippedEvents.size,
            actionDescription = "Updated style breakdown for"
        )
    }

    override fun bulkUpdateMaterial(
        sessionIds: List<UUID>,
        materialId: UUID?,
        materialsUrl: String?,
        clearMaterial: Boolean
    ): BulkEditResult {
        if (sessionIds.isEmpty()) {
            return BulkEditResult(updatedCount = 0, failedCount = 0, actionDescription = "Updated material for")
        }

        if (sessionIds.size > TrainingEventService.MAX_BULK_ACTION) {
            return BulkEditResult(
                updatedCount = 0,
                failedCount = 0,
                actionDescription = "Updated material for",
                errorMessage = TrainingEventService.bulkCapRefusal(sessionIds.size, "update")
            )
        }

        val currentUser = appUserService.getCurrentUser()
        val events = trainingEventRepository.findAllByIdIn(sessionIds)
        val ownedEvents = events.filter { it.createdBy?.id == currentUser.id || currentUser.role == Role.ADMIN }

        val resolvedMaterial = if (!clearMaterial && materialId != null) materialService.findById(materialId) else null
        val resolvedUrl = if (!clearMaterial) materialsUrl?.takeIf { it.isNotBlank() } else null

        val defaultGoogleCalId = trainingCalendarService.findDefault()?.googleCalendarId
        val succeededEvents = mutableListOf<TrainingEvent>()
        var failedCount = 0

        for (event in ownedEvents) {
            try {
                event.material = resolvedMaterial
                event.materialsUrl = resolvedUrl
                event.updatedAt = LocalDateTime.now()
                val googleCalId = event.calendar?.googleCalendarId ?: defaultGoogleCalId
                if (event.googleEventId != null) {
                    if (googleCalId != null) {
                        calendarClient.updateEvent(googleCalId, event.googleEventId!!, event)
                    } else {
                        log.error("Cannot update calendar event {}: no calendar resolved; skipping Google update", event.googleEventId)
                    }
                }
                succeededEvents.add(event)
            } catch (e: Exception) {
                log.error("Failed to update training event '{}' ({}) in Google Calendar: {}", event.title, event.id, e.message)
                // The session was mutated before the calendar call, and open-in-view leaves it
                // managed, so the transaction that saves the sessions that *did* succeed would
                // flush this one too - changing the app while Google still shows the old value.
                // Evicting it is what keeps a reported failure from half-applying.
                entityManager.detach(event)
                failedCount++
            }
        }

        if (succeededEvents.isNotEmpty()) {
            log.debug("User '{}' updating material for {} training events", currentUser.username, succeededEvents.size)
            trainingEventPersistence.bulkUpdate(succeededEvents, "material", currentUser)
        }

        return BulkEditResult(
            updatedCount = succeededEvents.size,
            failedCount = failedCount,
            actionDescription = "Updated material for"
        )
    }

    override fun reschedule(id: UUID, start: LocalDateTime, end: LocalDateTime): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val event = findById(id)
        checkOwnership(event, currentUser)
        require(end.isAfter(start)) { "End time must be after the start time" }

        // Segments keep their durations; only the slot moves. A drag that shortens the
        // session below its style total would leave the two inconsistent.
        val segmentMinutes = event.segments.sumOf { it.durationMinutes }
        val slotMinutes = java.time.Duration.between(start, end).toMinutes()
        require(segmentMinutes <= slotMinutes) {
            "The style breakdown adds up to $segmentMinutes minutes, which is longer " +
            "than the $slotMinutes-minute session"
        }

        log.debug("User '{}' rescheduling training event '{}' to {}", currentUser.username, event.title, start)
        event.startTime = start
        event.endTime = end
        event.updatedAt = LocalDateTime.now()

        val cal = calendarOf(event)
        event.googleEventId?.let { calendarClient.updateEvent(cal.googleCalendarId, it, event) }
        return trainingEventPersistence.applyUpdate(event, currentUser)
    }

    override fun findInRange(
        from: LocalDateTime,
        to: LocalDateTime,
        calendarId: UUID?
    ): List<TrainingEvent> {
        val currentUser = appUserService.getCurrentUser()
        // Null means "All calendars", which keeps the original unscoped query.
        return if (calendarId == null) {
            trainingEventRepository.findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(
                currentUser, to, from
            )
        } else {
            trainingEventRepository
                .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                    currentUser, calendarId, to, from
                )
        }
    }

    override fun delete(id: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val event = findById(id)
        checkOwnership(event, currentUser)

        log.debug("User '{}' deleting training event '{}'", currentUser.username, event.title)
        val googleCalId = event.calendar?.googleCalendarId ?: trainingCalendarService.findDefault()?.googleCalendarId
        if (event.googleEventId != null) {
            if (googleCalId != null) {
                calendarClient.deleteEvent(googleCalId, event.googleEventId!!)
            } else {
                log.error("Cannot delete calendar event {}: no calendar resolved; skipping Google delete and removing local row", event.googleEventId)
            }
        }
        trainingEventPersistence.remove(event, currentUser)
    }

    /**
     * An existing session is written to its own calendar; a row that predates the backfill
     * adopts the default and records it, so the next write is unambiguous.
     */
    private fun calendarOf(event: TrainingEvent): TrainingCalendar =
        (event.calendar ?: trainingCalendarService.requireDefault()).also { event.calendar = it }

    private fun resolveCalendar(calendarId: UUID?): TrainingCalendar {
        val cal = if (calendarId != null) {
            trainingCalendarService.findById(calendarId)
                ?: throw IllegalArgumentException("Training calendar with id $calendarId not found")
        } else {
            trainingCalendarService.requireDefault()
        }
        require(cal.enabled) { "Training calendar '${cal.displayName}' is disabled" }
        return cal
    }

    private fun applyRequest(event: TrainingEvent, request: TrainingEventRequest) {
        val date = requireNotNull(request.date) { "Date is required" }
        val startTime = requireNotNull(request.startTime) { "Start time is required" }
        val endTime = requireNotNull(request.endTime) { "End time is required" }

        val start = LocalDateTime.of(date, startTime)
        val end = LocalDateTime.of(request.effectiveEndDate() ?: date, endTime)
        require(end.isAfter(start)) { "End time must be after the start time" }

        event.title = request.title
        event.startTime = start
        event.endTime = end
        event.eventType = TrainingEventType.valueOf(request.eventType)
        event.description = richTextService.clean(request.description)
        event.material = request.materialId?.let { materialService.findById(it) }
        event.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        event.attendanceStatus = AttendanceStatus.valueOf(request.attendanceStatus)
        event.updatedAt = LocalDateTime.now()

        applySegments(event, request)
    }

    /**
     * Rebuilds the style breakdown in place. orphanRemoval on the collection means clearing
     * and refilling the existing list deletes the rows that went away — replacing the list
     * instance would detach them instead and trip Hibernate.
     */
    private fun applySegments(event: TrainingEvent, request: TrainingEventRequest) {
        val requested = request.segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        applySegmentsList(event, requested)
    }

    /**
     * Rebuilds the style breakdown in place. orphanRemoval on the collection means clearing
     * and refilling the existing list deletes the rows that went away — replacing the list
     * instance would detach them instead and trip Hibernate.
     */
    private fun applySegmentsList(event: TrainingEvent, requested: List<TrainingEventSegmentRequest>) {
        val totalSegmentMinutes = requested.sumOf { it.durationMinutes ?: 0 }
        val slotMinutes = java.time.Duration.between(event.startTime, event.endTime).toMinutes()
        require(totalSegmentMinutes <= slotMinutes) {
            "The style breakdown adds up to $totalSegmentMinutes minutes, which is longer " +
            "than the $slotMinutes-minute session"
        }

        // Rewrite the surviving rows in place rather than clearing and refilling. Hibernate
        // flushes the child INSERTs before the orphan DELETEs, so a refill that hands a new
        // row a sortOrder the outgoing row still holds trips
        // unique_training_event_segment_sort_order mid-flush.
        requested.forEachIndexed { index, segmentRequest ->
            val requestedCategory = danceCategoryService.findById(segmentRequest.categoryId!!)
            val requestedMinutes = segmentRequest.durationMinutes!!
            if (index < event.segments.size) {
                event.segments[index].apply {
                    danceCategory = requestedCategory
                    durationMinutes = requestedMinutes
                    sortOrder = index
                }
            } else {
                event.segments.add(TrainingEventSegment().apply {
                    trainingEvent = event
                    danceCategory = requestedCategory
                    durationMinutes = requestedMinutes
                    sortOrder = index
                })
            }
        }

        // Whatever is left over is genuinely gone. These hold the highest sortOrders, so
        // removing them can never collide with a row added above.
        while (event.segments.size > requested.size) {
            event.segments.removeAt(event.segments.size - 1)
        }
    }

    private fun checkOwnership(event: TrainingEvent, currentUser: AppUser) {
        if (event.createdBy?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to modify this training event")
        }
    }
}
