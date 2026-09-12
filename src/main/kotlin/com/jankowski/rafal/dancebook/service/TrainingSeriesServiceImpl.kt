package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
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

        val occurrences = createOccurrences(series, dates, currentUser)
        return trainingSeriesPersistence.insertSeries(series, occurrences, currentUser).first()
    }

    override fun updateThisAndFollowing(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        // The cut-off is the occurrence being edited: everything before it is history.
        val cutOff = occurrence.startTime.toLocalDate()
        val future = trainingEventRepository
            .findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(series, cutOff.atStartOfDay())

        buildSeries(series, request, currentUser)
        series.startsOn = cutOff
        val dates = occurrenceDates(cutOff, series.endsOn, series)

        val replacements = createOccurrences(series, dates, currentUser)

        // Only remove the old calendar events once the new ones exist, so a failure
        // mid-way leaves the original series intact rather than a gap.
        future.forEach { old ->
            old.googleEventId?.let { id ->
                runCatching { calendarClient.deleteEvent(id) }
                    .onFailure { log.error("Could not remove superseded calendar event {}", id, it) }
            }
        }

        val saved = trainingSeriesPersistence.replaceOccurrences(series, future, replacements)
        return saved.firstOrNull() ?: occurrence
    }

    override fun deleteThisAndFollowing(occurrenceId: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val cutOff = occurrence.startTime.toLocalDate().atStartOfDay()
        val future = trainingEventRepository
            .findAllBySeriesAndStartTimeGreaterThanEqualOrderByStartTime(series, cutOff)

        log.debug("Deleting {} occurrences of series '{}' from {}", future.size, series.title, cutOff)

        future.forEach { event ->
            event.googleEventId?.let { calendarClient.deleteEvent(it) }
        }
        trainingSeriesPersistence.removeOccurrences(future)
    }

    /**
     * Creates a calendar event for every date, rolling back the ones already created if any
     * call fails. Returns the unsaved occurrences, each carrying its Google event id.
     */
    private fun createOccurrences(
        series: TrainingSeries,
        dates: List<LocalDate>,
        actor: AppUser
    ): List<TrainingEvent> {
        val created = mutableListOf<TrainingEvent>()
        try {
            dates.forEach { date ->
                val event = occurrenceFor(series, date, actor)
                event.googleEventId = calendarClient.createEvent(event)
                created.add(event)
            }
        } catch (e: Exception) {
            log.error(
                "Series generation failed after {} of {} occurrences — removing them again",
                created.size, dates.size, e
            )
            created.forEach { event ->
                event.googleEventId?.let { id ->
                    runCatching { calendarClient.deleteEvent(id) }
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

        // Rewritten in place rather than cleared and refilled, for the same reason as
        // TrainingEventServiceImpl.applySegments: Hibernate flushes the child INSERTs before
        // the orphan DELETEs, so reusing a sortOrder the outgoing row still holds trips
        // unique_training_series_segment_sort_order mid-flush.
        requestedSegments.forEachIndexed { index, segmentRequest ->
            val requestedCategory = danceCategoryService.findById(segmentRequest.categoryId!!)
            val requestedMinutes = segmentRequest.durationMinutes!!
            if (index < series.segments.size) {
                series.segments[index].apply {
                    danceCategory = requestedCategory
                    durationMinutes = requestedMinutes
                    sortOrder = index
                }
            } else {
                series.segments.add(TrainingSeriesSegment().apply {
                    trainingSeries = series
                    danceCategory = requestedCategory
                    durationMinutes = requestedMinutes
                    sortOrder = index
                })
            }
        }
        while (series.segments.size > requestedSegments.size) {
            series.segments.removeAt(series.segments.size - 1)
        }
        return series
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
}
