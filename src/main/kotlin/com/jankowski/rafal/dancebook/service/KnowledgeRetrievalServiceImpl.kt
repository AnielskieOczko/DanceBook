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
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class KnowledgeRetrievalServiceImpl(
    private val knowledgeChunkRepository: KnowledgeChunkRepository,
    private val materialRepository: MaterialRepository,
    private val danceFigureRepository: DanceFigureRepository,
    private val materialService: MaterialService,
    private val richTextService: RichTextService,
    private val embeddingModel: EmbeddingModel
) : KnowledgeRetrievalService {

    companion object {
        private val log = LoggerFactory.getLogger(KnowledgeRetrievalServiceImpl::class.java)
    }

    @Transactional(readOnly = true)
    override fun findRelatedNotesForMaterial(materialId: UUID, currentUser: AppUser?, limit: Int): List<Material> {
        val chunk = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.NOTE, materialId)
        val embedding = chunk?.embedding ?: run {
            val material = materialRepository.findById(materialId).orElse(null) ?: return emptyList()
            val text = listOfNotNull(material.name, richTextService.toPlainText(material.description)).joinToString("\n")
            try { embeddingModel.embed(text) } catch (e: Exception) { null }
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

    @Transactional(readOnly = true)
    override fun findRelatedNotesForFigure(figureId: UUID, currentUser: AppUser?, limit: Int): List<Material> {
        val chunk = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.FIGURE, figureId)
        val embedding = chunk?.embedding ?: run {
            val figure = danceFigureRepository.findById(figureId).orElse(null) ?: return emptyList()
            val text = listOfNotNull("Figure: ${figure.name}", figure.danceType?.name, figure.danceClass?.displayName).joinToString("\n")
            try { embeddingModel.embed(text) } catch (e: Exception) { null }
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
