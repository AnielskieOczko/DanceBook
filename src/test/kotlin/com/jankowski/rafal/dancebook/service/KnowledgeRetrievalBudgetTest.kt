package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.Optional
import java.util.UUID

class KnowledgeRetrievalBudgetTest {

    private class CountingModel : EmbeddingModel {
        var calls = 0
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(3), i) })
        override fun embed(document: org.springframework.ai.document.Document) = embed(document.text ?: "")
        override fun embed(text: String): FloatArray { calls++; return floatArrayOf(1f, 0f, 0f) }
        override fun dimensions() = 3
    }

    private class NoChunks : KnowledgeChunkRepository(mock(JdbcTemplate::class.java)) {
        override fun findFirstChunkBySource(sourceType: KnowledgeSourceType, sourceId: UUID): KnowledgeChunk? = null
        override fun findRelatedNotes(
            queryEmbedding: FloatArray, excludeSourceId: UUID?, currentUser: AppUser?, limit: Int
        ): List<KnowledgeChunk> = emptyList()
    }

    private fun service(budget: EmbeddingBudget, model: CountingModel, id: UUID): KnowledgeRetrievalServiceImpl {
        val materials = mock(MaterialRepository::class.java)
        `when`(materials.findById(id)).thenReturn(Optional.of(Material().apply { this.id = id; name = "n" }))
        return KnowledgeRetrievalServiceImpl(
            NoChunks(), materials, mock(DanceFigureRepository::class.java), mock(MaterialService::class.java),
            RichTextServiceImpl(), model, budget
        )
    }

    @Test
    fun `the assistant's related-notes embedding draws on the shared budget`() {
        val props = GoogleAiProperties(
            embeddingRequestsPerMinute = 10, embeddingSearchSharePercent = 10,
            embeddingSearchPerUserPerMinute = 100, embeddingInteractiveMaxWaitMs = 0
        )
        val budget = EmbeddingBudget(props, Clock.systemUTC())
        assertTrue(budget.tryAcquireForSearch(null)) // takes the only search slot
        val model = CountingModel()
        val id = UUID.randomUUID()

        assertEquals(emptyList<Material>(), service(budget, model, id).findRelatedNotesForMaterial(id, null))
        assertEquals(0, model.calls, "no slot, no provider call")
    }

    @Test
    fun `with budget available the embedding is made`() {
        val model = CountingModel()
        val id = UUID.randomUUID()
        val budget = EmbeddingBudget(GoogleAiProperties(embeddingRequestsPerMinute = 90), Clock.systemUTC())
        service(budget, model, id).findRelatedNotesForMaterial(id, null)
        assertEquals(1, model.calls)
    }
}
