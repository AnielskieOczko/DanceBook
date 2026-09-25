package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * #152: figures are public and edited by everyone; deleting is limited to the creator or an
 * admin, refused while another user's item uses the figure, and admin-only for syllabus figures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class CommunityFiguresIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var dataSource: DataSource

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var admin: AppUser
    private lateinit var danceType: DanceType

    private fun userNamed(name: String, role: Role = Role.USER): AppUser =
        appUserRepository.findByUsername(name) ?: appUserRepository.save(AppUser().apply {
            username = name
            displayName = name
            email = "$name@example.com"
            password = passwordEncoder.encode("secret")
            this.role = role
        })

    private fun MockHttpServletRequestBuilder.by(u: AppUser) = with(user(u.username).roles(u.role.name))

    private fun figure(createdBy: AppUser?, predefined: Boolean = false): DanceFigure =
        danceFigureRepository.save(DanceFigure().apply {
            name = "Community Figure " + UUID.randomUUID()
            danceType = this@CommunityFiguresIntegrationTest.danceType
            this.createdBy = createdBy
            this.predefined = predefined
        })

    private fun saveFigure(figure: DanceFigure, name: String, version: Long, by: AppUser) =
        mockMvc.perform(
            post("/dance-figures/${figure.id}")
                .param("name", name)
                .param("danceTypeId", danceType.id.toString())
                .param("version", version.toString())
                .with(csrf()).by(by)
        )

    private fun noteUsing(figure: DanceFigure, owner: AppUser) {
        val note = materialRepository.save(Material().apply {
            name = "Note using figure"
            this.owner = owner
            visibility = Visibility.PRIVATE
            danceType = this@CommunityFiguresIntegrationTest.danceType
            updatedAt = LocalDateTime.now()
        })
        note.figures.add(Figure().apply { material = note; danceFigure = figure; startTime = 0; endTime = 5 })
        materialRepository.save(note)
    }

    private fun choreographyUsing(figure: DanceFigure, owner: AppUser) {
        val choreography = choreographyRepository.save(Choreography().apply {
            name = "Choreography using figure"
            this.owner = owner
            danceType = this@CommunityFiguresIntegrationTest.danceType
            updatedAt = LocalDateTime.now()
        })
        choreography.entries.add(ChoreographyEntry().apply {
            this.choreography = choreography
            entryType = EntryType.FIGURE
            danceFigure = figure
            sortOrder = 0
        })
        choreographyRepository.save(choreography)
    }

    @BeforeEach
    fun setUp() {
        userA = userNamed("fig-user-a")
        userB = userNamed("fig-user-b")
        admin = userNamed("fig-admin", Role.ADMIN)
        val category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Community Cat " + UUID.randomUUID()
        })
        danceType = danceTypeRepository.save(DanceType().apply {
            name = "Community Style " + UUID.randomUUID()
            this.category = category
        })
    }

    @Test
    fun `another user can edit a figure and a syllabus figure, and the feed names them as the actor`() {
        val figureByA = figure(createdBy = userA)
        val syllabus = figure(createdBy = null, predefined = true)

        saveFigure(figureByA, "Edited by B", figureByA.version, userB)
            .andExpect(status().is3xxRedirection)
        saveFigure(syllabus, "Syllabus edited by B", syllabus.version, userB)
            .andExpect(status().is3xxRedirection)

        assertEquals("Edited by B", danceFigureRepository.findById(figureByA.id!!).get().name)
        assertEquals("Syllabus edited by B", danceFigureRepository.findById(syllabus.id!!).get().name)
        val actor = JdbcTemplate(dataSource).queryForObject(
            "SELECT actor_id FROM activity_event WHERE target_id = ? AND event_type = 'DANCE_FIGURE_UPDATED'",
            UUID::class.java, figureByA.id
        )
        assertEquals(userB.id, actor)
    }

    @Test
    fun `a save from a stale form gets a conflict message and does not overwrite the first save`() {
        val shared = figure(createdBy = userA)
        val renderedVersion = shared.version

        saveFigure(shared, "First save", renderedVersion, userA)
            .andExpect(status().is3xxRedirection)
        saveFigure(shared, "Second save", renderedVersion, userB)
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(DanceFigureService.CONFLICT_MESSAGE)))

        assertEquals("First save", danceFigureRepository.findById(shared.id!!).get().name)
    }

    @Test
    fun `the edit form carries the figure's version`() {
        val shared = figure(createdBy = userA)

        mockMvc.perform(get("/dance-figures/${shared.id}/edit").by(userB))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name=\"version\"")))
    }

    @Test
    fun `a user cannot delete a figure someone else created`() {
        val figureByA = figure(createdBy = userA)

        mockMvc.perform(post("/dance-figures/${figureByA.id}/delete").with(csrf()).by(userB))
            .andExpect(status().isForbidden)
        assertTrue(danceFigureRepository.existsById(figureByA.id!!))
    }

    @Test
    fun `the creator cannot delete a figure another user's private note uses`() {
        val figureByA = figure(createdBy = userA)
        noteUsing(figureByA, userB)

        mockMvc.perform(post("/dance-figures/${figureByA.id}/delete").with(csrf()).by(userA))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dance-figures/${figureByA.id}"))
            .andExpect(flash().attribute("deleteError",
                "This figure can't be deleted: 1 note belonging to other users uses it."))
        assertTrue(danceFigureRepository.existsById(figureByA.id!!))
    }

    @Test
    fun `the creator cannot delete a figure another user's choreography uses`() {
        val figureByA = figure(createdBy = userA)
        choreographyUsing(figureByA, userB)

        mockMvc.perform(post("/dance-figures/${figureByA.id}/delete").with(csrf()).by(userA))
            .andExpect(flash().attribute("deleteError",
                "This figure can't be deleted: 1 choreography belonging to other users uses it."))
        assertTrue(danceFigureRepository.existsById(figureByA.id!!))
    }

    @Test
    fun `the creator can delete a figure only their own items use`() {
        val figureByA = figure(createdBy = userA)
        noteUsing(figureByA, userA)
        choreographyUsing(figureByA, userA)

        mockMvc.perform(post("/dance-figures/${figureByA.id}/delete").with(csrf()).by(userA))
            .andExpect(redirectedUrl("/dance-figures"))
        assertFalse(danceFigureRepository.existsById(figureByA.id!!))
    }

    @Test
    fun `only an admin can delete a syllabus figure`() {
        val syllabus = figure(createdBy = null, predefined = true)

        mockMvc.perform(post("/dance-figures/${syllabus.id}/delete").with(csrf()).by(userA))
            .andExpect(status().isForbidden)
        assertTrue(danceFigureRepository.existsById(syllabus.id!!))

        mockMvc.perform(post("/dance-figures/${syllabus.id}/delete").with(csrf()).by(admin))
            .andExpect(redirectedUrl("/dance-figures"))
        assertFalse(danceFigureRepository.existsById(syllabus.id!!))
    }

    @Test
    fun `the delete action is offered only to those who may delete`() {
        val figureByA = figure(createdBy = userA)

        mockMvc.perform(get("/dance-figures/${figureByA.id}").by(userB))
            .andExpect(content().string(not(containsString("/dance-figures/${figureByA.id}/delete"))))
        mockMvc.perform(get("/dance-figures/${figureByA.id}").by(userA))
            .andExpect(content().string(containsString("/dance-figures/${figureByA.id}/delete")))
    }

    @Test
    fun `the create form says figures are shared with everyone, and the edit form does not`() {
        val shared = figure(createdBy = userA)

        mockMvc.perform(get("/dance-figures/new").by(userA))
            .andExpect(content().string(containsString("Figures are shared with every DanceBook user.")))
        mockMvc.perform(get("/dance-figures/${shared.id}/edit").by(userA))
            .andExpect(content().string(not(containsString("Figures are shared with every DanceBook user."))))
    }

    @Test
    fun `creating a figure records its creator`() {
        val name = "Created by A " + UUID.randomUUID()
        mockMvc.perform(
            post("/dance-figures")
                .param("name", name)
                .param("danceTypeId", danceType.id.toString())
                .with(csrf()).by(userA)
        ).andExpect(status().is3xxRedirection)

        val createdBy = JdbcTemplate(dataSource).queryForObject(
            "SELECT created_by_id FROM dance_figure WHERE name = ?", UUID::class.java, name
        )
        assertEquals(userA.id, createdBy)
    }
}
