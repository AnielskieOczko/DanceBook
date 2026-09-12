package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * Keeps a session's training record in step with the session.
 *
 * Not @Transactional and not a service: it is called from inside the transactional
 * persistence beans so a record commits with the session write that caused it. The activity
 * feed is written from an after-commit listener and can afford to lose a row; statistics
 * cannot, so this deliberately does not go the same way.
 */
@Component
class TrainingRecordWriter(
    private val trainingRecordRepository: TrainingRecordRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingRecordWriter::class.java)
    }

    /**
     * Writes, updates or removes the record for [event] according to its attendance status.
     *
     * Attended and skipped are confirmed outcomes and get a record; planned and cancelled do
     * not, and marking a session back to either removes the record — that is a correction of
     * a mis-mark, not a piece of history.
     *
     * The orphaned check runs before the outcome branch, deliberately, and not the other way
     * round: an orphaned record's session is gone, so anything arriving here for it would be
     * a write against something that no longer exists. Checking outcome first would mean an
     * event that is no longer confirmed could delete a frozen row — the exact thing this
     * table exists to prevent. An orphaned record is never touched again, whatever the event
     * says.
     */
    fun sync(event: TrainingEvent) {
        val eventId = requireNotNull(event.id) {
            "A training event must be saved before its record can be written"
        }
        val existing = trainingRecordRepository.findByTrainingEventId(eventId)

        if (existing != null && existing.isOrphaned) {
            log.warn("Training record for session {} is orphaned; leaving it frozen", eventId)
            return
        }

        val outcome = TrainingOutcome.from(event.attendanceStatus)

        if (outcome == null) {
            existing?.let {
                log.debug("Session {} is no longer confirmed; removing its training record", eventId)
                trainingRecordRepository.delete(it)
            }
            return
        }

        val record = existing ?: TrainingRecord().apply {
            trainingEventId = eventId
            createdBy = event.createdBy
            createdAt = LocalDateTime.now()
        }
        record.occurredAt = event.startTime
        record.durationMinutes = event.durationMinutes.toInt()
        record.outcome = outcome
        record.title = event.title
        record.eventType = event.eventType
        record.updatedAt = LocalDateTime.now()
        applySegments(record, event)

        trainingRecordRepository.save(record)
    }

    /**
     * Stamps the records of deleted sessions as orphaned. The rows stay — that is the entire
     * point of the table — and from here on they are frozen and read from their own snapshots.
     *
     * Records already stamped are left untouched, so a second delete cannot move the date on
     * which a record's session disappeared.
     */
    fun orphan(eventIds: Collection<UUID>) {
        if (eventIds.isEmpty()) return

        val records = trainingRecordRepository.findAllByTrainingEventIdIn(eventIds)
            .filterNot { it.isOrphaned }
        if (records.isEmpty()) return

        val orphanedAt = LocalDateTime.now()
        records.forEach { it.orphanedAt = orphanedAt }
        trainingRecordRepository.saveAll(records)
        log.debug("Orphaned {} training records across {} deleted sessions", records.size, eventIds.size)
    }

    /**
     * Rebuilds the snapshot of the style breakdown in place.
     *
     * Surviving rows are rewritten in place rather than cleared and refilled, for the same
     * reason as `TrainingEventServiceImpl.applySegments`: Hibernate flushes the child INSERTs
     * before the orphan DELETEs, so handing a new row a sortOrder the outgoing row still holds
     * trips unique_training_record_segment_sort_order mid-flush.
     *
     * Segments whose category is gone are dropped, and the survivors renumbered from zero, so
     * the stored sortOrders stay contiguous.
     */
    private fun applySegments(record: TrainingRecord, event: TrainingEvent) {
        val sources = event.segments.filter { it.danceCategory != null }

        sources.forEachIndexed { index, source ->
            val category = source.danceCategory!!
            if (index < record.segments.size) {
                record.segments[index].apply {
                    danceCategory = category
                    categoryName = category.name
                    durationMinutes = source.durationMinutes
                    sortOrder = index
                }
            } else {
                record.segments.add(TrainingRecordSegment().apply {
                    trainingRecord = record
                    danceCategory = category
                    categoryName = category.name
                    durationMinutes = source.durationMinutes
                    sortOrder = index
                })
            }
        }

        while (record.segments.size > sources.size) {
            record.segments.removeAt(record.segments.size - 1)
        }
    }
}
