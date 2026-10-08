package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Optional
import java.util.UUID

class KnowledgeRetrievalBudgetTest {

    /** Records whether a transaction is open while the provider is called. */
    private class TrackingTx : PlatformTransactionManager {
        @Volatile var open = false
        val readOnlyFlags = mutableListOf<Boolean>()
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus { open = true; readOnlyFlags += definition!!.isReadOnly; return SimpleTransactionStatus() }
        override fun commit(status: TransactionStatus) { open = false }
        override fun rollback(status: TransactionStatus) { open = false }
    }

    private class CountingModel(private val tx: TrackingTx) : EmbeddingModel {
        var calls = 0
        var embeddedInsideTransaction = false
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(3), i) })
        override fun embed(document: org.springframework.ai.document.Document) = embed(document.text ?: "")
        override fun embed(text: String): FloatArray {
            calls++
            embeddedInsideTransaction = tx.open
            return floatArrayOf(1f, 0f, 0f)
        }
        override fun dimensions() = 3
    }

    private class NoChunks : KnowledgeChunkRepository(mock(JdbcTemplate::class.java)) {
        override fun findFirstChunkBySource(sourceType: KnowledgeSourceType, sourceId: UUID): KnowledgeChunk? = null
        override fun findRelatedNotes(
            queryEmbedding: FloatArray, excludeSourceId: UUID?, currentUser: AppUser?, limit: Int
        ): List<KnowledgeChunk> = emptyList()
    }

    private val user = AppUser().apply { id = UUID.randomUUID() }
    private val id = UUID.randomUUID()
    private val tx = TrackingTx()
    private val model = CountingModel(tx)

    private fun service(props: GoogleAiProperties, budget: EmbeddingBudget = EmbeddingBudget(props, Clock.systemUTC())): KnowledgeRetrievalServiceImpl {
        val materials = mock(MaterialRepository::class.java)
        `when`(materials.findById(id)).thenReturn(Optional.of(Material().apply { this.id = id; name = "n" }))
        return KnowledgeRetrievalServiceImpl(
            NoChunks(), materials, mock(DanceFigureRepository::class.java), mock(MaterialService::class.java),
            RichTextServiceImpl(), model, budget, AssistantFeature(props), TransactionTemplate(tx)
        )
    }

    private val keyed = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 100, embeddingInteractiveMaxWaitMs = 5000)

    @Test
    fun `related notes draw on the assistant share and do not wait when it is spent`() {
        val props = GoogleAiProperties(
            apiKey = "key", embeddingRequestsPerMinute = 100, embeddingInteractiveSharePercent = 5,
            embeddingInteractivePerUserPerMinute = 100, embeddingInteractiveMaxWaitMs = 5000
        )
        val budget = EmbeddingBudget(props, Clock.systemUTC())
        repeat(5) { assertTrue(budget.tryAcquireInteractive(user.id, 0) { }) }

        val started = System.nanoTime()
        val result = service(props, budget).findRelatedNotesForMaterial(id, user)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(emptyList<Material>(), result)
        assertEquals(0, model.calls, "no slot, no provider call")
        assertTrue(elapsedMs < 1000, "a page view must not wait for a slot, took $elapsedMs ms")
    }

    @Test
    fun `the provider is called outside any transaction`() {
        service(keyed).findRelatedNotesForMaterial(id, user)
        assertEquals(1, model.calls)
        assertFalse(model.embeddedInsideTransaction, "a slow embedding call must not hold a database connection")
    }

    @Test
    fun `related-notes page views only ever open read-only transactions`() {
        service(keyed).findRelatedNotesForMaterial(id, user)
        assertTrue(tx.readOnlyFlags.isNotEmpty())
        assertTrue(tx.readOnlyFlags.all { it }, "flags were ${tx.readOnlyFlags}")
    }

    @Test
    fun `with no API key related notes spend no budget and never embed`() {
        val noKey = GoogleAiProperties(apiKey = "", embeddingRequestsPerMinute = 100, embeddingInteractivePerUserPerMinute = 100, embeddingInteractiveMaxWaitMs = 5000)
        val budget = EmbeddingBudget(noKey, Clock.systemUTC())
        assertEquals(emptyList<Material>(), service(noKey, budget).findRelatedNotesForMaterial(id, user))
        assertEquals(0, model.calls)
        // Nothing was drawn: the whole assistant share is still there.
        val cap = 100 * noKey.embeddingInteractiveSharePercent / 100
        repeat(cap) { assertTrue(budget.tryAcquireInteractive(user.id, 0) { }) }
    }

    @Test
    fun `an anonymous visitor gets no related-note embedding`() {
        assertEquals(emptyList<Material>(), service(keyed).findRelatedNotesForMaterial(id, null))
        assertEquals(0, model.calls)
    }
}
