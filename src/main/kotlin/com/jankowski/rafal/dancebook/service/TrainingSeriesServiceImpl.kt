package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.ScopeOption
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.SeriesScope
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.model.TrainingSeriesSegment
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Turns a repeating definition into independent training events.
 *
 * Like [TrainingEventServiceImpl] this is deliberately not @Transactional: it makes one
 * Google Calendar call per occurrence and must not hold a database connection across them.
 * Generation is all-or-nothing — every calendar event is created first, and if any call
 * fails the ones already created are deleted again, so a half-built series never survives.
 */
@Service
class TrainingSeriesServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingSeriesPersistence: TrainingSeriesPersistence,
    private val calendarClient: GoogleCalendarClient,
    private val trainingCalendarService: TrainingCalendarService,
    private val appUserService: AppUserService,
    private val danceCategoryService: DanceCategoryService,
    private val materialService: MaterialService
) : TrainingSeriesService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingSeriesServiceImpl::class.java)

        /**
         * One form submit becomes one Google API call per occurrence, so the span is
         * capped rather than letting a stray end date fire hundreds of requests.
         */
        const val MAX_OCCURRENCES = 52
    }

    override fun create(request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val series = buildSeries(TrainingSeries(), request, currentUser)

        val dates = occurrenceDates(series.startsOn, series.endsOn, series)
        log.debug(
            "User '{}' creating series '{}' with {} occurrences",
            currentUser.username, series.title, dates.size
        )

        val calendar = resolveCalendar(request.calendarId)
        val occurrences = createOccurrences(series, dates, currentUser, calendar)
        return trainingSeriesPersistence.insertSeries(series, occurrences, currentUser).first()
    }

    override fun updateThisEvent(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val date = requireNotNull(request.date) { "Date is required" }
        val startTime = requireNotNull(request.startTime) { "Start time is required" }
        val endTime = requireNotNull(request.endTime) { "End time is required" }
        val start = LocalDateTime.of(date, startTime)
        val end = LocalDateTime.of(request.effectiveEndDate() ?: date, endTime)
        require(end.isAfter(start)) { "End time must be after the start time" }

        occurrence.title = request.title
        occurrence.startTime = start
        occurrence.endTime = end
        occurrence.eventType = TrainingEventType.valueOf(request.eventType)
        occurrence.description = request.description?.takeIf { it.isNotBlank() }
        occurrence.material = request.materialId?.let { materialService.findById(it) }
        occurrence.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        occurrence.attendanceStatus = AttendanceStatus.valueOf(request.attendanceStatus)
        occurrence.updatedAt = LocalDateTime.now()

        val validSegments = request.segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        val resolvedSegments = validSegments.map {
            danceCategoryService.findById(it.categoryId!!) to it.durationMinutes!!
        }
        val slotMinutes = java.time.Duration.between(occurrence.startTime, occurrence.endTime).toMinutes()
        val totalSegmentMinutes = resolvedSegments.sumOf { it.second }
        require(totalSegmentMinutes <= slotMinutes) {
            "The style breakdown adds up to $totalSegmentMinutes minutes, which is longer " +
            "than the $slotMinutes-minute session"
        }
        applyResolvedSegmentsToEvent(occurrence, resolvedSegments)

        occurrence.series = null

        val cal = occurrence.calendar ?: trainingCalendarService.findDefault()
        val googleCalId = cal?.googleCalendarId
        val googleEventId = occurrence.googleEventId
        if (googleCalId != null) {
            if (googleEventId != null) {
                calendarClient.updateEvent(googleCalId, googleEventId, occurrence)
            } else {
                occurrence.googleEventId = calendarClient.createEvent(googleCalId, occurrence)
            }
        } else {
            log.error("Cannot update calendar event {}: no calendar resolved", googleEventId)
        }

        return try {
            trainingSeriesPersistence.detachAndSave(occurrence, series, currentUser)
        } catch (e: Exception) {
            log.error(
                "Local write failed after updating calendar event {}; calendar is ahead of the database",
                occurrence.googleEventId, e
            )
            throw e
        }
    }

    override fun updateThisAndFollowing(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val cutOff = occurrence.startTime.toLocalDate().atStartOfDay()
        val occurrences = trainingEventRepository
            .findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(series, cutOff)

        return updateOccurrencesInPlace(series, occurrences, request, currentUser, occurrenceId)
    }

    override fun updateAll(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val occurrences = trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)

        return updateOccurrencesInPlace(series, occurrences, request, currentUser, occurrenceId)
    }

    override fun deleteThisAndFollowing(occurrenceId: UUID): BulkDeleteResult {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val cutOff = occurrence.startTime.toLocalDate().atStartOfDay()
        val future = trainingEventRepository
            .findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(series, cutOff)

        log.debug("Deleting {} occurrences of series '{}' from {}", future.size, series.title, cutOff)

        var failedCount = 0
        future.forEach { event ->
            event.googleEventId?.let { id ->
                val googleCalId = event.calendar?.googleCalendarId ?: trainingCalendarService.findDefault()?.googleCalendarId
                if (googleCalId != null) {
                    try {
                        calendarClient.deleteEvent(googleCalId, id)
                    } catch (e: Exception) {
                        log.error("Could not delete Google Calendar event {}: {}", id, e.message)
                        failedCount++
                    }
                } else {
                    log.error("Cannot delete calendar event {}: no calendar resolved; skipping Google delete", id)
                }
            }
        }
        trainingSeriesPersistence.removeOccurrences(future, series, currentUser)
        return BulkDeleteResult(
            deletedCount = future.size,
            failedCount = failedCount
        )
    }

    override fun deleteAll(occurrenceId: UUID): BulkDeleteResult {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val allOccurrences = trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)
        val (surviving, toDelete) = allOccurrences.partition { hasRecordedOutcome(it) }

        log.debug("Deleting series '{}' ({} to delete, {} surviving standalone)", series.title, toDelete.size, surviving.size)

        var failedCount = 0
        toDelete.forEach { event ->
            event.googleEventId?.let { id ->
                val googleCalId = event.calendar?.googleCalendarId ?: trainingCalendarService.findDefault()?.googleCalendarId
                if (googleCalId != null) {
                    try {
                        calendarClient.deleteEvent(googleCalId, id)
                    } catch (e: Exception) {
                        log.error("Could not delete Google Calendar event {}: {}", id, e.message)
                        failedCount++
                    }
                } else {
                    log.error("Cannot delete calendar event {}: no calendar resolved; skipping Google delete", id)
                }
            }
        }

        trainingSeriesPersistence.deleteAll(series, surviving, toDelete, currentUser)
        return BulkDeleteResult(
            deletedCount = toDelete.size,
            failedCount = failedCount
        )
    }

    override fun calculateDeleteScopeOptions(occurrenceId: UUID): List<ScopeOption> {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val allOccurrences = trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)
        val cutOff = occurrence.startTime.toLocalDate().atStartOfDay()
        val followingOccurrences = allOccurrences.filter { !it.startTime.isBefore(cutOff) }

        val thisEventOutcomeCount = if (hasRecordedOutcome(occurrence)) 1 else 0
        val followingOutcomeCount = followingOccurrences.count { hasRecordedOutcome(it) }
        val allOutcomeCount = allOccurrences.count { hasRecordedOutcome(it) }

        return listOf(
            ScopeOption(
                scope = SeriesScope.THIS_EVENT,
                count = 1,
                outcomeCount = thisEventOutcomeCount
            ),
            ScopeOption(
                scope = SeriesScope.THIS_AND_FOLLOWING,
                count = followingOccurrences.size,
                outcomeCount = followingOutcomeCount
            ),
            ScopeOption(
                scope = SeriesScope.ALL_EVENTS,
                count = allOccurrences.size,
                outcomeCount = allOutcomeCount
            )
        )
    }

    private fun hasRecordedOutcome(event: TrainingEvent): Boolean =
        event.attendanceStatus == AttendanceStatus.ATTENDED || event.attendanceStatus == AttendanceStatus.SKIPPED

    /**
     * Creates a calendar event for every date, rolling back the ones already created if any
     * call fails. Returns the unsaved occurrences, each carrying its Google event id.
     */
    private fun createOccurrences(
        series: TrainingSeries,
        dates: List<LocalDate>,
        actor: AppUser,
        calendar: TrainingCalendar
    ): List<TrainingEvent> {
        val created = mutableListOf<TrainingEvent>()
        try {
            dates.forEach { date ->
                val event = occurrenceFor(series, date, actor)
                event.calendar = calendar
                event.googleEventId = calendarClient.createEvent(calendar.googleCalendarId, event)
                created.add(event)
            }
        } catch (e: Exception) {
            log.error(
                "Series generation failed after {} of {} occurrences — removing them again",
                created.size, dates.size, e
            )
            created.forEach { event ->
                event.googleEventId?.let { id ->
                    runCatching { calendarClient.deleteEvent(calendar.googleCalendarId, id) }
                        .onFailure { log.error("Compensating delete failed for {}; orphan calendar event", id, it) }
                }
            }
            throw e
        }
        return created
    }

    private fun occurrenceFor(series: TrainingSeries, date: LocalDate, actor: AppUser): TrainingEvent {
        val start = LocalDateTime.of(date, series.startTime)
        // An end time at or before the start means the session runs past midnight.
        val endDate = if (series.endTime > series.startTime) date else date.plusDays(1)

        val event = TrainingEvent().apply {
            title = series.title
            startTime = start
            endTime = LocalDateTime.of(endDate, series.endTime)
            eventType = series.eventType
            description = series.description
            material = series.material
            materialsUrl = series.materialsUrl
            attendanceStatus = AttendanceStatus.PLANNED
            createdBy = actor
            this.series = series
            createdAt = LocalDateTime.now()
            updatedAt = LocalDateTime.now()
        }

        series.segments.forEach { template ->
            event.segments.add(TrainingEventSegment().apply {
                trainingEvent = event
                danceCategory = template.danceCategory
                durationMinutes = template.durationMinutes
                sortOrder = template.sortOrder
            })
        }
        return event
    }

    private fun occurrenceDates(from: LocalDate, until: LocalDate, series: TrainingSeries): List<LocalDate> {
        require(!until.isBefore(from)) { "The repeat end date must not be before the first session" }

        val dates = mutableListOf<LocalDate>()
        var cursor = from
        while (cursor.dayOfWeek != series.dayOfWeek) {
            cursor = cursor.plusDays(1)
            if (cursor.isAfter(until)) break
        }
        while (!cursor.isAfter(until)) {
            dates.add(cursor)
            cursor = cursor.plusWeeks(1)
        }

        require(dates.isNotEmpty()) {
            "No ${series.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}s fall between " +
            "$from and $until"
        }
        require(dates.size <= MAX_OCCURRENCES) {
            "That would create ${dates.size} sessions; the limit is $MAX_OCCURRENCES. " +
            "Choose an earlier end date."
        }
        return dates
    }

    private fun buildSeries(
        series: TrainingSeries,
        request: TrainingEventRequest,
        actor: AppUser
    ): TrainingSeries {
        val date = requireNotNull(request.date) { "Date is required" }
        val startTime = requireNotNull(request.startTime) { "Start time is required" }
        val endTime = requireNotNull(request.endTime) { "End time is required" }
        val until = requireNotNull(request.repeatUntil) { "A repeat end date is required" }

        val slotMinutes = java.time.Duration.between(
            LocalDateTime.of(date, startTime),
            LocalDateTime.of(request.effectiveEndDate() ?: date, endTime)
        ).toMinutes()
        require(slotMinutes > 0) { "End time must be after the start time" }

        val requestedSegments = request.segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        val totalSegmentMinutes = requestedSegments.sumOf { it.durationMinutes ?: 0 }
        require(totalSegmentMinutes <= slotMinutes) {
            "The style breakdown adds up to $totalSegmentMinutes minutes, which is longer " +
            "than the $slotMinutes-minute session"
        }

        series.title = request.title
        series.dayOfWeek = date.dayOfWeek
        series.startTime = startTime
        series.endTime = endTime
        series.startsOn = date
        series.endsOn = until
        series.eventType = TrainingEventType.valueOf(request.eventType)
        series.description = request.description?.takeIf { it.isNotBlank() }
        series.material = request.materialId?.let { materialService.findById(it) }
        series.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        series.createdBy = series.createdBy ?: actor
        series.updatedAt = LocalDateTime.now()

        applyResolvedSegmentsToSeries(series, requestedSegments.map {
            danceCategoryService.findById(it.categoryId!!) to it.durationMinutes!!
        })
        return series
    }

    private fun updateOccurrencesInPlace(
        series: TrainingSeries,
        occurrences: List<TrainingEvent>,
        request: TrainingEventRequest,
        currentUser: AppUser,
        targetOccurrenceId: UUID
    ): TrainingEvent {
        if (occurrences.isEmpty()) {
            return findOccurrence(targetOccurrenceId)
        }

        val validSegments = request.segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        val resolvedSegments = validSegments.map {
            danceCategoryService.findById(it.categoryId!!) to it.durationMinutes!!
        }

        val seriesSlotMinutes = java.time.Duration.between(series.startTime, series.endTime).toMinutes().let {
            if (it <= 0) it + 24 * 60 else it
        }
        val totalSegmentMinutes = resolvedSegments.sumOf { it.second }
        require(totalSegmentMinutes <= seriesSlotMinutes) {
            "The style breakdown adds up to $totalSegmentMinutes minutes, which is longer " +
            "than the $seriesSlotMinutes-minute session"
        }

        series.title = request.title
        series.eventType = TrainingEventType.valueOf(request.eventType)
        series.description = request.description?.takeIf { it.isNotBlank() }
        series.material = request.materialId?.let { materialService.findById(it) }
        series.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        series.updatedAt = LocalDateTime.now()
        applyResolvedSegmentsToSeries(series, resolvedSegments)

        val now = LocalDateTime.now()
        val defaultGoogleCalId = trainingCalendarService.findDefault()?.googleCalendarId

        // Every occurrence is pushed to Google before anything is written locally, so a failure
        // part-way leaves the calendar showing the edit for the occurrences already sent while
        // the database still shows none of it. Nothing can roll those back without a snapshot of
        // their previous content, so name them in the log — that list is the only record of where
        // the two sides diverged, and the only way to put them back by hand.
        val pushedToGoogle = mutableListOf<String>()
        try {
            for (event in occurrences) {
                event.title = request.title
                event.eventType = series.eventType
                event.description = series.description
                event.material = series.material
                event.materialsUrl = series.materialsUrl
                event.updatedAt = now
                applyResolvedSegmentsToEvent(event, resolvedSegments)

                val googleCalId = event.calendar?.googleCalendarId ?: defaultGoogleCalId
                val googleEventId = event.googleEventId
                if (googleCalId != null) {
                    if (googleEventId != null) {
                        calendarClient.updateEvent(googleCalId, googleEventId, event)
                        pushedToGoogle.add(googleEventId)
                    } else {
                        event.googleEventId = calendarClient.createEvent(googleCalId, event)
                        event.googleEventId?.let { pushedToGoogle.add(it) }
                    }
                } else {
                    log.error("Cannot update calendar event {}: no calendar resolved", googleEventId)
                }
            }
        } catch (e: Exception) {
            log.error(
                "Series edit failed after updating {} of {} calendar events for series '{}'; " +
                "the calendar is ahead of the database for these events: {}",
                pushedToGoogle.size, occurrences.size, series.title, pushedToGoogle, e
            )
            throw e
        }

        val saved = try {
            trainingSeriesPersistence.updateOccurrencesInPlace(series, occurrences, currentUser)
        } catch (e: Exception) {
            log.error(
                "Local write failed after updating calendar events for series '{}'; calendar is ahead of the database",
                series.title, e
            )
            throw e
        }

        return saved.firstOrNull { it.id == targetOccurrenceId }
            ?: occurrences.firstOrNull { it.id == targetOccurrenceId }
            ?: occurrences.first()
    }

    private fun applyResolvedSegmentsToEvent(
        event: TrainingEvent,
        resolvedSegments: List<Pair<DanceCategory, Int>>
    ) {
        resolvedSegments.forEachIndexed { index, (category, minutes) ->
            if (index < event.segments.size) {
                event.segments[index].apply {
                    danceCategory = category
                    durationMinutes = minutes
                    sortOrder = index
                }
            } else {
                event.segments.add(TrainingEventSegment().apply {
                    trainingEvent = event
                    danceCategory = category
                    durationMinutes = minutes
                    sortOrder = index
                })
            }
        }
        while (event.segments.size > resolvedSegments.size) {
            event.segments.removeAt(event.segments.size - 1)
        }
    }

    private fun applyResolvedSegmentsToSeries(
        series: TrainingSeries,
        resolvedSegments: List<Pair<DanceCategory, Int>>
    ) {
        resolvedSegments.forEachIndexed { index, (category, minutes) ->
            if (index < series.segments.size) {
                series.segments[index].apply {
                    danceCategory = category
                    durationMinutes = minutes
                    sortOrder = index
                }
            } else {
                series.segments.add(TrainingSeriesSegment().apply {
                    trainingSeries = series
                    danceCategory = category
                    durationMinutes = minutes
                    sortOrder = index
                })
            }
        }
        while (series.segments.size > resolvedSegments.size) {
            series.segments.removeAt(series.segments.size - 1)
        }
    }

    private fun findOccurrence(id: UUID): TrainingEvent =
        trainingEventRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find training event with id $id")
        }

    private fun checkOwnership(event: TrainingEvent, currentUser: AppUser) {
        if (event.createdBy?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to modify this training event")
        }
    }

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
}
