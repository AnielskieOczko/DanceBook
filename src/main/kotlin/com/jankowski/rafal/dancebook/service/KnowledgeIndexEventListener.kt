package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ChoreographyCreatedEvent
import com.jankowski.rafal.dancebook.model.ChoreographyDeletedEvent
import com.jankowski.rafal.dancebook.model.ChoreographyUpdatedEvent
import com.jankowski.rafal.dancebook.model.ChoreographyVisibilityChangedEvent
import com.jankowski.rafal.dancebook.model.CommentAddedEvent
import com.jankowski.rafal.dancebook.model.CommentDeletedEvent
import com.jankowski.rafal.dancebook.model.CommentUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.MaterialCreatedEvent
import com.jankowski.rafal.dancebook.model.MaterialDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureAddedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialVisibilityChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for domain events to keep the knowledge index current.
 * Uses AFTER_COMMIT so saves never wait on the embedding call.
 * Deletes and visibility changes apply immediately without re-embedding.
 */
@Component
class KnowledgeIndexEventListener(
    private val knowledgeIndexService: KnowledgeIndexService
) {

    companion object {
        private val log = LoggerFactory.getLogger(KnowledgeIndexEventListener::class.java)
    }

    // ── Material (Notes) ───────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialCreated(event: MaterialCreatedEvent) {
        log.debug("Queueing knowledge indexing for created material {}", event.material.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, event.material.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialUpdated(event: MaterialUpdatedEvent) {
        log.debug("Queueing knowledge indexing for updated material {}", event.material.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, event.material.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialFigureAdded(event: MaterialFigureAddedEvent) {
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, event.material.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialFigureUpdated(event: MaterialFigureUpdatedEvent) {
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, event.material.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialFigureDeleted(event: MaterialFigureDeletedEvent) {
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, event.material.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialDeleted(event: MaterialDeletedEvent) {
        log.debug("Removing knowledge chunks for deleted material {}", event.materialId)
        knowledgeIndexService.deleteChunks(KnowledgeSourceType.NOTE, event.materialId)
        if (event.commentIds.isNotEmpty()) {
            knowledgeIndexService.deleteComments(event.commentIds)
        }
        knowledgeIndexService.deleteCommentsForMaterial(event.materialId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMaterialVisibilityChanged(event: MaterialVisibilityChangedEvent) {
        log.debug("Updating visibility for material {} to {}", event.material.id, event.material.visibility)
        knowledgeIndexService.updateVisibility(KnowledgeSourceType.NOTE, event.material.id!!, event.material.visibility)
        knowledgeIndexService.updateCommentsVisibilityForMaterial(event.material.id!!, event.material.visibility)
    }

    // ── Comments ───────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommentAdded(event: CommentAddedEvent) {
        log.debug("Queueing knowledge indexing for added comment {}", event.comment.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE_COMMENT, event.comment.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommentUpdated(event: CommentUpdatedEvent) {
        log.debug("Queueing knowledge indexing for updated comment {}", event.comment.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE_COMMENT, event.comment.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommentDeleted(event: CommentDeletedEvent) {
        log.debug("Removing knowledge chunk for deleted comment {}", event.commentId)
        knowledgeIndexService.deleteChunks(KnowledgeSourceType.NOTE_COMMENT, event.commentId)
    }

    // ── Dance Figures ──────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDanceFigureCreated(event: DanceFigureCreatedEvent) {
        log.debug("Queueing knowledge indexing for created figure {}", event.danceFigure.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.FIGURE, event.danceFigure.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDanceFigureUpdated(event: DanceFigureUpdatedEvent) {
        log.debug("Queueing knowledge indexing for updated figure {}", event.danceFigure.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.FIGURE, event.danceFigure.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDanceFigureDeleted(event: DanceFigureDeletedEvent) {
        log.debug("Removing knowledge chunks for deleted figure {}", event.danceFigureId)
        knowledgeIndexService.deleteChunks(KnowledgeSourceType.FIGURE, event.danceFigureId)
    }

    // ── Choreographies ─────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChoreographyCreated(event: ChoreographyCreatedEvent) {
        log.debug("Queueing knowledge indexing for created choreography {}", event.choreography.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.CHOREOGRAPHY, event.choreography.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChoreographyUpdated(event: ChoreographyUpdatedEvent) {
        log.debug("Queueing knowledge indexing for updated choreography {}", event.choreography.id)
        knowledgeIndexService.queueIndex(KnowledgeSourceType.CHOREOGRAPHY, event.choreography.id!!)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChoreographyDeleted(event: ChoreographyDeletedEvent) {
        log.debug("Removing knowledge chunks for deleted choreography {}", event.choreographyId)
        knowledgeIndexService.deleteChunks(KnowledgeSourceType.CHOREOGRAPHY, event.choreographyId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChoreographyVisibilityChanged(event: ChoreographyVisibilityChangedEvent) {
        log.debug("Updating visibility for choreography {} to {}", event.choreography.id, event.choreography.visibility)
        knowledgeIndexService.updateVisibility(KnowledgeSourceType.CHOREOGRAPHY, event.choreography.id!!, event.choreography.visibility)
    }
}
