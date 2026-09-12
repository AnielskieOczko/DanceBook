package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingHistoryMonth
import com.jankowski.rafal.dancebook.dto.TrainingHistoryRow
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Confirmed training, newest first, grouped by month.
 *
 * Records outlive the sessions they describe, so they need somewhere to be seen: the calendar,
 * the agenda and the timeline all draw `training_event`, and none of them can show a session
 * that has been deleted. This page is where an orphaned record is visible, and the only place
 * one can be corrected.
 *
 * Loads the whole history rather than windowing it, the way the statistics page does: a user's
 * confirmed sessions are few, and grouping needs all of a month at once anyway.
 */
@Service
@Transactional(readOnly = true)
class TrainingHistoryServiceImpl(
    private val trainingRecordRepository: TrainingRecordRepository,
    private val appUserService: AppUserService
) : TrainingHistoryService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingHistoryServiceImpl::class.java)
        private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    }

    override fun historyForCurrentUser(): TrainingHistory {
        val currentUser = appUserService.getCurrentUser()
        val records = trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser)
        log.debug("Building training history of {} records for user '{}'", records.size, currentUser.username)

        // groupBy keeps insertion order, and the records arrive newest first, so the months
        // come out newest first without a second sort.
        val months = records.groupBy { YearMonth.from(it.occurredAt) }
            .map { (month, monthRecords) ->
                TrainingHistoryMonth(
                    label = month.format(MONTH_LABEL),
                    rows = monthRecords.map {
                        TrainingHistoryRow(it, TrainingEventPalette.swatchFor(it.outcome))
                    },
                    attendedMinutes = monthRecords
                        .filter { it.outcome == TrainingOutcome.ATTENDED }
                        .sumOf { it.durationMinutes.toLong() }
                )
            }

        return TrainingHistory(months)
    }

    /**
     * Deliberately publishes no domain event, unlike every other mutating service method in
     * this codebase. `EventType`/`TargetType` in `model/ActivityEvent.kt` have no concept of
     * a training record, so logging this would mean growing the activity feed's vocabulary
     * for an action that is not first-class: removing a mis-marked record is a correction, the
     * same way editing a session's attendance back to planned removes its record without an
     * event of its own. The user-visible action that actually created this orphan — deleting
     * the session — already published `TrainingEventDeletedEvent` at that time, so the feed
     * already has an entry for it.
     */
    @Transactional
    override fun deleteOrphanedRecord(recordId: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val record = trainingRecordRepository.findById(recordId).orElseThrow {
            EntityNotFoundException("Could not find training record with id $recordId")
        }
        checkOwnership(record, currentUser)
        check(record.isOrphaned) {
            "This session still exists — change its attendance there rather than removing the record"
        }

        log.debug("User '{}' removing orphaned training record '{}'", currentUser.username, record.title)
        trainingRecordRepository.delete(record)
    }

    private fun checkOwnership(record: TrainingRecord, currentUser: AppUser) {
        if (record.createdBy?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to remove this training record")
        }
    }
}
