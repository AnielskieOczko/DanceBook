package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

data class ReconcileResult(
    val adopted: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val skippedNoOp: Int = 0
)

/**
 * Applies inbound changes from Google Calendar to the local database.
 *
 * All local writes go through [TrainingEventPersistence] so they commit with training record
 * updates and publish domain events without echoing back to Google.
 */
@Component
class CalendarReconciler(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingEventPersistence: TrainingEventPersistence,
    private val appUserService: AppUserService
) {

    internal var nowProvider: () -> LocalDateTime = { LocalDateTime.now() }

    companion object {
        private val log = LoggerFactory.getLogger(CalendarReconciler::class.java)
    }

    @Transactional
    fun reconcile(calendar: TrainingCalendar, changeSet: CalendarChangeSet): ReconcileResult {
        val rootAdmin = appUserService.getRootAdmin()
        var adopted = 0
        var updated = 0
        var deleted = 0
        var skippedNoOp = 0

        for (change in changeSet.changes) {
            when (change) {
                is CalendarChange.Upserted -> {
                    val existingOpt = trainingEventRepository.findByGoogleEventId(change.googleEventId)
                    if (existingOpt.isPresent) {
                        val existing = existingOpt.get()
                        // Ensure event belongs to the calendar being synced
                        if (existing.calendar?.id != calendar.id) {
                            log.warn(
                                "Google event {} belongs to calendar {} but was received on calendar {}; skipping",
                                change.googleEventId, existing.calendar?.id, calendar.id
                            )
                            continue
                        }

                        // Rule 3: No-op changes are skipped entirely
                        if (existing.title == change.title &&
                            existing.startTime == change.start &&
                            existing.endTime == change.end
                        ) {
                            log.debug("Google event {} matches local event {}; skipping no-op", change.googleEventId, existing.id)
                            skippedNoOp++
                            continue
                        }

                        // Rule 1: Google owns title, start and end
                        existing.title = change.title
                        existing.startTime = change.start
                        existing.endTime = change.end
                        existing.updatedAt = LocalDateTime.now()

                        // Rule 6: A shortened session trims its style breakdown from the end
                        val newDurationMinutes = Duration.between(change.start, change.end).toMinutes()
                        while (existing.segments.isNotEmpty() &&
                            existing.segments.sumOf { it.durationMinutes } > newDurationMinutes
                        ) {
                            val removed = existing.segments.removeAt(existing.segments.lastIndex)
                            log.info(
                                "Dropped segment {} ({} min) from event {} because session was shortened to {} min",
                                removed.danceCategory?.name, removed.durationMinutes, existing.id, newDurationMinutes
                            )
                        }

                        // Note: Attendance, event type and description are NOT touched (Rules 1 & 2)
                        trainingEventPersistence.applyUpdate(existing, rootAdmin)
                        updated++
                    } else {
                        // Rule 7: Adoption of unknown event
                        val newEvent = TrainingEvent().apply {
                            this.googleEventId = change.googleEventId
                            this.title = change.title
                            this.startTime = change.start
                            this.endTime = change.end
                            this.description = change.description
                            this.eventType = TrainingEventType.TRAINING
                            this.calendar = calendar
                            this.createdBy = rootAdmin
                            this.setAttendance(rootAdmin, AttendanceStatus.PLANNED)
                        }
                        trainingEventPersistence.insert(newEvent, rootAdmin)
                        adopted++
                    }
                }
                is CalendarChange.Cancelled -> {
                    val existingOpt = trainingEventRepository.findByGoogleEventId(change.googleEventId)
                    if (existingOpt.isPresent) {
                        val existing = existingOpt.get()
                        if (existing.calendar?.id != calendar.id) {
                            log.warn(
                                "Cancelled Google event {} belongs to calendar {} but was received on calendar {}; skipping",
                                change.googleEventId, existing.calendar?.id, calendar.id
                            )
                            continue
                        }
                        // Rule 4: Deletion routes to TrainingEventPersistence.remove, which orphans its training record
                        trainingEventPersistence.remove(existing, rootAdmin)
                        deleted++
                    } else {
                        // Unknown cancellation is ignored (Rule 4)
                        log.debug("Ignoring cancellation for unknown Google event {}", change.googleEventId)
                    }
                }
            }
        }

        // Rule 5: Infer deletions on a complete full sync window
        val calendarId = calendar.id
        val windowStart = changeSet.windowStart
        if (changeSet.isCompleteWindow && windowStart != null && calendarId != null) {
            val returnedGoogleIds = changeSet.changes.map { it.googleEventId }.toSet()
            val now = nowProvider()
            val graceCutoff = now.minusMinutes(1)

            val localEvents = trainingEventRepository.findAllByCalendarId(calendarId)
            for (event in localEvents) {
                val googleId = event.googleEventId
                // Condition 1: Must belong to this calendar
                if (event.calendar?.id != calendarId) {
                    continue
                }
                // Condition 2: Must have a google_event_id
                if (googleId.isNullOrBlank()) {
                    continue
                }
                // Condition 3: Start time inside the fetched window
                if (event.startTime.isBefore(windowStart)) {
                    continue
                }
                // Condition 5: Google did not return it
                if (googleId in returnedGoogleIds) {
                    continue
                }
                // Grace period: exclude rows created or updated in the last minute
                if (!event.createdAt.isBefore(graceCutoff) || !event.updatedAt.isBefore(graceCutoff)) {
                    log.debug(
                        "Skipping deletion of event {} within grace period (created={}, updated={})",
                        event.id, event.createdAt, event.updatedAt
                    )
                    continue
                }

                log.info(
                    "Inferring deletion for event {} ('{}', googleId={}) absent from complete window on calendar '{}'",
                    event.id, event.title, googleId, calendar.displayName
                )
                trainingEventPersistence.remove(event, rootAdmin)
                deleted++
            }
        }

        return ReconcileResult(
            adopted = adopted,
            updated = updated,
            deleted = deleted,
            skippedNoOp = skippedNoOp
        )
    }
}
