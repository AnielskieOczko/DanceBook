package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = [
    "google.calendar.calendar-id=integration-test-calendar",
    "google.ai.api-key=test-key",
    "google.ai.embedding-requests-per-minute=60000"
])
@Import(RagIntegrationTest.RagTestConfig::class)
class HybridSearchIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var hybridSearchService: HybridSearchService
    @Autowired private lateinit var knowledgeIndexService: KnowledgeIndexService
    @Autowired private lateinit var knowledgeChunkRepository: KnowledgeChunkRepository
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @Autowired private lateinit var testEmbeddingModel: RagIntegrationTest.RagTestConfig.TestEmbeddingModel

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

    @BeforeEach
    fun setUp() {
        knowledgeChunkRepository.deleteAll()
        materialRepository.deleteAll()
        danceFigureRepository.deleteAll()
        userA = newUser("Alice")
        userB = newUser("Bob")
        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        waltz = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; category = cat })
    }

    private fun note(owner: AppUser, name: String, text: String, visibility: Visibility) =
        materialRepository.save(Material().apply {
            this.owner = owner; this.name = name; description = text
            this.visibility = visibility; danceType = waltz
        }).also { knowledgeIndexService.indexMaterial(it.id!!) }

    @Test
    fun `second user never sees another user's private note, keyword or semantic`() {
        // "sway" is in the first note's text (keyword match); the second note only embeds near it
        // (the test model maps "sway" to the same vector axis), so it can only be a semantic hit.
        val privateNote = note(userA, "Secret", "sway private thoughts", Visibility.PRIVATE)
        val publicNote = note(userA, "Shared", "sway shared guide", Visibility.PUBLIC)

        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userB)
        val asB = hybridSearchService.search("sway").map { it.id }
        assertFalse(privateNote.id in asB, "User B must not see Alice's private note")
        assertTrue(publicNote.id in asB)

        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)
        val asA = hybridSearchService.search("sway").map { it.id }
        assertTrue(privateNote.id in asA)
        assertTrue(publicNote.id in asA)
        assertEquals(asA.distinct(), asA, "no duplicates")
    }

    @Test
    fun `a related note without the query words is added as SEMANTIC and a matching one is BOTH`() {
        // The test embedding model puts "sway" and "waltz" on their own axes. The query has both
        // words, so a note that has only "sway" shares no full keyword match but is near in meaning.
        val keywordNote = note(userA, "Sway basics", "sway and waltz basics", Visibility.PUBLIC)
        val semanticNote = note(userA, "Body motion", "all about sway", Visibility.PUBLIC)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)

        val hits = hybridSearchService.search("sway waltz")

        val byId = hits.associateBy { it.id }
        assertEquals(SearchMatch.BOTH, byId[keywordNote.id]?.match)
        assertEquals(SearchMatch.SEMANTIC, byId[semanticNote.id]?.match)
        assertEquals(keywordNote.id, hits.first().id)
    }

    @Test
    fun `the query is embedded outside any transaction so a slow provider cannot hold a connection`() {
        note(userA, "Sway basics", "sway", Visibility.PUBLIC)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)
        var inTransaction: Boolean? = null
        testEmbeddingModel.beforeEmbedHook = {
            inTransaction = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        }
        try {
            hybridSearchService.search("sway")
        } finally {
            testEmbeddingModel.beforeEmbedHook = null
        }
        assertEquals(false, inTransaction, "the embed call must run with no transaction open")
    }

    @Test
    fun `the dance type filter applies before the candidate limit`() {
        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Latin ${UUID.randomUUID()}" })
        val tango = danceTypeRepository.save(DanceType().apply { name = "Tango ${UUID.randomUUID()}"; category = cat })
        val nearer = note(userA, "Waltz sway", "sway", Visibility.PUBLIC) // waltz: nearest to "sway"
        val farther = materialRepository.save(Material().apply {
            owner = userA; name = "Tango motion"; description = "sway turn spin"
            visibility = Visibility.PUBLIC; danceType = tango
        }).also { knowledgeIndexService.indexMaterial(it.id!!) }
        val embedding = FloatArray(768).also { it[0] = 1f }

        fun ids(type: UUID?) = knowledgeChunkRepository.hybridSearch(
            query = null, queryEmbedding = embedding, currentUser = userA, topK = 1, danceTypeId = type
        ).map { it.chunk.sourceId }

        assertEquals(listOf(nearer.id), ids(null), "unfiltered, the nearer note takes the only slot")
        assertEquals(listOf(farther.id), ids(tango.id), "filtered, the tango note is not crowded out")
    }

    @Test
    fun `the vector query honours the distance threshold and reports the distance`() {
        val n = note(userA, "Body motion", "all about sway", Visibility.PUBLIC)
        val embedding = FloatArray(768).also { it[0] = 1f } // "sway" only; the note embeds on sway and waltz, 45 degrees away

        fun run(max: Double) = knowledgeChunkRepository.hybridSearch(
            query = null, queryEmbedding = embedding, currentUser = userA, maxDistance = max
        ).filter { it.chunk.sourceId == n.id }

        val within = run(0.65)
        assertEquals(1, within.size)
        assertTrue(within.single().distance!! in 0.2..0.4, "distance was ${within.single().distance}")
        assertTrue(run(0.1).isEmpty())
    }
}
