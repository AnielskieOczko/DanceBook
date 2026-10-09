package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Choreography
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "google.calendar.calendar-id=integration-test-calendar",
    "google.ai.api-key=test-key",
    "google.ai.embedding-requests-per-minute=60000"
])
@Import(RagIntegrationTest.RagTestConfig::class)
class RagIntegrationTest {

    @TestConfiguration
    class RagTestConfig {
        class TestEmbeddingModel : EmbeddingModel {
            var beforeEmbedHook: ((String) -> Unit)? = null

            override fun call(request: EmbeddingRequest): EmbeddingResponse =
                EmbeddingResponse(request.instructions.mapIndexed { idx, text ->
                    Embedding(embed(text), idx)
                })

            override fun embed(document: Document): FloatArray = embed(document.text ?: "")

            override fun embed(text: String): FloatArray {
                beforeEmbedHook?.invoke(text)
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
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var testEmbeddingModel: EmbeddingModel
    @Autowired private lateinit var knowledgeIndexEventListener: KnowledgeIndexEventListener
    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var waltz: DanceType

    private fun newUser(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"
        displayName = name
        password = "x"
        role = Role.USER
    })

    private fun newAdmin(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"
        displayName = name
        password = "x"
        role = Role.ADMIN
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
        `when`(appUserService.findById(any(UUID::class.java) ?: UUID.randomUUID())).thenAnswer { inv ->
            val id = inv.getArgument<UUID>(0)
            appUserRepository.findById(id).orElse(null)
        }
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
    fun `indexed figure text carries the medal level only when set`() {
        val medal = danceFigureRepository.save(DanceFigure().apply {
            name = "Medal Figure"
            danceType = waltz
            danceClass = DanceClass.D
            medalLevel = MedalLevel.GOLD
        })
        val plain = danceFigureRepository.save(DanceFigure().apply {
            name = "Plain Figure"
            danceType = waltz
            danceClass = DanceClass.D
        })

        knowledgeIndexService.indexFigure(medal.id!!)
        knowledgeIndexService.indexFigure(plain.id!!)

        val medalText = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.FIGURE, medal.id!!)!!.content
        val plainText = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.FIGURE, plain.id!!)!!.content
        assertTrue(medalText.contains("Medal: Gold"), medalText)
        assertFalse(plainText.contains("Medal"), plainText)
    }

    @Test
    fun `indexed choreography text carries the medal level only when set`() {
        val medal = choreographyRepository.save(Choreography().apply {
            name = "Medal Choreo"
            danceType = waltz
            owner = userA
            medalLevel = MedalLevel.SILVER
        })
        val plain = choreographyRepository.save(Choreography().apply {
            name = "Plain Choreo"
            danceType = waltz
            owner = userA
        })

        knowledgeIndexService.indexChoreography(medal.id!!)
        knowledgeIndexService.indexChoreography(plain.id!!)

        val medalText = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.CHOREOGRAPHY, medal.id!!)!!.content
        val plainText = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.CHOREOGRAPHY, plain.id!!)!!.content
        assertTrue(medalText.contains("Medal: Silver"), medalText)
        assertFalse(plainText.contains("Medal"), plainText)
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

