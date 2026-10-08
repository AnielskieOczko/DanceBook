package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class KnowledgeRetrievalServiceImpl(
    private val knowledgeChunkRepository: KnowledgeChunkRepository,
    private val materialRepository: MaterialRepository,
    private val danceFigureRepository: DanceFigureRepository,
    private val materialService: MaterialService,
    private val richTextService: RichTextService,
    private val embeddingModel: EmbeddingModel,
    private val embeddingBudget: EmbeddingBudget,
    private val assistantFeature: com.jankowski.rafal.dancebook.config.AssistantFeature,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate
) : KnowledgeRetrievalService {

    companion object {
        private val log = LoggerFactory.getLogger(KnowledgeRetrievalServiceImpl::class.java)
    }

    /**
     * Embeds through the shared budget, outside any transaction (the caller loads its text in a
     * short one first, so a slow provider cannot hold a connection). A page view never waits for
     * a slot: null (no related notes) when none is free, there is no API key, the visitor is
     * anonymous, or the call fails.
     */
    private fun embedQuery(text: String, user: AppUser?): FloatArray? {
        if (!assistantFeature.enabled) return null
        if (!embeddingBudget.tryAcquireInteractive(user?.id, maxWaitMs = 0)) return null
        return try { embeddingModel.embed(text) } catch (e: Exception) { null }
    }

    private fun <T> readOnly(block: () -> T?): T? =
        transactionTemplate.execute { block() }

    override fun findRelatedNotesForMaterial(materialId: UUID, currentUser: AppUser?, limit: Int): List<Material> {
        val chunk = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.NOTE, materialId)
        val embedding = chunk?.embedding ?: run {
            val text = readOnly {
                val material = materialRepository.findById(materialId).orElse(null)
                material?.let { listOfNotNull(it.name, richTextService.toPlainText(it.description)).joinToString("\n") }
            } ?: return emptyList()
            embedQuery(text, currentUser)
        } ?: return emptyList()

        val relatedChunks = knowledgeChunkRepository.findRelatedNotes(embedding, excludeSourceId = materialId, currentUser = currentUser, limit = limit)
        return relatedChunks.mapNotNull {
            try {
                materialService.findById(it.sourceId)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.id }
    }

    override fun findRelatedNotesForFigure(figureId: UUID, currentUser: AppUser?, limit: Int): List<Material> {
        val chunk = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.FIGURE, figureId)
        val embedding = chunk?.embedding ?: run {
            val text = readOnly {
                val figure = danceFigureRepository.findById(figureId).orElse(null)
                figure?.let { listOfNotNull("Figure: ${it.name}", it.danceType?.name, it.danceClass?.displayName).joinToString("\n") }
            } ?: return emptyList()
            embedQuery(text, currentUser)
        } ?: return emptyList()

        val relatedChunks = knowledgeChunkRepository.findRelatedNotes(embedding, excludeSourceId = figureId, currentUser = currentUser, limit = limit)
        return relatedChunks.mapNotNull {
            try {
                materialService.findById(it.sourceId)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.id }
    }
}
