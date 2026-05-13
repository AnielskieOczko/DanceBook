package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.CommentAddedEvent
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.ListCreatedEvent
import com.jankowski.rafal.dancebook.model.ListMadePublicEvent
import com.jankowski.rafal.dancebook.model.MaterialCreatedEvent
import com.jankowski.rafal.dancebook.model.MaterialDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialUpdatedEvent
import com.jankowski.rafal.dancebook.model.TargetType
import com.jankowski.rafal.dancebook.repository.ActivityEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for domain events and persists them as ActivityEvent rows.
 * Uses AFTER_COMMIT so events are only recorded if the main transaction succeeds.
 * Runs in a new transaction (REQUIRES_NEW) so the listener has its own TX context.
 */
@Component
class ActivityEventListener(
    private val activityEventRepository: ActivityEventRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(ActivityEventListener::class.java)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialCreated(event: MaterialCreatedEvent) {
        log.info("Recording MATERIAL_CREATED event for '{}'", event.material.name)
        save(EventType.MATERIAL_CREATED, event.actor, TargetType.MATERIAL, event.material.id, event.material.name)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialUpdated(event: MaterialUpdatedEvent) {
        log.info("Recording MATERIAL_UPDATED event for '{}'", event.material.name)
        save(EventType.MATERIAL_UPDATED, event.actor, TargetType.MATERIAL, event.material.id, event.material.name)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialDeleted(event: MaterialDeletedEvent) {
        log.info("Recording MATERIAL_DELETED event for '{}'", event.materialName)
        save(EventType.MATERIAL_DELETED, event.actor, TargetType.MATERIAL, event.materialId, event.materialName)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onCommentAdded(event: CommentAddedEvent) {
        log.info("Recording COMMENT_ADDED event on material '{}'", event.material.name)
        save(EventType.COMMENT_ADDED, event.actor, TargetType.MATERIAL, event.material.id, event.material.name)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onListCreated(event: ListCreatedEvent) {
        log.info("Recording LIST_CREATED event for '{}'", event.list.name)
        save(EventType.LIST_CREATED, event.actor, TargetType.CUSTOM_LIST, event.list.id, event.list.name)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onListMadePublic(event: ListMadePublicEvent) {
        log.info("Recording LIST_MADE_PUBLIC event for '{}'", event.list.name)
        save(EventType.LIST_MADE_PUBLIC, event.actor, TargetType.CUSTOM_LIST, event.list.id, event.list.name)
    }

    private fun save(
        eventType: EventType,
        actor: com.jankowski.rafal.dancebook.model.AppUser,
        targetType: TargetType,
        targetId: java.util.UUID?,
        targetName: String?
    ) {
        val activityEvent = ActivityEvent().apply {
            this.eventType = eventType
            this.actor = actor
            this.targetType = targetType
            this.targetId = targetId
            this.targetName = targetName
        }
        activityEventRepository.save(activityEvent)
    }
}