        for (i in 1..40) {
            val c = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.FIGURE, figure.id!!)
            if (c?.embedding != null) break
            Thread.sleep(25)
        }

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
        for (i in 1..50) {
            val chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
            if (chunks.isNotEmpty() && chunks.first().embedding != null) break
            Thread.sleep(50)
        }

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
        assertEquals("gemini-embedding-001", modelName)
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

        for (i in 1..40) {
            val c = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.NOTE, note2.id!!)
            if (c?.embedding != null) break
            Thread.sleep(25)
        }

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

        for (i in 1..40) {
            val c = knowledgeChunkRepository.findFirstChunkBySource(KnowledgeSourceType.NOTE, note.id!!)
            if (c?.embedding != null) break
            Thread.sleep(25)
        }

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
            assertTrue(chunksBefore.isNotEmpty() && chunksBefore.first().embedding == null,
                "Chunk should be stored first with null vector while embedding is blocked")

            unblockLatch.countDown()

            var chunksAfter = emptyList<KnowledgeChunk>()
            for (i in 1..40) {
                chunksAfter = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                if (chunksAfter.isNotEmpty() && chunksAfter.first().embedding != null) break
                Thread.sleep(50)
            }
            assertEquals(1, chunksAfter.size, "Chunk must appear once embedding completes")
            assertTrue(chunksAfter.first().embedding != null)
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
                if (chunks.isNotEmpty() && chunks.first().embedding != null) break
                Thread.sleep(50)
            }
            assertEquals(1, chunks.size, "Chunk should be indexed after automatic retry by scheduler")
            assertTrue(chunks.first().embedding != null)
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
        materialService.update(created.id!!, updateReq)

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

    @Test
    fun `rebuild over several hundred chunks returns promptly and ends with every chunk embedded without quota error`() {
        val timestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val quotaViolated = java.util.concurrent.atomic.AtomicBoolean(false)
        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel

        val notes = (1..200).map { i ->
            Material().apply {
                owner = userA
                name = "Fast Rebuild Note $i"
                description = "technique and practice content $i"
                visibility = Visibility.PUBLIC
                danceType = waltz
            }
        }
        materialRepository.saveAll(notes)

        model.beforeEmbedHook = {
            val now = System.currentTimeMillis()
            timestamps.add(now)
            while (timestamps.peek() != null && now - timestamps.peek() > 1000) {
                timestamps.poll()
            }
            if (timestamps.size > 500) {
                quotaViolated.set(true)
                throw RuntimeException("429 RESOURCE_EXHAUSTED: quota exceeded")
            }
        }

        try {
            val start = System.currentTimeMillis()
            val report = knowledgeIndexService.rebuildAll()
            val rebuildDuration = System.currentTimeMillis() - start

            assertTrue(rebuildDuration < 3000, "rebuildAll must return promptly, took ${rebuildDuration}ms")
            assertTrue(report.totalChunks >= 200, "Rebuild should report at least 200 chunks")
            assertFalse(quotaViolated.get(), "Quota must not be violated")

            var pending = 1L
            for (i in 1..100) {
                pending = knowledgeChunkRepository.countAwaitingEmbedding()
                if (pending == 0L) break
                Thread.sleep(50)
            }
            assertEquals(0L, pending, "All chunks must end up embedded")
            assertEquals(report.totalChunks.toLong(), knowledgeChunkRepository.countEmbedded(), "Every chunk must have a vector")
        } finally {
            model.beforeEmbedHook = null
        }
    }

    @Test
    fun `a chunk whose embedding call fails once or twice ends up with a vector without any further user action`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Flaky Embedding Note"
            description = "Note testing transient failure retry."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        var failAttemptsLeft = 2
        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = {
            if (failAttemptsLeft > 0) {
                failAttemptsLeft--
                throw RuntimeException("Simulated transient network timeout")
            }
        }

        try {
            knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, note.id!!)

            var chunk: KnowledgeChunk? = null
            for (i in 1..100) {
                val chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                if (chunks.isNotEmpty() && chunks.first().embedding != null) {
                    chunk = chunks.first()
                    break
                }
                Thread.sleep(50)
            }

            assertEquals(0, failAttemptsLeft, "Should have failed twice and retried")
            assertTrue(chunk != null && chunk.embedding != null, "Chunk must end up with a vector without user action")
        } finally {
            model.beforeEmbedHook = null
        }
    }

    @Test
    fun `extractRetryDelayMs parses decimal seconds from verbatim Google quota message`() {
        val service = knowledgeIndexService as KnowledgeIndexServiceImpl
        val ex = RuntimeException("You exceeded your current quota ... Please retry in 27.794566691s..")
        val delay = service.extractRetryDelayMs(ex)
        assertEquals(27795L, delay)
    }

    @Test
    fun `provider 429 slows the worker down and does not lose chunks`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Quota Note"
            description = "Note testing 429 rate limit backoff."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        var threw429 = false
        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = {
            if (!threw429) {
                threw429 = true
                throw RuntimeException("You exceeded your current quota ... Please retry in 27.794566691s..")
            }
        }

        try {
            val start = System.currentTimeMillis()
            knowledgeIndexService.queueIndex(KnowledgeSourceType.NOTE, note.id!!)

            var chunk: KnowledgeChunk? = null
            for (i in 1..800) {
                val chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                if (chunks.isNotEmpty() && chunks.first().embedding != null) {
                    chunk = chunks.first()
                    break
                }
                Thread.sleep(50)
            }

            val elapsed = System.currentTimeMillis() - start
            assertTrue(threw429, "Should have encountered 429")
            assertTrue(elapsed >= 27_000, "Worker should have slowed down / backed off on 429 for ~28s as requested by provider, took ${elapsed}ms")
            assertTrue(chunk != null && chunk.embedding != null, "Chunk must not be lost and must have its vector filled")
        } finally {
            model.beforeEmbedHook = null
        }
    }

    @Test
    fun `head-of-line blocking is prevented when oldest chunks fail and newer chunks are embedded`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Head of Line Note"
            description = "Parent note for chunks"
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        // Create 25 chunks awaiting embedding (embedding = null).
        // The oldest 21 chunks will fail embedding permanently.
        // The newest 4 chunks will succeed.
        val now = java.time.LocalDateTime.now()
        for (i in 0 until 25) {
            val chunk = KnowledgeChunk(
                id = UUID.randomUUID(),
                sourceType = KnowledgeSourceType.NOTE,
                sourceId = note.id!!,
                chunkIndex = i,
                content = if (i < 21) "failing-chunk-$i" else "healthy-chunk-$i",
                embedding = null,
                embeddingModel = "text-embedding-004",
                ownerId = userA.id,
                visibility = Visibility.PUBLIC,
                updatedAt = now.minusMinutes((25 - i).toLong())
            )
            knowledgeChunkRepository.save(chunk)
        }

        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = { text ->
            if (text.contains("failing-chunk")) {
                throw RuntimeException("Permanent error embedding corrupt chunk")
            }
        }

        try {
            knowledgeIndexService.processQueueAsync()

            var embeddedHealthyChunks = 0
            for (attempt in 1..100) {
                val chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
                val healthy = chunks.filter { it.content.contains("healthy-chunk") }
                embeddedHealthyChunks = healthy.count { it.embedding != null }
                if (embeddedHealthyChunks == 4) break
                Thread.sleep(50)
            }

            assertEquals(4, embeddedHealthyChunks, "All 4 healthy chunks should be embedded despite 21 failing chunks ahead of them")

            val chunks = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
            val failing = chunks.filter { it.content.contains("failing-chunk") }
            assertEquals(21, failing.count { it.embedding == null }, "Failing chunks should remain un-embedded in backoff")
        } finally {
            model.beforeEmbedHook = null
        }
    }

    @Test
    fun `after a restart chunks without a vector are embedded`() {
        val note = materialRepository.save(Material().apply {
            owner = userA
            name = "Restart Note"
            description = "Note with chunk inserted without vector."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        val chunk = KnowledgeChunk(
            sourceType = KnowledgeSourceType.NOTE,
            sourceId = note.id!!,
            chunkIndex = 0,
            content = "Note: Restart Note\n\nContent awaiting vector after restart",
            embedding = null,
            embeddingModel = "gemini-embedding-001",
            ownerId = userA.id,
            visibility = Visibility.PUBLIC
        )
        knowledgeChunkRepository.save(chunk)

        assertEquals(1, knowledgeChunkRepository.countAwaitingEmbedding(), "Should have 1 chunk awaiting embedding")

        (knowledgeIndexService as KnowledgeIndexServiceImpl).onApplicationReady()

        var pending = 1L
        for (i in 1..80) {
            pending = knowledgeChunkRepository.countAwaitingEmbedding()
            if (pending == 0L) break
            Thread.sleep(50)
        }

        assertEquals(0L, pending, "All pending chunks must be embedded after startup recovery")
        val saved = knowledgeChunkRepository.findBySource(KnowledgeSourceType.NOTE, note.id!!)
        assertTrue(saved.isNotEmpty() && saved.first().embedding != null, "Vector must be filled in")
    }

    @Test
    fun `admin knowledge rebuild reports honest status with warning when N less than M and updates on status poll`() {
        val admin = newAdmin("AdminUser")
        `when`(appUserService.getCurrentUser()).thenReturn(admin)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(admin)

        materialRepository.save(Material().apply {
            owner = admin
            name = "Admin Truth Note"
            description = "Testing honest rebuild reporting."
            visibility = Visibility.PUBLIC
            danceType = waltz
        })

        val latch = CountDownLatch(1)
        val model = testEmbeddingModel as RagTestConfig.TestEmbeddingModel
        model.beforeEmbedHook = {
            latch.await(3, TimeUnit.SECONDS)
        }

        try {
            val rebuildResult = mockMvc.perform(
                post("/admin/knowledge/rebuild")
                    .with(user(admin.username).roles("ADMIN"))
                    .with(csrf())
            )
                .andExpect(status().isOk)
                .andReturn().response.contentAsString

            assertTrue(rebuildResult.contains("Embedding in Progress"),
                "Must report embedding in progress with honest warning: $rebuildResult")
            assertTrue(rebuildResult.contains("/admin/knowledge/status"),
                "Must include polling endpoint: $rebuildResult")
            assertFalse(rebuildResult.contains("All 1 chunks embedded"),
                "Must never report full success while chunks are waiting: $rebuildResult")

            latch.countDown()

            for (i in 1..80) {
                if (knowledgeChunkRepository.countAwaitingEmbedding() == 0L) break
                Thread.sleep(50)
            }

            val statusResult = mockMvc.perform(
                get("/admin/knowledge/status")
                    .with(user(admin.username).roles("ADMIN"))
                    .with(csrf())
            )
                .andExpect(status().isOk)
                .andReturn().response.contentAsString

            assertTrue(statusResult.contains("Knowledge Index Rebuilt"),
                "Must report complete success: $statusResult")
            assertTrue(statusResult.contains("chunks embedded"),
                "Must state chunk counts: $statusResult")
            assertFalse(statusResult.contains("hx-get=\"/admin/knowledge/status\""),
                "Must stop polling once complete: $statusResult")
        } finally {
            model.beforeEmbedHook = null
            latch.countDown()
        }
    }
}
