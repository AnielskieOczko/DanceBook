package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CustomListRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class MaterialSearchIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var materialService: MaterialService
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var figureRepository: FigureRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var customListRepository: CustomListRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var waltz: DanceType
    private lateinit var naturalTurn: DanceFigure

    private fun user(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    private fun note(owner: AppUser, name: String, text: String?, visibility: Visibility = Visibility.PRIVATE) =
        materialRepository.save(Material().apply {
            this.owner = owner; this.name = name; description = text; this.visibility = visibility; danceType = waltz
        })

    @BeforeEach
    fun setUp() {
        figureRepository.deleteAll()
        customListRepository.deleteAll()
        materialRepository.deleteAll()
        alice = user("alice")
        bob = user("bob")
        val category = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        waltz = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; this.category = category })
        naturalTurn = danceFigureRepository.save(DanceFigure().apply {
            name = "Natural Turn ${UUID.randomUUID()}"; danceType = waltz; danceClass = DanceClass.D
        })
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(alice)
    }

    @Test
    fun `finds notes by every word in the title or text`() {
        note(alice, "Rise and fall", "no SWAY on step one, then sway to the right")
        note(alice, "Frame", "keep the frame wide")

        assertEquals(listOf("Rise and fall"), materialService.searchNotes("sway right", null, null, 10).map { it.name })
        assertEquals(2, materialService.searchNotes(null, null, null, 10).size)
        assertEquals(0, materialService.searchNotes("sway tango", null, null, 10).size)
    }

    @Test
    fun `a private note of another user is never found`() {
        note(bob, "Bob's secret sway note", "sway")
        note(bob, "Bob's public sway note", "sway", Visibility.PUBLIC)

        val found = materialService.searchNotes("sway", null, null, 10).map { it.name }
        assertEquals(listOf("Bob's public sway note"), found)
    }

    @Test
    fun `figureId keeps only notes that pin the figure`() {
        val pinning = note(alice, "Pins it", "text")
        note(alice, "Does not", "text")
        figureRepository.save(Figure().apply { material = pinning; danceFigure = naturalTurn })

        assertEquals(listOf("Pins it"), materialService.searchNotes(null, naturalTurn.id, null, 10).map { it.name })
    }

    @Test
    fun `the limit caps the result, and wildcards in the query are literal`() {
        repeat(12) { note(alice, "Note $it", "sway") }
        note(alice, "Percent", "100% sure")

        assertEquals(10, materialService.searchNotes("sway", null, null, 10).size)
        assertEquals(listOf("Percent"), materialService.searchNotes("100%", null, null, 10).map { it.name })
        assertEquals(0, materialService.searchNotes("%", null, null, 10).filter { it.name != "Percent" }.size)
    }

    @Test
    fun `findAll finds notes when terms appear in any order across title or text`() {
        note(alice, "Dropping the head in the lowering", "Coach noticed lowering issues")
        note(alice, "Waltz frame", "Avoid head dropping during rotation")
        note(alice, "Footwork only", "Heel leads and toe releases")

        val results = materialService.findAll(
            typeIds = null, categoryIds = null, minRating = null,
            textSearch = "head dropping",
            pageable = Pageable.unpaged()
        ).content.map { it.name }

        assertEquals(setOf("Dropping the head in the lowering", "Waltz frame"), results.toSet())
    }

    @Test
    fun `findAll preserves exact phrase title search`() {
        note(alice, "Rise and fall", "Lesson notes on waltz technique")
        note(alice, "Quickstep rise", "Rise on 2")

        val results = materialService.findAll(
            typeIds = null, categoryIds = null, minRating = null,
            textSearch = "Rise and fall",
            pageable = Pageable.unpaged()
        ).content.map { it.name }

        assertEquals(listOf("Rise and fall"), results)
    }

    @Test
    fun `findAll hides other users private notes from non-admin users`() {
        note(bob, "Bob's secret head dropping note", "dropping", Visibility.PRIVATE)
        note(bob, "Bob's public head dropping note", "dropping", Visibility.PUBLIC)

        val results = materialService.findAll(
            typeIds = null, categoryIds = null, minRating = null,
            textSearch = "head dropping",
            pageable = Pageable.unpaged()
        ).content.map { it.name }

        assertEquals(listOf("Bob's public head dropping note"), results)
    }

    @Test
    fun `saved list with name filter matches notes by name only and not by description`() {
        note(alice, "Quickstep technique", "sway and swing exercises")
        note(alice, "Waltz sway note", "rise and fall exercises")

        val list = customListRepository.save(CustomList().apply {
            name = "Sway collection"
            owner = alice
            nameFilter = "sway"
            isPublic = false
        })

        // Saved list matches notes through materialService.findAll with nameSearch = list.nameFilter
        val results = materialService.findAll(
            nameSearch = list.nameFilter,
            pageable = Pageable.unpaged()
        ).content.map { it.name }

        assertEquals(listOf("Waltz sway note"), results)
    }

    @Test
    fun `hasPrivateNotesMatchingFilter matches by name only and not by description`() {
        note(alice, "Quickstep technique", "sway and swing exercises", Visibility.PRIVATE)

        val hasMatching = materialService.hasPrivateNotesMatchingFilter(
            owner = alice,
            typeIds = null,
            categoryIds = null,
            minRating = null,
            nameSearch = "sway"
        )

        assertFalse(hasMatching)
    }

    @Test
    fun `nameSearch matches by name substring only whereas textSearch matches title or text`() {
        note(alice, "Natural Spin Turn", "technique for sway and swing")
        note(alice, "Sway technique", "natural spin turn details")

        // nameSearch: used by saved lists and REST API - matches title substring only
        val byName = materialService.findAll(
            nameSearch = "sway",
            pageable = Pageable.unpaged()
        ).content.map { it.name }
        assertEquals(listOf("Sway technique"), byName)

        // textSearch: used by Notes search page - matches every word across title or text
        val byText = materialService.findAll(
            textSearch = "sway",
            pageable = Pageable.unpaged()
        ).content.map { it.name }
        assertEquals(setOf("Natural Spin Turn", "Sway technique"), byText.toSet())
    }
}
