package com.jankowski.rafal.dancebook.model

import java.time.LocalDateTime
import java.util.UUID

enum class KnowledgeSourceType {
    NOTE,
    NOTE_COMMENT,
    FIGURE,
    CHOREOGRAPHY
}

data class KnowledgeChunk(
    val id: UUID = UUID.randomUUID(),
    val sourceType: KnowledgeSourceType,
    val sourceId: UUID,
    val chunkIndex: Int = 0,
    val content: String,
    val embedding: FloatArray? = null,
    val embeddingModel: String,
    val ownerId: UUID? = null,
    val visibility: Visibility,
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeChunk) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class KnowledgeSearchResult(
    val chunk: KnowledgeChunk,
    val rrfScore: Double,
    /** Cosine distance of the vector match, or null when the chunk matched by text only. */
    val distance: Double? = null
)
