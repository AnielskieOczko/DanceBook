package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@Service
class CalendarSyncServiceImpl(
    private val trainingCalendarRepository: TrainingCalendarRepository,
    private val googleCalendarClient: GoogleCalendarClient,
    private val calendarReconciler: CalendarReconciler
) : CalendarSyncService {

    companion object {
        private val log = LoggerFactory.getLogger(CalendarSyncServiceImpl::class.java)
    }

    private val syncLock = ReentrantLock()

    override fun syncAll(): SyncReport {
        if (!syncLock.tryLock(5, TimeUnit.SECONDS)) {
            log.warn("Calendar sync already in progress; skipping request")
            return SyncReport(emptyList())
        }

        try {
            val calendars = trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()
            if (calendars.isEmpty()) {
                log.info("No enabled calendars configured; sync is a no-op")
                return SyncReport(emptyList())
            }

            val outcomes = mutableListOf<CalendarSyncOutcome>()
            for (calendar in calendars) {
                try {
                    val outcome = performSync(calendar)
                    outcomes.add(outcome)
                } catch (e: Exception) {
                    log.error("Failed to sync calendar '{}' ({})", calendar.displayName, calendar.googleCalendarId, e)
                    outcomes.add(
                        CalendarSyncOutcome(
                            calendar = calendar,
                            success = false,
                            errorMessage = e.message ?: "Failed to sync calendar '${calendar.displayName}'"
                        )
                    )
                }
            }
            return SyncReport(outcomes)
        } finally {
            syncLock.unlock()
        }
    }

    override fun syncCalendar(calendarId: UUID): CalendarSyncOutcome {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            IllegalArgumentException("Calendar with id $calendarId not found")
        }
        if (!calendar.enabled) {
            return CalendarSyncOutcome(calendar, success = true)
        }

        if (!syncLock.tryLock(5, TimeUnit.SECONDS)) {
            log.warn("Calendar sync already in progress; skipping request")
            return CalendarSyncOutcome(calendar, success = false, errorMessage = "Sync already in progress")
        }

        return try {
            performSync(calendar)
        } catch (e: Exception) {
            log.error("Failed to sync calendar '{}' ({})", calendar.displayName, calendar.googleCalendarId, e)
            CalendarSyncOutcome(calendar, success = false, errorMessage = e.message ?: "Sync failed")
        } finally {
            syncLock.unlock()
        }
    }

    private fun performSync(calendar: TrainingCalendar): CalendarSyncOutcome {
        val initialChangeSet = googleCalendarClient.listChanges(calendar.googleCalendarId, calendar.syncToken)

        val (changeSetToApply, nextToken) = if (initialChangeSet.fullResyncRequired) {
            log.info("Sync token expired for calendar '{}', clearing token and performing full resync", calendar.displayName)
            calendar.syncToken = null
            trainingCalendarRepository.save(calendar)
            val fullChangeSet = googleCalendarClient.listChanges(calendar.googleCalendarId, null)
            fullChangeSet to fullChangeSet.nextSyncToken
        } else {
            initialChangeSet to initialChangeSet.nextSyncToken
        }

        val result = calendarReconciler.reconcile(calendar, changeSetToApply)

        // Advance token only after reconcile succeeds completely
        calendar.syncToken = nextToken
        calendar.lastSyncedAt = LocalDateTime.now()
        trainingCalendarRepository.save(calendar)

        log.info(
            "Synchronized calendar '{}': {} adopted, {} updated, {} deleted, {} no-op",
            calendar.displayName, result.adopted, result.updated, result.deleted, result.skippedNoOp
        )

        return CalendarSyncOutcome(
            calendar = calendar,
            success = true,
            adoptedCount = result.adopted,
            updatedCount = result.updated,
            deletedCount = result.deleted
        )
    }
}
