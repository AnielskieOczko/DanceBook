package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.repository.CommentRepository
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = [
    "google.calendar.calendar-id=integration-test-calendar",
    "google.ai.api-key=test-key"
])
@Import(RagIntegrationTest.RagTestConfig::class)
class RagIntegrationTest {

    @TestConfiguration
    class RagTestConfig {
        class TestEmbeddingModel : EmbeddingModel {
            var beforeEmbedHook: (() -> Unit)? = null

            override fun call(request: EmbeddingRequest): EmbeddingResponse =
                EmbeddingResponse(request.instructions.mapIndexed { idx, text ->
                    Embedding(embed(text), idx)
                })

            override fun embed(document: Document): FloatArray = embed(document.text ?: "")

            override fun embed(text: String): FloatArray {
                beforeEmbedHook?.invoke()
                val vec = FloatArray(768)
                val lower = text.lowercase()
                if ("sway" in lower) vec[0] = 0.8f
                if ("waltz" in lower) vec[1] = 0.8f
                if ("turn" in lower) vec[2] = 0.8f
                if ("spin" in lower) vec[3] = 0.8f
                if ("bounce" in lower) vec[4] = 0.8f
                var sumSq = 0.0f
                for (v in vec) sumSq += v * v
                if (sumSq > 0) {
                    val norm = Math.sqrt(sumSq.toDouble()).toFloat()
                    for (i in vec.indices) vec[i] /= norm
                } else {
                    vec[767] = 1.0f
                }
                return vec
            }

            override fun dimensions(): Int = 768
        }

        @Bean
        @Primary
        fun testEmbeddingModel(): TestEmbeddingModel = TestEmbeddingModel()
    }

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var knowledgeChunkRepository: KnowledgeChunkRepository
    @Autowired private lateinit var knowledgeIndexService: KnowledgeIndexService
    @Autowired private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService
    @Autowired private lateinit var assistantReadTools: AssistantReadTools
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var materialService: MaterialService
    @Autowired private lateinit var commentRepository: CommentRepository
    @Autowired private lateinit var commentService: CommentService
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var testEmbeddingModel: EmbeddingModel
    @Autowired private lateinit var knowledgeIndexEventListener: KnowledgeIndexEventListener

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var waltz: DanceType

