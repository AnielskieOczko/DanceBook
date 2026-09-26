package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.PatternReconcilePlan
import com.jankowski.rafal.dancebook.dto.ScopeOption
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Material
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
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
    private val materialService: MaterialService,
    private val richTextService: RichTextService
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
        occurrence.description = richTextService.clean(request.description)
        occurrence.material = request.materialId?.let { materialService.findById(it) }
        occurrence.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        occurrence.setAttendance(currentUser, AttendanceStatus.valueOf(request.attendanceStatus))
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

        val cal = occurrence.calendar ?: trainingCalendarService.findDefault(currentUser)
        val googleCalId = cal?.writeTarget?.googleCalendarId
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

    private data class TargetSeriesPattern(
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime,
        val startsOn: LocalDate,
        val endsOn: LocalDate,
        val targetDates: List<LocalDate>,
        val isPatternChanged: Boolean
    )

    private fun deriveTargetPattern(series: TrainingSeries, request: TrainingEventRequest): TargetSeriesPattern {
        val newDayOfWeek = request.dayOfWeek ?: request.date?.dayOfWeek ?: series.dayOfWeek
        val newStartTime = request.startTime ?: series.startTime
        val newEndTime = request.endTime ?: series.endTime
        val newEndsOn = request.repeatUntil ?: series.endsOn

        val newStartsOn = if (newDayOfWeek == series.dayOfWeek) {
            series.startsOn
        } else {
            series.startsOn.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusDays((newDayOfWeek.value - 1).toLong())
        }

        val targetDates = occurrenceDates(newStartsOn, newEndsOn, newDayOfWeek)

        val isPatternChanged = newDayOfWeek != series.dayOfWeek ||
            newStartTime != series.startTime ||
            newEndTime != series.endTime ||
            newEndsOn != series.endsOn

        return TargetSeriesPattern(
            dayOfWeek = newDayOfWeek,
            startTime = newStartTime,
            endTime = newEndTime,
            startsOn = newStartsOn,
            endsOn = newEndsOn,
            targetDates = targetDates,
            isPatternChanged = isPatternChanged
        )
    }

    override fun updateAll(occurrenceId: UUID, request: TrainingEventRequest): TrainingEvent {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val pattern = deriveTargetPattern(series, request)
        val occurrences = trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)

        return if (pattern.isPatternChanged) {
            reconcileSeriesPattern(
                series = series,
                occurrences = occurrences,
                request = request,
                pattern = pattern,
                currentUser = currentUser,
                targetOccurrenceId = occurrenceId
            )
        } else {
            updateOccurrencesInPlace(series, occurrences, request, currentUser, occurrenceId)
        }
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
                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: trainingCalendarService.findDefault(currentUser)?.writeTarget?.googleCalendarId
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
                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: trainingCalendarService.findDefault(currentUser)?.writeTarget?.googleCalendarId
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

    override fun calculatePatternReconcile(occurrenceId: UUID, request: TrainingEventRequest): PatternReconcilePlan {
        val currentUser = appUserService.getCurrentUser()
        val occurrence = findOccurrence(occurrenceId)
        checkOwnership(occurrence, currentUser)

        val series = occurrence.series
            ?: throw IllegalStateException("This session is not part of a repeating series")

        val pattern = deriveTargetPattern(series, request)
        val existingOccurrences = trainingEventRepository.findAllBySeriesOrderByStartTimeAsc(series)

        val m = existingOccurrences.size
        val n = pattern.targetDates.size

        val createdCount = maxOf(0, n - m)
        val removedCount = maxOf(0, m - n)
        val droppedRecordedCount = if (m > n) {
            existingOccurrences.subList(n, m).count { hasRecordedOutcome(it) }
        } else 0

        val movedCount = (0 until minOf(m, n)).count { i ->
            val targetDate = pattern.targetDates[i]
            val slotStart = LocalDateTime.of(targetDate, pattern.startTime)
            val endDate = if (pattern.endTime > pattern.startTime) targetDate else targetDate.plusDays(1)
            val slotEnd = LocalDateTime.of(endDate, pattern.endTime)
            existingOccurrences[i].startTime != slotStart || existingOccurrences[i].endTime != slotEnd
        }

        return PatternReconcilePlan(
            createdCount = createdCount,
            movedCount = movedCount,
            removedCount = removedCount,
            droppedRecordedCount = droppedRecordedCount,
            targetTotalCount = n
        )
    }

    private fun hasRecordedOutcome(event: TrainingEvent): Boolean =
        event.attendances.any { it.status == AttendanceStatus.ATTENDED || it.status == AttendanceStatus.SKIPPED }

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
        val targetGoogleCalId = calendar.requireWriteTarget().googleCalendarId
        val created = mutableListOf<TrainingEvent>()
        try {
            dates.forEach { date ->
                val event = occurrenceFor(series, date, actor)
                event.calendar = calendar
                event.googleEventId = calendarClient.createEvent(targetGoogleCalId, event)
                created.add(event)
            }
        } catch (e: Exception) {
            log.error(
                "Series generation failed after {} of {} occurrences — removing them again",
                created.size, dates.size, e
            )
            created.forEach { event ->
                event.googleEventId?.let { id ->
                    runCatching { calendarClient.deleteEvent(targetGoogleCalId, id) }
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

    private fun occurrenceDates(from: LocalDate, until: LocalDate, dayOfWeek: DayOfWeek): List<LocalDate> {
        require(!until.isBefore(from)) { "The repeat end date must not be before the first session" }

        val dates = mutableListOf<LocalDate>()
        var cursor = from
        while (cursor.dayOfWeek != dayOfWeek) {
            cursor = cursor.plusDays(1)
            if (cursor.isAfter(until)) break
        }
        while (!cursor.isAfter(until)) {
            dates.add(cursor)
            cursor = cursor.plusWeeks(1)
        }

        require(dates.isNotEmpty()) {
            "No ${dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}s fall between " +
            "$from and $until"
        }
        require(dates.size <= MAX_OCCURRENCES) {
            "That would create ${dates.size} sessions; the limit is $MAX_OCCURRENCES. " +
            "Choose an earlier end date."
        }
        return dates
    }

    private fun occurrenceDates(from: LocalDate, until: LocalDate, series: TrainingSeries): List<LocalDate> =
        occurrenceDates(from, until, series.dayOfWeek)

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
        series.description = richTextService.clean(request.description)
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
        series.description = richTextService.clean(request.description)
        series.material = request.materialId?.let { materialService.findById(it) }
        series.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        series.updatedAt = LocalDateTime.now()
        applyResolvedSegmentsToSeries(series, resolvedSegments)

        val now = LocalDateTime.now()
        val defaultGoogleCalId = trainingCalendarService.findDefault(currentUser)?.writeTarget?.googleCalendarId

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

                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: defaultGoogleCalId
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

    private fun reconcileSeriesPattern(
        series: TrainingSeries,
        occurrences: List<TrainingEvent>,
        request: TrainingEventRequest,
        pattern: TargetSeriesPattern,
        currentUser: AppUser,
        targetOccurrenceId: UUID
    ): TrainingEvent {
        val newStartsOn = pattern.startsOn
        val newEndsOn = pattern.endsOn
        val newDayOfWeek = pattern.dayOfWeek
        val newStartTime = pattern.startTime
        val newEndTime = pattern.endTime
        val targetDates = pattern.targetDates

        val seriesSlotMinutes = java.time.Duration.between(newStartTime, newEndTime).toMinutes().let {
            if (it <= 0) it + 24 * 60 else it
        }
        val validSegments = request.segments.filter { it.categoryId != null && (it.durationMinutes ?: 0) > 0 }
        val resolvedSegments = validSegments.map {
            danceCategoryService.findById(it.categoryId!!) to it.durationMinutes!!
        }
        val totalSegmentMinutes = resolvedSegments.sumOf { it.second }
        require(totalSegmentMinutes <= seriesSlotMinutes) {
            "The style breakdown adds up to $totalSegmentMinutes minutes, which is longer " +
            "than the $seriesSlotMinutes-minute session"
        }

        val m = occurrences.size
        val n = targetDates.size
        val survivingCount = minOf(m, n)
        val surviving = occurrences.subList(0, survivingCount)

        val defaultGoogleCalId = trainingCalendarService.findDefault(currentUser)?.writeTarget?.googleCalendarId
        val seriesCalendar = occurrences.firstOrNull()?.calendar ?: trainingCalendarService.findDefault(currentUser)

        // Snapshot surviving occurrences before pushing to Google Calendar so we can rollback on failure
        val snapshots = surviving.map { event ->
            EventGoogleSnapshot(
                title = event.title,
                startTime = event.startTime,
                endTime = event.endTime,
                eventType = event.eventType,
                description = event.description,
                material = event.material,
                materialsUrl = event.materialsUrl,
                segments = event.segments.map { SegmentSnapshot(it.danceCategory, it.durationMinutes, it.sortOrder) }
            )
        }

        val now = LocalDateTime.now()
        val updatedGoogleEvents = mutableListOf<Pair<TrainingEvent, EventGoogleSnapshot>>()
        val createdGoogleEvents = mutableListOf<TrainingEvent>()
        val newOccurrences = mutableListOf<TrainingEvent>()

        fun rollbackGoogleCalendar() {
            for (event in createdGoogleEvents) {
                event.googleEventId?.let { id ->
                    val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: seriesCalendar?.writeTarget?.googleCalendarId ?: defaultGoogleCalId
                    if (googleCalId != null) {
                        runCatching { calendarClient.deleteEvent(googleCalId, id) }
                            .onFailure { log.error("Failed to delete Google event {} during rollback", id, it) }
                    }
                }
                event.googleEventId = null
            }
            for ((event, snapshot) in updatedGoogleEvents) {
                restoreSnapshot(event, snapshot)
                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: defaultGoogleCalId
                val googleEventId = event.googleEventId
                if (googleCalId != null && googleEventId != null) {
                    runCatching { calendarClient.updateEvent(googleCalId, googleEventId, event) }
                        .onFailure { log.error("Failed to restore Google event {} during rollback", googleEventId, it) }
                }
            }
        }

        try {
            for (i in 0 until survivingCount) {
                val event = surviving[i]
                val snapshot = snapshots[i]
                val targetDate = targetDates[i]
                val endDate = if (newEndTime > newStartTime) targetDate else targetDate.plusDays(1)

                event.title = request.title
                event.startTime = LocalDateTime.of(targetDate, newStartTime)
                event.endTime = LocalDateTime.of(endDate, newEndTime)
                event.eventType = TrainingEventType.valueOf(request.eventType)
                event.description = richTextService.clean(request.description)
                event.material = request.materialId?.let { materialService.findById(it) }
                event.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
                event.updatedAt = now
                applyResolvedSegmentsToEvent(event, resolvedSegments)

                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: defaultGoogleCalId
                if (googleCalId != null) {
                    if (event.googleEventId != null) {
                        calendarClient.updateEvent(googleCalId, event.googleEventId!!, event)
                        updatedGoogleEvents.add(event to snapshot)
                    } else {
                        val newId = calendarClient.createEvent(googleCalId, event)
                        event.googleEventId = newId
                        createdGoogleEvents.add(event)
                    }
                }
            }

            if (n > m) {
                for (i in m until n) {
                    val date = targetDates[i]
                    val newEvent = occurrenceFor(series, date, currentUser).apply {
                        title = request.title
                        startTime = LocalDateTime.of(date, newStartTime)
                        endTime = LocalDateTime.of(if (newEndTime > newStartTime) date else date.plusDays(1), newEndTime)
                        eventType = TrainingEventType.valueOf(request.eventType)
                        description = richTextService.clean(request.description)
                        material = request.materialId?.let { materialService.findById(it) }
                        materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
                        calendar = seriesCalendar
                        applyResolvedSegmentsToEvent(this, resolvedSegments)
                    }
                    val targetGoogleCalId = seriesCalendar?.writeTarget?.googleCalendarId
                    if (targetGoogleCalId != null) {
                        newEvent.googleEventId = calendarClient.createEvent(targetGoogleCalId, newEvent)
                        if (newEvent.googleEventId != null) {
                            createdGoogleEvents.add(newEvent)
                        }
                    }
                    newOccurrences.add(newEvent)
                }
            }
        } catch (e: Exception) {
            log.error(
                "Google Calendar call failed during series reconcile for '{}'; rolling back",
                series.title, e
            )
            rollbackGoogleCalendar()
            throw e
        }

        val (detachedToKeep, toDelete) = if (m > n) {
            occurrences.subList(n, m).partition { hasRecordedOutcome(it) }
        } else {
            emptyList<TrainingEvent>() to emptyList<TrainingEvent>()
        }

        series.title = request.title
        series.dayOfWeek = newDayOfWeek
        series.startTime = newStartTime
        series.endTime = newEndTime
        series.startsOn = newStartsOn
        series.endsOn = newEndsOn
        series.eventType = TrainingEventType.valueOf(request.eventType)
        series.description = richTextService.clean(request.description)
        series.material = request.materialId?.let { materialService.findById(it) }
        series.materialsUrl = request.materialsUrl?.takeIf { it.isNotBlank() }
        series.updatedAt = now
        applyResolvedSegmentsToSeries(series, resolvedSegments)

        val saved = try {
            trainingSeriesPersistence.reconcileSeries(
                series = series,
                survivingOccurrences = surviving,
                newOccurrences = newOccurrences,
                detachedOccurrences = detachedToKeep,
                deletedOccurrences = toDelete,
                actor = currentUser
            )
        } catch (e: Exception) {
            log.error(
                "Local write failed after updating calendar events for series '{}'; calendar is ahead of the database",
                series.title, e
            )
            throw e
        }

        for (event in toDelete) {
            event.googleEventId?.let { id ->
                val googleCalId = event.calendar?.writeTarget?.googleCalendarId ?: defaultGoogleCalId
                if (googleCalId != null) {
                    try {
                        calendarClient.deleteEvent(googleCalId, id)
                    } catch (e: Exception) {
                        log.error("Could not delete Google Calendar event {}: {}", id, e.message)
                    }
                }
            }
        }

        return saved.firstOrNull { it.id == targetOccurrenceId }
            ?: surviving.firstOrNull { it.id == targetOccurrenceId }
            ?: saved.firstOrNull()
            ?: occurrences.first()
    }

    private data class SegmentSnapshot(
        val category: DanceCategory?,
        val durationMinutes: Int,
        val sortOrder: Int
    )

    private data class EventGoogleSnapshot(
        val title: String,
        val startTime: LocalDateTime,
        val endTime: LocalDateTime,
        val eventType: TrainingEventType,
        val description: String?,
        val material: Material?,
        val materialsUrl: String?,
        val segments: List<SegmentSnapshot>
    )

    private fun restoreSnapshot(event: TrainingEvent, snapshot: EventGoogleSnapshot) {
        event.title = snapshot.title
        event.startTime = snapshot.startTime
        event.endTime = snapshot.endTime
        event.eventType = snapshot.eventType
        event.description = snapshot.description
        event.material = snapshot.material
        event.materialsUrl = snapshot.materialsUrl
        event.segments.clear()
        snapshot.segments.forEach { seg ->
            event.segments.add(TrainingEventSegment().apply {
                trainingEvent = event
                danceCategory = seg.category
                durationMinutes = seg.durationMinutes
                sortOrder = seg.sortOrder
            })
        }
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
        val currentUser = appUserService.getCurrentUser()
        val cal = if (calendarId != null) {
            trainingCalendarService.findByIdVisibleTo(calendarId, currentUser)
                ?: trainingCalendarService.findById(calendarId)
                ?: throw IllegalArgumentException("Training calendar with id $calendarId not found")
        } else {
            val userDefault = try {
                trainingCalendarService.requireDefault(currentUser)
            } catch (e: CalendarSyncException) {
                throw e
            } catch (e: Exception) {
                null
            }
            userDefault ?: trainingCalendarService.requireDefault()
        }
        require(cal.enabled) { "Training calendar '${cal.displayName}' is disabled" }
        return cal
    }
}
