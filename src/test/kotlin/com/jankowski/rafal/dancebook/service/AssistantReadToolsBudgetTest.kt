package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.KnowledgeSearchResult
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
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
import java.time.ZoneOffset
import java.util.UUID

class AssistantReadToolsBudgetTest {

    private class CountingModel : EmbeddingModel {
        var calls = 0
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(3), i) })
        override fun embed(document: org.springframework.ai.document.Document) = embed(document.text ?: "")
        override fun embed(text: String): FloatArray { calls++; return floatArrayOf(1f, 0f, 0f) }
        override fun dimensions() = 3
    }

    private class EmptyChunks : KnowledgeChunkRepository(mock(JdbcTemplate::class.java)) {
        override fun hybridSearch(
            query: String?, queryEmbedding: FloatArray?, sourceTypes: List<KnowledgeSourceType>?,
            currentUser: AppUser?, limit: Int, topK: Int, k: Int, maxDistance: Double, danceTypeId: UUID?
        ): List<KnowledgeSearchResult> = emptyList()
    }

    private val user = AppUser().apply { id = UUID.randomUUID() }
    private val model = CountingModel()

    private fun tools(props: GoogleAiProperties, current: AppUser?): AssistantReadTools {
        val appUserService = mock(AppUserService::class.java)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(current)
        `when`(appUserService.getCurrentUser()).thenReturn(current ?: user)
        return AssistantReadTools(
            mock(MaterialService::class.java), mock(DanceFigureService::class.java), mock(DanceTypeService::class.java),
            mock(TrainingEventService::class.java), appUserService, RichTextServiceImpl(),
            Clock.fixed(java.time.Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC),
            EmptyChunks(), model, null, null, EmbeddingBudget(props, Clock.systemUTC()), AssistantFeature(props)
        )
    }

    @Test
    fun `with no API key the tools never embed and never wait`() {
        val props = GoogleAiProperties(apiKey = "", embeddingRequestsPerMinute = 100, embeddingInteractiveMaxWaitMs = 5000)
        val started = System.nanoTime()
        tools(props, user).searchKnowledge("sway")
        assertEquals(0, model.calls)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
    }

    @Test
    fun `an anonymous caller is never embedded for`() {
        val props = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 100, embeddingInteractiveMaxWaitMs = 5000)
        val started = System.nanoTime()
        tools(props, null).searchKnowledge("sway")
        assertEquals(0, model.calls)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000, "no semantic search for anonymous, and no waiting either")
    }

    @Test
    fun `a signed-in caller embeds through the assistant share`() {
        val props = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 100)
        tools(props, user).searchKnowledge("sway")
        assertEquals(1, model.calls)
    }
}
