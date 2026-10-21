package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class ListPaginationIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var danceFigureService: DanceFigureService
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var materialService: MaterialService
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var figureRepository: FigureRepository
    @Autowired private lateinit var customListRepository: CustomListRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var testUser: AppUser
    private lateinit var testCategory: DanceCategory

    @BeforeEach
    fun setUp() {
        figureRepository.deleteAll()
        customListRepository.deleteAll()
        materialRepository.deleteAll()
        danceFigureRepository.deleteAll()
        danceTypeRepository.deleteAll()
        danceCategoryRepository.deleteAll()

        testUser = appUserRepository.save(AppUser().apply {
            username = "pag-user-${UUID.randomUUID()}"
            displayName = "Pagination User"
            password = "x"
            role = Role.USER
        })
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)

        testCategory = danceCategoryRepository.save(DanceCategory().apply {
            name = "Standard ${UUID.randomUUID()}"
        })
    }

    @Test
    fun `figures pagination is stable across tied names and styles visiting every record exactly once`() {
        val tiedName = "Tied Figure Name"
        val styles = (1..30).map { i ->
            danceTypeRepository.save(DanceType().apply {
                name = "Style ${i.toString().padStart(2, '0')} ${UUID.randomUUID()}"
                category = testCategory
            })
        }

        val createdFigures = styles.map { style ->
            danceFigureRepository.save(DanceFigure().apply {
                name = tiedName
                danceType = style
                danceClass = DanceClass.D
            })
        }
        val expectedIds = createdFigures.map { it.id!! }.toSet()
        assertEquals(30, expectedIds.size)

        val pageSize = 10
        val visitedIds = mutableListOf<UUID>()
        var page = 0
        while (true) {
            val pageResult = danceFigureService.findAll(
                nameSearch = tiedName,
                sortBy = "name_asc",
                pageable = PageRequest.of(page, pageSize)
            )
            val content = pageResult.content
            if (content.isEmpty()) break
            visitedIds.addAll(content.map { it.id!! })
            if (!pageResult.hasNext()) break
            page++
        }

        assertEquals(30, visitedIds.size, "Must retrieve exactly 30 elements across pages")
        assertEquals(30, visitedIds.distinct().size, "All retrieved IDs across pages must be unique (no duplicates)")
        assertEquals(expectedIds, visitedIds.toSet(), "Every figure ID must be visited exactly once")
    }

    @Test
    fun `figures pagination is stable across tied style sorting visiting every record exactly once`() {
        val style = danceTypeRepository.save(DanceType().apply {
            name = "Waltz ${UUID.randomUUID()}"
            category = testCategory
        })

        val createdFigures = (1..30).map { i ->
            danceFigureRepository.save(DanceFigure().apply {
                name = "Figure ${i.toString().padStart(2, '0')}"
                danceType = style
                danceClass = DanceClass.D
            })
        }
        val expectedIds = createdFigures.map { it.id!! }.toSet()
        assertEquals(30, expectedIds.size)

        val pageSize = 10
        val visitedIds = mutableListOf<UUID>()
        var page = 0
        while (true) {
            val pageResult = danceFigureService.findAll(
                typeIds = listOf(style.id!!),
                sortBy = "style_asc",
                pageable = PageRequest.of(page, pageSize)
            )
            val content = pageResult.content
            if (content.isEmpty()) break
            visitedIds.addAll(content.map { it.id!! })
            if (!pageResult.hasNext()) break
            page++
        }

        assertEquals(30, visitedIds.size, "Must retrieve exactly 30 elements across pages")
        assertEquals(30, visitedIds.distinct().size, "All retrieved IDs across pages must be unique (no duplicates)")
        assertEquals(expectedIds, visitedIds.toSet(), "Every figure ID must be visited exactly once")
    }

    @Test
    fun `notes pagination is stable across tied createdAt timestamps visiting every record exactly once`() {
        val style = danceTypeRepository.save(DanceType().apply {
            name = "Quickstep ${UUID.randomUUID()}"
            category = testCategory
        })

        val fixedTimestamp = LocalDateTime.of(2026, 1, 15, 10, 30, 0)
        val createdNotes = (1..30).map { i ->
            materialRepository.save(Material().apply {
                owner = testUser
                name = "Note ${i.toString().padStart(2, '0')}"
                description = "Content $i"
                visibility = Visibility.PRIVATE
                danceType = style
                createdAt = fixedTimestamp
            })
        }
        val expectedIds = createdNotes.map { it.id!! }.toSet()
        assertEquals(30, expectedIds.size)

        val pageSize = 10
        val visitedIds = mutableListOf<UUID>()
        var page = 0
        while (true) {
            val pageResult = materialService.findAll(
                typeIds = null,
                categoryIds = null,
                minRating = null,
                nameSearch = null,
                textSearch = null,
                pageable = PageRequest.of(page, pageSize)
            )
            val content = pageResult.content
            if (content.isEmpty()) break
            visitedIds.addAll(content.map { it.id!! })
            if (!pageResult.hasNext()) break
            page++
        }

        assertEquals(30, visitedIds.size, "Must retrieve exactly 30 notes across pages")
        assertEquals(30, visitedIds.distinct().size, "All retrieved note IDs across pages must be unique (no duplicates)")
        assertEquals(expectedIds, visitedIds.toSet(), "Every note ID must be visited exactly once")
    }
}
