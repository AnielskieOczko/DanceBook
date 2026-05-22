package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.CommentAddedEvent
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.ListCreatedEvent
import com.jankowski.rafal.dancebook.model.ListMadePublicEvent
import com.jankowski.rafal.dancebook.model.MaterialCreatedEvent
import com.jankowski.rafal.dancebook.model.MaterialDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureAddedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialFigureAdded(event: MaterialFigureAddedEvent) {
        log.info("Recording MATERIAL_FIGURE_ADDED event for figure '{}' on material '{}'", event.figureName, event.material.name)
        save(
            eventType = EventType.MATERIAL_FIGURE_ADDED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            metadata = event.figureName
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialFigureUpdated(event: MaterialFigureUpdatedEvent) {
        log.info("Recording MATERIAL_FIGURE_UPDATED event for figure '{}' on material '{}'", event.figureName, event.material.name)
        save(
            eventType = EventType.MATERIAL_FIGURE_UPDATED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            metadata = event.figureName
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialFigureDeleted(event: MaterialFigureDeletedEvent) {
        log.info("Recording MATERIAL_FIGURE_DELETED event for figure '{}' on material '{}'", event.figureName, event.material.name)
        save(
            eventType = EventType.MATERIAL_FIGURE_DELETED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            metadata = event.figureName
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onDanceFigureCreated(event: DanceFigureCreatedEvent) {
        log.info("Recording DANCE_FIGURE_CREATED event for '{}'", event.danceFigure.name)
        val targetName = "${event.danceFigure.danceType?.name ?: ""} - ${event.danceFigure.name}"
        save(EventType.DANCE_FIGURE_CREATED, event.actor, TargetType.DANCE_FIGURE, event.danceFigure.id, targetName)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onDanceFigureUpdated(event: DanceFigureUpdatedEvent) {
        log.info("Recording DANCE_FIGURE_UPDATED event for '{}'", event.danceFigure.name)
        val targetName = "${event.danceFigure.danceType?.name ?: ""} - ${event.danceFigure.name}"
        save(EventType.DANCE_FIGURE_UPDATED, event.actor, TargetType.DANCE_FIGURE, event.danceFigure.id, targetName)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onDanceFigureDeleted(event: DanceFigureDeletedEvent) {
        log.info("Recording DANCE_FIGURE_DELETED event for '{}'", event.danceFigureName)
        save(EventType.DANCE_FIGURE_DELETED, event.actor, TargetType.DANCE_FIGURE, event.danceFigureId, event.danceFigureName)
    }

    private fun save(
        eventType: EventType,
        actor: com.jankowski.rafal.dancebook.model.AppUser,
        targetType: TargetType,
        targetId: java.util.UUID?,
        targetName: String?,
        metadata: String? = null
    ) {
        val activityEvent = ActivityEvent().apply {
            this.eventType = eventType
            this.actor = actor
            this.targetType = targetType
            this.targetId = targetId
            this.targetName = targetName
            this.metadata = metadata
        }
        activityEventRepository.save(activityEvent)
    }
}
