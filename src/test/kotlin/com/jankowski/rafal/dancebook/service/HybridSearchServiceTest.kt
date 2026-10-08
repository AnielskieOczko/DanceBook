package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.model.KnowledgeSearchResult
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.UUID

class HybridSearchServiceTest {

    private class FakeEmbeddingModel : EmbeddingModel {
        var calls = 0
        var failure: RuntimeException? = null
        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(3), i) })
        override fun embed(document: org.springframework.ai.document.Document): FloatArray = embed(document.text ?: "")
        override fun embed(text: String): FloatArray {
            calls++
            failure?.let { throw it }
            return floatArrayOf(1f, 0f, 0f)
        }
        override fun dimensions(): Int = 3
    }

    private class FakeChunkRepository : KnowledgeChunkRepository(mock(JdbcTemplate::class.java)) {
        var results: List<KnowledgeSearchResult> = emptyList()
        var failure: RuntimeException? = null
        var lastQuery: String? = "unset"
        var lastUser: AppUser? = null
        var lastMaxDistance: Double? = null
        var lastTopK: Int? = null
        override fun hybridSearch(
            query: String?, queryEmbedding: FloatArray?, sourceTypes: List<KnowledgeSourceType>?,
            currentUser: AppUser?, limit: Int, topK: Int, k: Int, maxDistance: Double
        ): List<KnowledgeSearchResult> {
            lastMaxDistance = maxDistance
            lastTopK = topK
            lastQuery = query
            lastUser = currentUser
            failure?.let { throw it }
            return results
        }
    }

    private val user = AppUser().apply { id = UUID.randomUUID() }
    private lateinit var materialService: MaterialService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var appUserService: AppUserService
    private lateinit var commentRepository: CommentRepository
    private lateinit var chunks: FakeChunkRepository
    private lateinit var embeddings: FakeEmbeddingModel
    private lateinit var props: GoogleAiProperties
    private lateinit var service: HybridSearchServiceImpl

    @BeforeEach
    fun setUp() {
        materialService = mock(MaterialService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        appUserService = mock(AppUserService::class.java)
        commentRepository = mock(CommentRepository::class.java)
        chunks = FakeChunkRepository()
        embeddings = FakeEmbeddingModel()
        props = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 100, semanticSearchMaxDistance = 0.5)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)
        build()
    }

    private fun build() {
        service = HybridSearchServiceImpl(
            materialService, danceFigureService, appUserService, commentRepository,
            chunks, embeddings, EmbeddingBudget(props, Clock.systemUTC()), props, AssistantFeature(props)
        )
    }

    private fun note(name: String) = Material().apply { id = UUID.randomUUID(); this.name = name }
    private fun figure(name: String) = DanceFigure().apply { id = UUID.randomUUID(); this.name = name }

    private fun chunk(type: KnowledgeSourceType, sourceId: UUID, distance: Double = 0.1) = KnowledgeSearchResult(
        KnowledgeChunk(sourceType = type, sourceId = sourceId, content = "x", embeddingModel = "m", visibility = Visibility.PUBLIC),
        0.1, distance
    )

    private fun keywordNotes(vararg notes: Material) {
        `when`(materialService.searchNotes("sway", null, null, HybridSearchService.DEFAULT_LIMIT)).thenReturn(notes.toList())
    }

    private fun keywordFigures(vararg figures: DanceFigure) {
        `when`(danceFigureService.findAll(null, null, null, "sway", null, null, null)).thenReturn(figures.toList())
    }

    private fun visibleNote(n: Material) { `when`(materialService.findById(n.id!!)).thenReturn(n) }
    private fun visibleFigure(f: DanceFigure) { `when`(danceFigureService.findById(f.id!!)).thenReturn(f) }

    @Test
    fun `keyword hits come back in their existing order marked KEYWORD when the semantic part is empty`() {
        val n1 = note("Sway one"); val n2 = note("Sway two"); val f = figure("Sway figure")
        keywordNotes(n1, n2); keywordFigures(f)

        val hits = service.search("sway")

        assertEquals(listOf(n1.id, f.id, n2.id), hits.map { it.id })
        assertTrue(hits.all { it.match == SearchMatch.KEYWORD })
        assertEquals(listOf(SearchHitType.NOTE, SearchHitType.FIGURE, SearchHitType.NOTE), hits.map { it.type })
        assertEquals("Sway one", hits[0].note?.name)
        assertEquals("Sway figure", hits[1].figure?.name)
    }

    @Test
    fun `semantic-only items are added after keyword hits and marked SEMANTIC`() {
        val kw = note("Sway notes"); val sem = note("Keep the neck long"); val semFig = figure("Natural Spin Turn")
        keywordNotes(kw); keywordFigures()
        visibleNote(sem); visibleFigure(semFig)
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, sem.id!!), chunk(KnowledgeSourceType.FIGURE, semFig.id!!))

        val hits = service.search("sway")

        assertEquals(listOf(kw.id, sem.id, semFig.id), hits.map { it.id })
        assertEquals(listOf(SearchMatch.KEYWORD, SearchMatch.SEMANTIC, SearchMatch.SEMANTIC), hits.map { it.match })
        assertNull(chunks.lastQuery, "the semantic leg must be vector-only, keyword matching is done by the existing services")
        assertEquals(user, chunks.lastUser)
    }

    @Test
    fun `an item found both ways appears once, marked BOTH, ahead of keyword-only hits`() {
        val a = note("A"); val b = note("B")
        keywordNotes(a, b); keywordFigures()
        visibleNote(b)
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, b.id!!))

        val hits = service.search("sway")

        assertEquals(listOf(b.id, a.id), hits.map { it.id })
        assertEquals(listOf(SearchMatch.BOTH, SearchMatch.KEYWORD), hits.map { it.match })
    }

    @Test
    fun `several chunks of one note and a comment chunk collapse into one hit`() {
        val sem = note("Sem")
        val comment = Comment().apply { id = UUID.randomUUID(); material = sem }
        keywordNotes(); keywordFigures(); visibleNote(sem)
        `when`(commentRepository.findAllById(listOf(comment.id!!))).thenReturn(listOf(comment))
        chunks.results = listOf(
            chunk(KnowledgeSourceType.NOTE, sem.id!!),
            chunk(KnowledgeSourceType.NOTE, sem.id!!),
            chunk(KnowledgeSourceType.NOTE_COMMENT, comment.id!!)
        )

        val hits = service.search("sway")

        assertEquals(listOf(sem.id), hits.map { it.id })
    }

    @Test
    fun `a semantic hit the user cannot open is dropped`() {
        val hidden = note("Someone else's private note")
        keywordNotes(); keywordFigures()
        `when`(materialService.findById(hidden.id!!)).thenThrow(EntityNotFoundException("nope"))
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, hidden.id!!))

        assertTrue(service.search("sway").isEmpty())
    }

    @Test
    fun `at most the semantic cap of extra items is added`() {
        keywordNotes(); keywordFigures()
        val extras = (1..HybridSearchService.SEMANTIC_LIMIT + 3).map { note("S$it") }
        extras.forEach { visibleNote(it) }
        chunks.results = extras.map { chunk(KnowledgeSourceType.NOTE, it.id!!) }

        val hits = service.search("sway")

        assertEquals(extras.take(HybridSearchService.SEMANTIC_LIMIT).map { it.id }, hits.map { it.id })
    }

    @Test
    fun `an embedding failure falls back to keyword-only without an error`() {
        val kw = note("Sway notes")
        keywordNotes(kw); keywordFigures()
        embeddings.failure = RuntimeException("429 RESOURCE_EXHAUSTED")

        val hits = service.search("sway")

        assertEquals(listOf(kw.id), hits.map { it.id })
        assertEquals(SearchMatch.KEYWORD, hits[0].match)
    }

    @Test
    fun `a failing vector query falls back to keyword-only without an error`() {
        val kw = note("Sway notes")
        keywordNotes(kw); keywordFigures()
        chunks.failure = RuntimeException("pgvector missing")

        assertEquals(listOf(kw.id), service.search("sway").map { it.id })
    }

    @Test
    fun `the embedding rate limit is respected by skipping the semantic leg`() {
        props = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 1)
        build()
        val kw = note("Sway notes"); val sem = note("Semantic")
        keywordNotes(kw); keywordFigures(); visibleNote(sem)
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, sem.id!!))

        assertEquals(2, service.search("sway").size)
        val second = service.search("sway")

        assertEquals(1, embeddings.calls, "the second search is over the limit and must not call the provider")
        assertEquals(listOf(kw.id), second.map { it.id })
    }

    @Test
    fun `without an API key the semantic leg is skipped and keyword results stand alone`() {
        props = GoogleAiProperties(apiKey = "", embeddingRequestsPerMinute = 100)
        build()
        val kw = note("Sway notes"); val sem = note("Zero vector neighbour")
        keywordNotes(kw); keywordFigures(); visibleNote(sem)
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, sem.id!!))

        val hits = service.search("sway")

        assertEquals(listOf(kw.id), hits.map { it.id })
        assertEquals(0, embeddings.calls)
        assertEquals("unset", chunks.lastQuery, "the index must not be queried either")
    }

    @Test
    fun `semantic candidates beyond the configured distance are dropped, those within are kept`() {
        keywordNotes(); keywordFigures()
        val near = note("Near"); val far = note("Far"); val edge = note("Edge")
        listOf(near, far, edge).forEach { visibleNote(it) }
        chunks.results = listOf(
            chunk(KnowledgeSourceType.NOTE, near.id!!, 0.49),
            chunk(KnowledgeSourceType.NOTE, far.id!!, 0.51),
            chunk(KnowledgeSourceType.NOTE, edge.id!!, 0.5)
        )

        val hits = service.search("sway")

        assertEquals(listOf(near.id), hits.map { it.id })
        assertEquals(0.5, chunks.lastMaxDistance) // the threshold is also pushed into the vector query
    }

    @Test
    fun `a keyword hit with only a distant semantic match stays KEYWORD`() {
        val a = note("A")
        keywordNotes(a); keywordFigures(); visibleNote(a)
        chunks.results = listOf(chunk(KnowledgeSourceType.NOTE, a.id!!, 0.9))

        assertEquals(SearchMatch.KEYWORD, service.search("sway").single().match)
    }

    @Test
    fun `the whole candidate window is requested from the vector query`() {
        keywordNotes(); keywordFigures()
        service.search("sway")
        assertEquals(30, chunks.lastTopK)
    }

    @Test
    fun `comment chunks are resolved to notes in one batched lookup`() {
        keywordNotes(); keywordFigures()
        val n1 = note("N1"); val n2 = note("N2")
        visibleNote(n1); visibleNote(n2)
        val c1 = Comment().apply { id = UUID.randomUUID(); material = n1 }
        val c2 = Comment().apply { id = UUID.randomUUID(); material = n2 }
        val c3 = Comment().apply { id = UUID.randomUUID(); material = n1 }
        `when`(commentRepository.findAllById(listOf(c1.id!!, c2.id!!, c3.id!!))).thenReturn(listOf(c1, c2, c3))
        chunks.results = listOf(c1, c2, c3).map { chunk(KnowledgeSourceType.NOTE_COMMENT, it.id!!) }

        val hits = service.search("sway")

        assertEquals(listOf(n1.id, n2.id), hits.map { it.id })
        org.mockito.Mockito.verify(commentRepository, org.mockito.Mockito.times(1)).findAllById(org.mockito.ArgumentMatchers.anyIterable())
        org.mockito.Mockito.verify(commentRepository, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `a search is not run for a user over their share of the shared budget`() {
        props = GoogleAiProperties(apiKey = "key", embeddingRequestsPerMinute = 100, embeddingSearchPerUserPerMinute = 1)
        build()
        keywordNotes(); keywordFigures()
        service.search("sway")
        service.search("sway")
        assertEquals(1, embeddings.calls)
    }

    @Test
    fun `a blank query returns nothing and does not embed`() {
        assertTrue(service.search("   ").isEmpty())
        assertEquals(0, embeddings.calls)
    }
}
