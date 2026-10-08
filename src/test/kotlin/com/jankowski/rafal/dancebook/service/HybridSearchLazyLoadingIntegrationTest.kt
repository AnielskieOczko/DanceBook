package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Open Session in View is off in this app, so what search and related notes return must be fully
 * usable after the call. This test deliberately has no transaction and no request, and reads the
 * lazy relations a page or a later consumer would.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = [
    "google.calendar.calendar-id=integration-test-calendar",
    "google.ai.api-key=test-key",
    "google.ai.embedding-requests-per-minute=60000"
])
@Import(RagIntegrationTest.RagTestConfig::class)
class HybridSearchLazyLoadingIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var hybridSearchService: HybridSearchService
    @Autowired private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService
    @Autowired private lateinit var knowledgeIndexService: KnowledgeIndexService
    @Autowired private lateinit var knowledgeChunkRepository: KnowledgeChunkRepository
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var user: AppUser
    private lateinit var waltz: DanceType
    private lateinit var catalogFigure: DanceFigure

    @BeforeEach
    fun setUp() {
        knowledgeChunkRepository.deleteAll()
        materialRepository.deleteAll()
        danceFigureRepository.deleteAll()
        user = appUserRepository.save(AppUser().apply {
            username = "lazy-${UUID.randomUUID()}"; displayName = "Lazy"; password = "x"; role = Role.USER
        })
        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        waltz = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; category = cat })
        catalogFigure = danceFigureRepository.save(DanceFigure().apply {
            name = "Sway figure"; danceType = waltz; danceClass = DanceClass.D; createdBy = user
        })
        knowledgeIndexService.indexFigure(catalogFigure.id!!)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)
        `when`(appUserService.getCurrentUser()).thenReturn(user)
    }

    private fun noteWithPin(name: String): Material {
        val n = Material().apply {
            owner = user; this.name = name; description = "sway"; visibility = Visibility.PUBLIC; danceType = waltz
        }
        n.figures.add(Figure().apply { material = n; danceFigure = catalogFigure })
        return materialRepository.save(n).also { knowledgeIndexService.indexMaterial(it.id!!) }
    }

    private fun readEverythingAPageCouldRead(n: Material) {
        assertTrue(n.name.isNotEmpty())
        assertTrue(n.owner!!.displayName.isNotEmpty())
        assertTrue(n.danceType!!.name.isNotEmpty())
        assertTrue(n.danceType!!.category!!.name.isNotEmpty())
        assertEquals("Sway figure", n.figures.single().name)
        assertTrue(n.figures.single().danceFigure!!.danceType!!.name.isNotEmpty())
    }

    @Test
    fun `search results can be read after the call with no transaction and no request`() {
        val note = noteWithPin("Sway note")

        val hits = hybridSearchService.search("sway")

        val noteHit = hits.single { it.type == SearchHitType.NOTE }
        assertEquals(note.id, noteHit.id)
        readEverythingAPageCouldRead(noteHit.note!!)

        val fig = hits.single { it.type == SearchHitType.FIGURE }.figure!!
        assertTrue(fig.danceType!!.name.isNotEmpty())
        assertTrue(fig.danceType!!.category!!.name.isNotEmpty())
        assertEquals("Lazy", fig.createdBy!!.displayName)
    }

    @Test
    fun `semantic-only results can be read after the call too`() {
        // "sway" and the query "sway waltz" share no keyword match for this note, only meaning.
        val sem = Material().apply {
            owner = user; name = "Body motion"; description = "all about sway"
            visibility = Visibility.PUBLIC; danceType = waltz
        }
        sem.figures.add(Figure().apply { material = sem; danceFigure = catalogFigure })
        val saved = materialRepository.save(sem).also { knowledgeIndexService.indexMaterial(it.id!!) }

        val hit = hybridSearchService.search("sway waltz").first { it.id == saved.id }

        assertEquals(SearchMatch.SEMANTIC, hit.match)
        readEverythingAPageCouldRead(hit.note!!)
    }

    @Test
    fun `related notes can be read after the call with no transaction and no request`() {
        val a = noteWithPin("Sway one")
        val b = noteWithPin("Sway two")

        val related = knowledgeRetrievalService.findRelatedNotesForMaterial(a.id!!, user, 3)

        assertEquals(listOf(b.id), related.map { it.id })
        readEverythingAPageCouldRead(related.single())
    }
}
