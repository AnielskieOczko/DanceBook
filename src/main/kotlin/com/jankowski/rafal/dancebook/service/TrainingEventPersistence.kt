package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkAttendanceUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventCreatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventUpdatedEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * Holds the short local write for a training event.
 *
 * A separate bean on purpose. The sync methods on TrainingEventServiceImpl must not be
 * @Transactional — they perform Google Calendar I/O and would otherwise hold a DB
 * connection across the network call. But the save still has to run inside a transaction,
 * because @TransactionalEventListener(AFTER_COMMIT) silently drops events published
 * outside one, and a self-invoked @Transactional method on the same bean bypasses the
 * proxy entirely. Calling across bean boundaries is what makes the annotation apply.
 *
 * It is also where a session's training record is written, for the same transactional
 * reason: the record must commit with the session or not at all.
 */
@Component
class TrainingEventPersistence(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordWriter: TrainingRecordWriter,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun insert(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        // In this transaction, not in an after-commit listener: statistics are read from the
        // record, and a lost record is a lost hour of training.
        trainingRecordWriter.sync(saved)
        eventPublisher.publishEvent(TrainingEventCreatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun applyUpdate(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        trainingRecordWriter.sync(saved)
        eventPublisher.publishEvent(TrainingEventUpdatedEvent(saved, actor))
        return saved
    }

    /**
     * Updates attendance across multiple sessions in one transaction, syncing their records
     * and publishing a single bulk activity event rather than one per session.
     */
    @Transactional
    fun bulkUpdateAttendance(
        events: List<TrainingEvent>,
        status: AttendanceStatus,
        actor: AppUser
    ): List<TrainingEvent> {
        if (events.isEmpty()) return emptyList()

        val now = LocalDateTime.now()
        events.forEach { event ->
            event.attendanceStatus = status
            event.updatedAt = now
        }
        val saved = trainingEventRepository.saveAll(events)
        saved.forEach { trainingRecordWriter.sync(it) }
        eventPublisher.publishEvent(TrainingBulkAttendanceUpdatedEvent(saved.size, status, actor))
        return saved
    }

    @Transactional
    fun remove(event: TrainingEvent, actor: AppUser) {
        val id: UUID = event.id!!
        val title = event.title
        // The record is marked orphaned rather than deleted: the schedule entry is disposable,
        // the training that happened is not.
        trainingRecordWriter.orphan(listOf(id))
        trainingEventRepository.delete(event)
        eventPublisher.publishEvent(TrainingEventDeletedEvent(id, title, actor))
    }
}
