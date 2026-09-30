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
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
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
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var materialService: MaterialService
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var figureRepository: FigureRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

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
}