    private fun newUser(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"
        displayName = name
        password = "x"
        role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        knowledgeChunkRepository.deleteAll()
        materialRepository.deleteAll()
        danceFigureRepository.deleteAll()

        userA = newUser("Alice")
        userB = newUser("Bob")

        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        waltz = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; category = cat })

        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)
    }

    @Test
    fun `user B never retrieves user A private note, including right after A makes it private`() {
        val privateNote = materialRepository.save(Material().apply {
            owner = userA
            name = "Secret Sway Notes"
            description = "Alice private thoughts on sway."
            visibility = Visibility.PRIVATE
            danceType = waltz
        })
        val publicNote = materialRepository.save(Material().apply {
            owner = userA
            name = "Public Sway Guide"
            description = "Alice shared guide on sway."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        knowledgeIndexService.indexMaterial(privateNote.id!!)
        knowledgeIndexService.indexMaterial(publicNote.id!!)

        // User B searches
        val resultsForB = knowledgeChunkRepository.hybridSearch(
            query = "sway",
            queryEmbedding = null,
            currentUser = userB,
            limit = 10
        )
        val resultIdsForB = resultsForB.map { it.chunk.sourceId }
        assertFalse(resultIdsForB.contains(privateNote.id), "User B must not see Alice's private note")
        assertTrue(resultIdsForB.contains(publicNote.id), "User B can see Alice's public note")

        // Alice makes the public note private via service
        val updateReq = MaterialRequest(
            name = publicNote.name,
            description = publicNote.description,
            danceTypeId = waltz.id,
            isPublic = false,
            version = publicNote.version
        )
        materialService.update(publicNote.id!!, updateReq)

        // Immediately, user B can no longer retrieve it
        val resultsAfterChange = knowledgeChunkRepository.hybridSearch(
            query = "sway",
            queryEmbedding = null,
            currentUser = userB,
            limit = 10
        )
        val resultIdsAfterChange = resultsAfterChange.map { it.chunk.sourceId }
        assertFalse(resultIdsAfterChange.contains(publicNote.id), "Public note turned private must immediately be hidden from User B")
    }

    @Test
    fun `exact figure-name match ranks above vaguely similar note`() {
        // A note mentioning spin and turn and waltz
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Waltz Practice Session"
            description = "We worked on general rotation and sway mechanics in waltz without the full figure name."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        // A figure with the exact name Natural Spin Turn
        val figure = danceFigureRepository.save(DanceFigure().apply {
            name = "Natural Spin Turn"
            danceType = waltz
            danceClass = DanceClass.D
            alternativeTiming = "1 2 3 1 2 3"
        })

        knowledgeIndexService.indexMaterial(note.id!!)
        knowledgeIndexService.indexFigure(figure.id!!)

        val emb = testEmbeddingModel.embed("Natural Spin Turn")
        val results = knowledgeChunkRepository.hybridSearch(
            query = "Natural Spin Turn",
            queryEmbedding = emb,
            currentUser = userA,
            limit = 5
        )
        assertTrue(results.isNotEmpty(), "Should return results")
        assertEquals(figure.id, results.first().chunk.sourceId, "Exact figure name match must rank first")
        assertEquals(KnowledgeSourceType.FIGURE, results.first().chunk.sourceType)
    }

    @Test
    fun `stale index detection on model change and rebuild replacement`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Quickstep Chassé"
            description = "Chassé technique details."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        knowledgeIndexService.indexMaterial(note.id!!)

        assertFalse(knowledgeIndexService.isStale(), "Index should initially be fresh")

        // Simulate an older embedding model in DB
        jdbcTemplate.update("UPDATE knowledge_chunk SET embedding_model = 'old-model-xyz'")

        assertTrue(knowledgeIndexService.isStale(), "Index should be marked stale when model differs")

        // Run rebuild
        val report = knowledgeIndexService.rebuildAll()
        assertTrue(report.totalChunks > 0, "Rebuild should index chunks")
        assertFalse(knowledgeIndexService.isStale(), "Index should not be stale after rebuild")

        val modelName = jdbcTemplate.queryForObject(
            "SELECT DISTINCT embedding_model FROM knowledge_chunk",
            String::class.java
        )
        assertEquals("text-embedding-004", modelName)
    }

    @Test
    fun `related notes appear on note and figure pages and never include current item`() {
        val note1 = materialRepository.save(Material().apply {
            owner = userA
            name = "Waltz Sway Part 1"
            description = "Detailed sway and rotation practice in waltz."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        val note2 = materialRepository.save(Material().apply {
            owner = userA
            name = "Waltz Sway Part 2"
            description = "Continuation of sway technique and head position in waltz."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        val figure = danceFigureRepository.save(DanceFigure().apply {
            name = "Reverse Turn"
            danceType = waltz
            danceClass = DanceClass.D
        })

        knowledgeIndexService.indexMaterial(note1.id!!)
        knowledgeIndexService.indexMaterial(note2.id!!)
        knowledgeIndexService.indexFigure(figure.id!!)

        // Related notes for Note 1
        val relatedToNote1 = knowledgeRetrievalService.findRelatedNotesForMaterial(note1.id!!, currentUser = userA, limit = 3)
        assertFalse(relatedToNote1.any { it.id == note1.id }, "Related notes must not include the item itself")
        assertTrue(relatedToNote1.any { it.id == note2.id }, "Note 2 should be related to Note 1")

        // Related notes for Figure
        val relatedToFigure = knowledgeRetrievalService.findRelatedNotesForFigure(figure.id!!, currentUser = userA, limit = 3)
        assertFalse(relatedToFigure.any { it.id == figure.id }, "Related notes must not include the figure")
    }

    @Test
    fun `search_knowledge tool returns passages with url citations`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Heel Turn Secrets"
            description = "Keep pressure on the ball of the standing foot before closing."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        knowledgeIndexService.indexMaterial(note.id!!)

        val result = assistantReadTools.searchKnowledge("standing foot heel turn")
        assertTrue(result.items.isNotEmpty(), "Should find passage")
        val passage = result.items.first()
        assertEquals("/materials/${note.id}", passage.url)
        assertEquals("Heel Turn Secrets", passage.title)
        assertTrue(passage.snippet!!.contains("pressure on the ball"))

        // When nothing matches
        val emptyResult = assistantReadTools.searchKnowledge("nonexistent astrophysics telescope")
        assertTrue(emptyResult.items.isEmpty(), "Unrelated query should yield no passages")
        assertEquals("Nothing found in your notes.", emptyResult.message)
    }

    @Test
    fun `saves do not wait on embedding call`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Async Latch Note"
            description = "Alice note testing async embedding latch."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        val embedStartedLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = {
            embedStartedLatch.countDown()
            unblockLatch.await(5, TimeUnit.SECONDS)
        }

        try {
            val start = System.currentTimeMillis()
            knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, note.id!!)
            val elapsed = System.currentTimeMillis() - start

            assertTrue(elapsed < 1000, "queueIndex must return immediately without waiting on embedding (took ${elapsed}ms)")
            assertTrue(embedStartedLatch.await(5, TimeUnit.SECONDS), "Worker thread should start embedding in background")

            val chunksBefore = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
            assertTrue(chunksBefore.isEmpty(), "Chunk should not be committed yet while embedding is blocked")

            unblockLatch.countDown()

            var chunksAfter = emptyList<KnowledgeChunk>()
            for (i in 1..40) {
                chunksAfter = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                if (chunksAfter.isNotEmpty()) break
                Thread.sleep(50)
            }
            assertEquals(1, chunksAfter.size, "Chunk must appear once embedding completes")
        } finally {
            model.beforeEmbedHook = null
            unblockLatch.countDown()
        }
    }

    @Test
    fun `retried items with future retry time are re-triggered by the scheduler`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Retry Test Note"
            description = "Note testing retry backoff scheduler."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        var failOnce = true
        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = {
            if (failOnce) {
                failOnce = false
                throw RuntimeException("Simulated transient embedding API failure")
            }
        }

        try {
            knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, note.id!!)

            var chunks = emptyList<KnowledgeChunk>()
            for (i in 1..80) {
                chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                if (chunks.isNotEmpty()) break
                Thread.sleep(50)
            }
            assertEquals(1, chunks.size, "Chunk should be indexed after automatic retry by scheduler")
            assertFalse(failOnce, "Embedding should have failed once and then succeeded")
        } finally {
            model.beforeEmbedHook = null
        }
    }

    @Test
    fun `real materialService delete removes note chunks and all cascaded comment chunks`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Note with comments"
            description = "Note description about sway technique."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })
        knowledgeIndexService.indexMaterial(note.id!!)

        val comment = commentService.addComment(note.id!!, "Comment about ball of foot and sway", userA)
        var commentChunks = emptyList<KnowledgeChunk>()
        for (i in 1..40) {
            commentChunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE_COMMENT, comment.id!!)
            if (commentChunks.isNotEmpty()) break
            Thread.sleep(50)
        }
        assertEquals(1, commentChunks.size, "Comment should be indexed")

        val searchBefore = knowledgeChunkRepository.hybridSearch(
            query = "sway",
            queryEmbedding = null,
            currentUser = userA,
            limit = 10
        )
        val foundIdsBefore = searchBefore.map { it.chunk.sourceId }
        assertTrue(foundIdsBefore.contains(note.id), "Note should be in search results")
        assertTrue(foundIdsBefore.contains(comment.id), "Comment should be in search results")

        // Real delete through materialService
        materialService.delete(note.id!!)

        val noteChunksAfter = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
        val commentChunksAfter = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE_COMMENT, comment.id!!)
        assertTrue(noteChunksAfter.isEmpty(), "Note chunks must be deleted")
        assertTrue(commentChunksAfter.isEmpty(), "Cascaded comment chunks must be deleted")

        val searchAfter = knowledgeChunkRepository.hybridSearch(
            query = "sway",
            queryEmbedding = null,
            currentUser = userA,
            limit = 10
        )
        val foundIdsAfter = searchAfter.map { it.chunk.sourceId }
        assertFalse(foundIdsAfter.contains(note.id), "Note must no longer appear in search")
        assertFalse(foundIdsAfter.contains(comment.id), "Comment must no longer appear in search")
    }

    @Test
    fun `lifecycle through service creates, updates and deletes chunks via domain events`() {
        val createReq = MaterialRequest(
            name = "Lifecycle Note",
            description = "Initial content about waltz technique.",
            danceTypeId = waltz.id,
            isPublic = true,
            version = 0
        )
        val created = materialService.create(createReq)

        var chunks = emptyList<KnowledgeChunk>()
        for (i in 1..40) {
            chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, created.id!!)
            if (chunks.isNotEmpty()) break
            Thread.sleep(50)
        }
        assertEquals(1, chunks.size, "Chunk should be created via service create event")
        assertTrue(chunks.first().content.contains("Initial content"))

        val updateReq = MaterialRequest(
            name = "Lifecycle Note",
            description = "Updated content with spin and sway.",
            danceTypeId = waltz.id,
            isPublic = true,
            version = created.version
        )
        val updated = materialService.update(created.id!!, updateReq)

        var updatedChunks = emptyList<KnowledgeChunk>()
        for (i in 1..40) {
            updatedChunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, created.id!!)
            if (updatedChunks.isNotEmpty() && updatedChunks.first().content.contains("Updated content with spin")) break
            Thread.sleep(50)
        }
        assertEquals(1, updatedChunks.size, "Chunk should be updated via service update event")
        assertTrue(updatedChunks.first().content.contains("Updated content with spin"))

        materialService.delete(created.id!!)

        val afterDelete = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, created.id!!)
        assertTrue(afterDelete.isEmpty(), "Chunks must be deleted immediately upon service delete event")
    }
}
