package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.CommentAddedEvent
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.ListCreatedEvent
import com.jankowski.rafal.dancebook.model.ListMadePublicEvent
import com.jankowski.rafal.dancebook.model.MaterialCreatedEvent
import com.jankowski.rafal.dancebook.model.MaterialDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialVisibilityChangedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureAddedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventCreatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingEventDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingSeriesCreatedEvent
import com.jankowski.rafal.dancebook.model.TrainingSeriesDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkAttendanceUpdatedEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkDeletedEvent
import com.jankowski.rafal.dancebook.model.TrainingBulkUpdatedEvent
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
        save(
            eventType = EventType.MATERIAL_CREATED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            targetVisibility = event.material.visibility.name
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialUpdated(event: MaterialUpdatedEvent) {
        log.info("Recording MATERIAL_UPDATED event for '{}'", event.material.name)
        save(
            eventType = EventType.MATERIAL_UPDATED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            targetVisibility = event.material.visibility.name
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialDeleted(event: MaterialDeletedEvent) {
        val vis = if (event.wasPublic) "PUBLIC" else "PRIVATE"
        log.info("Recording MATERIAL_DELETED event for '{}' (wasPublic={})", event.materialName, event.wasPublic)
        save(
            eventType = EventType.MATERIAL_DELETED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.materialId,
            targetName = event.materialName,
            targetVisibility = vis
        )
        activityEventRepository.updateTargetVisibilityForTarget(TargetType.MATERIAL, event.materialId, vis)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onMaterialVisibilityChanged(event: MaterialVisibilityChangedEvent) {
        val vis = event.material.visibility.name
        log.info("Recording MATERIAL_VISIBILITY_CHANGED event for '{}' to {}", event.material.name, vis)
        save(
            eventType = EventType.MATERIAL_VISIBILITY_CHANGED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            metadata = vis,
            targetVisibility = vis
        )
        event.material.id?.let {
            activityEventRepository.updateTargetVisibilityForTarget(TargetType.MATERIAL, it, vis)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onCommentAdded(event: CommentAddedEvent) {
        log.info("Recording COMMENT_ADDED event on material '{}'", event.material.name)
        save(
            eventType = EventType.COMMENT_ADDED,
            actor = event.actor,
            targetType = TargetType.MATERIAL,
            targetId = event.material.id,
            targetName = event.material.name,
            targetVisibility = event.material.visibility.name
        )
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
            metadata = event.figureName,
            targetVisibility = event.material.visibility.name
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
            metadata = event.figureName,
            targetVisibility = event.material.visibility.name
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
            metadata = event.figureName,
            targetVisibility = event.material.visibility.name
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingEventCreated(event: TrainingEventCreatedEvent) {
        log.info("Recording TRAINING_EVENT_CREATED event for '{}'", event.trainingEvent.title)
        save(EventType.TRAINING_EVENT_CREATED, event.actor, TargetType.TRAINING_EVENT, event.trainingEvent.id, event.trainingEvent.title)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingEventUpdated(event: TrainingEventUpdatedEvent) {
        log.info("Recording TRAINING_EVENT_UPDATED event for '{}'", event.trainingEvent.title)
        save(EventType.TRAINING_EVENT_UPDATED, event.actor, TargetType.TRAINING_EVENT, event.trainingEvent.id, event.trainingEvent.title)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingEventDeleted(event: TrainingEventDeletedEvent) {
        log.info("Recording TRAINING_EVENT_DELETED event for '{}'", event.trainingEventTitle)
        save(EventType.TRAINING_EVENT_DELETED, event.actor, TargetType.TRAINING_EVENT, event.trainingEventId, event.trainingEventTitle)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingSeriesCreated(event: TrainingSeriesCreatedEvent) {
        log.info(
            "Recording TRAINING_SERIES_CREATED event for '{}' ({} occurrences)",
            event.firstOccurrence.title, event.occurrenceCount
        )
        save(
            eventType = EventType.TRAINING_SERIES_CREATED,
            actor = event.actor,
            targetType = TargetType.TRAINING_EVENT,
            targetId = event.firstOccurrence.id,
            targetName = event.firstOccurrence.title,
            metadata = event.occurrenceCount.toString()
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingSeriesDeleted(event: TrainingSeriesDeletedEvent) {
        log.info(
            "Recording TRAINING_SERIES_DELETED event for '{}' ({} occurrences, seriesRemoved={})",
            event.seriesTitle, event.deletedCount, event.seriesRemoved
        )
        val metadata = if (event.seriesRemoved) null else event.deletedCount.toString()
        save(
            eventType = EventType.TRAINING_SERIES_DELETED,
            actor = event.actor,
            targetType = TargetType.TRAINING_EVENT,
            targetId = null,
            targetName = event.seriesTitle,
            metadata = metadata
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingBulkAttendanceUpdated(event: TrainingBulkAttendanceUpdatedEvent) {
        log.info(
            "Recording TRAINING_BULK_ATTENDANCE_UPDATED event for {} sessions as {}",
            event.count, event.status
        )
        save(
            eventType = EventType.TRAINING_BULK_ATTENDANCE_UPDATED,
            actor = event.actor,
            targetType = TargetType.TRAINING_EVENT,
            targetId = null,
            targetName = event.status.name.lowercase(),
            metadata = event.count.toString()
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingBulkDeleted(event: TrainingBulkDeletedEvent) {
        log.info(
            "Recording TRAINING_BULK_DELETED event for {} sessions",
            event.count
        )
        save(
            eventType = EventType.TRAINING_BULK_DELETED,
            actor = event.actor,
            targetType = TargetType.TRAINING_EVENT,
            targetId = null,
            targetName = "${event.count} sessions",
            metadata = event.count.toString()
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTrainingBulkUpdated(event: TrainingBulkUpdatedEvent) {
        log.info(
            "Recording TRAINING_BULK_UPDATED event for {} sessions ({})",
            event.count, event.updateType
        )
        save(
            eventType = EventType.TRAINING_BULK_UPDATED,
            actor = event.actor,
            targetType = TargetType.TRAINING_EVENT,
            targetId = null,
            targetName = "${event.count} sessions",
            metadata = event.updateType
        )
    }

    private fun save(
        eventType: EventType,
        actor: com.jankowski.rafal.dancebook.model.AppUser,
        targetType: TargetType,
        targetId: java.util.UUID?,
        targetName: String?,
        metadata: String? = null,
        targetVisibility: String? = null
    ) {
        val activityEvent = ActivityEvent().apply {
            this.eventType = eventType
            this.actor = actor
            this.targetType = targetType
            this.targetId = targetId
            this.targetName = targetName
            this.metadata = metadata
            this.targetVisibility = targetVisibility
        }
        activityEventRepository.save(activityEvent)
    }
}
