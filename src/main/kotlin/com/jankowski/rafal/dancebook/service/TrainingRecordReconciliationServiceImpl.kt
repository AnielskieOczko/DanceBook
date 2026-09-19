package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class TrainingRecordReconciliationServiceImpl(
    private val trainingRecordRepository: TrainingRecordRepository,
    private val trainingEventRepository: TrainingEventRepository
) : TrainingRecordReconciliationService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingRecordReconciliationServiceImpl::class.java)
    }

    /**
     * Read-modify-write rather than a bulk `UPDATE ... FROM`, which would have to be native:
     * [com.jankowski.rafal.dancebook.model.TrainingRecord] deliberately holds only a raw
     * `trainingEventId` and no association, so JPQL cannot express the join. Going through the
     * entities keeps the statement dialect-free, cannot be defeated by a stale persistence
     * context, and yields an exact repaired count for the log.
     *
     * It does load every calendar-less record at once, which on a restored database is all of
     * them. That is a deliberate trade at this project's scale — a personal training log, not a
     * multi-tenant table — and the point to revisit if the row count ever stops being small.
     *
     * Orphaned records are excluded by the query alone. The in-memory guard that used to sit
     * here could never fire and only suggested the query might return them; the invariant that
     * a frozen record is never rewritten is enforced by the query name and held by
     * `TrainingRecordReconciliationServiceTest` and its integration counterpart.
     */
    @Transactional
    override fun reconcile(): Int {
        val candidates = trainingRecordRepository.findAllByCalendarIdIsNullAndOrphanedAtIsNull()
        if (candidates.isEmpty()) {
            return 0
        }

        val eventIds = candidates.mapNotNull { it.trainingEventId }
        if (eventIds.isEmpty()) {
            return 0
        }

        val eventsById = trainingEventRepository.findAllById(eventIds).associateBy { it.id }

        val toUpdate = mutableListOf<TrainingRecord>()
        for (record in candidates) {
            val eventId = record.trainingEventId ?: continue
            val event = eventsById[eventId] ?: continue
            val calendar = event.calendar ?: continue

            record.calendarId = calendar.id
            record.calendarName = calendar.displayName
            record.updatedAt = LocalDateTime.now()
            toUpdate.add(record)
        }

        if (toUpdate.isNotEmpty()) {
            trainingRecordRepository.saveAll(toUpdate)
            log.info("Repaired {} training records with missing calendar references", toUpdate.size)
        }

        return toUpdate.size
    }
}
