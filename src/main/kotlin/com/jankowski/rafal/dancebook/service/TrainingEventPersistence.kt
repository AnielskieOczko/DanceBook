package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventCreatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventUpdatedEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
 */
@Component
class TrainingEventPersistence(
    private val trainingEventRepository: TrainingEventRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun insert(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        eventPublisher.publishEvent(TrainingEventCreatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun applyUpdate(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        eventPublisher.publishEvent(TrainingEventUpdatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun remove(event: TrainingEvent, actor: AppUser) {
        val id: UUID = event.id!!
        val title = event.title
        trainingEventRepository.delete(event)
        eventPublisher.publishEvent(TrainingEventDeletedEvent(id, title, actor))
    }
}
