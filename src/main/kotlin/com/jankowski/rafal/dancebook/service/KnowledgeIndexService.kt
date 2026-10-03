package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Visibility
import java.util.UUID

interface KnowledgeIndexService {
    fun indexMaterial(materialId: UUID)
    fun indexComment(commentId: UUID)
    fun indexFigure(danceFigureId: UUID)
    fun indexChoreography(choreographyId: UUID)

    fun queueIndex(sourceType: KnowledgeSourceType, sourceId: UUID)
    fun processQueueAsync()
    fun processQueue()

    fun deleteChunks(sourceType: KnowledgeSourceType, sourceId: UUID)
    fun deleteComments(commentIds: Collection<UUID>)
    fun deleteCommentsForMaterial(materialId: UUID)
    fun updateVisibility(sourceType: KnowledgeSourceType, sourceId: UUID, visibility: Visibility)
    fun updateCommentsVisibilityForMaterial(materialId: UUID, visibility: Visibility)

    fun isStale(): Boolean
    fun rebuildAll(): RebuildReport
    fun getStatus(): KnowledgeIndexStatus
}

data class RebuildReport(
    val indexedNotes: Int,
    val indexedComments: Int,
    val indexedFigures: Int,
    val indexedChoreographies: Int,
    val totalChunks: Int,
    val embeddedChunks: Int = 0,
    val durationMs: Long
)

data class KnowledgeIndexStatus(
    val totalChunks: Int,
    val embeddedChunks: Int,
    val isStale: Boolean
) {
    val isComplete: Boolean get() = totalChunks == 0 || embeddedChunks >= totalChunks
}
