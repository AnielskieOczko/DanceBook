package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
import com.jankowski.rafal.dancebook.service.FigureSuggestionService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.jankowski.rafal.dancebook.service.ScriptedChatModel
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
@Import(AssistantPreferencesEnforcementWebTest.ScriptedModelConfig::class)
class AssistantPreferencesEnforcementWebTest {

    @TestConfiguration
    class ScriptedModelConfig {
        @Bean
        @Primary
        fun scriptedChatModel(): ChatModel = ScriptedChatModel(listOf(ScriptedChatModel.text("Scripted response")))
    }

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var materialRepository: MaterialRepository

    @Autowired
    private lateinit var assistantPreferencesService: AssistantPreferencesService

    @MockitoBean
    private lateinit var googleCalendarClient: GoogleCalendarClient

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var figureSuggestionService: FigureSuggestionService

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        testUser = appUserRepository.findByUsername("enforcement-user") ?: appUserRepository.save(AppUser().apply {
            username = "enforcement-user"
            email = "enforcement-user@example.com"
            displayName = "Enforcement User"
            password = "password"
            role = Role.USER
        })
    }

    private fun asUser() = user(testUser.username).roles("USER")

    @Test
    fun `when assistant is disabled in preferences, assistant endpoints return 404 and widget is omitted from page`() {
        assistantPreferencesService.updatePreferences(testUser, AssistantPreferencesUpdateRequest(assistantEnabled = false))

        mockMvc.perform(get("/assistant/start").with(asUser()))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/assistant/conversations").with(asUser()))
            .andExpect(status().isNotFound)

        val pageResult = mockMvc.perform(get("/materials").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pageResult.response.contentAsString)
        assertNull(doc.selectFirst("#assistantSurface"), "Assistant surface should not be rendered when assistant is disabled")
        assertNull(doc.selectFirst("button[data-assistant-open]"), "Assistant nav button should not be rendered when assistant is disabled")
    }

    @Test
    fun `when assistant is enabled in preferences, assistant endpoints return 200 and widget is rendered`() {
        assistantPreferencesService.updatePreferences(testUser, AssistantPreferencesUpdateRequest(assistantEnabled = true))

        mockMvc.perform(get("/assistant/start").with(asUser()))
            .andExpect(status().isOk)

        mockMvc.perform(get("/assistant/conversations").with(asUser()))
            .andExpect(status().isOk)

        val pageResult = mockMvc.perform(get("/materials").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pageResult.response.contentAsString)
        assertNotNull(doc.selectFirst("#assistantSurface"), "Assistant surface should be rendered when assistant is enabled")
        assertNotNull(doc.selectFirst("button[data-assistant-open]"), "Assistant nav button should be rendered when assistant is enabled")
    }

    @Test
    fun `when openInFullWindow is true, assistant surface has data-full-window true`() {
        assistantPreferencesService.updatePreferences(
            testUser,
            AssistantPreferencesUpdateRequest(assistantEnabled = true, openInFullWindow = true)
        )

        val pageResult = mockMvc.perform(get("/materials").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pageResult.response.contentAsString)
        val surface = doc.selectFirst("#assistantSurface")
        assertNotNull(surface)
        assertEquals("true", surface!!.attr("data-full-window"))
        assertFalse(surface.hasClass("is-expanded"), "Server-rendered markup should not have is-expanded class; JS expands dynamically")
    }

    @Test
    fun `when openInFullWindow is false, assistant surface has data-full-window false`() {
        assistantPreferencesService.updatePreferences(
            testUser,
            AssistantPreferencesUpdateRequest(assistantEnabled = true, openInFullWindow = false)
        )

        val pageResult = mockMvc.perform(get("/materials").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pageResult.response.contentAsString)
        val surface = doc.selectFirst("#assistantSurface")
        assertNotNull(surface)
        assertEquals("false", surface!!.attr("data-full-window"))
        assertFalse(surface.hasClass("is-expanded"), "Surface must not have is-expanded class when openInFullWindow is false")
    }

    @Test
    fun `when suggestFiguresEnabled is false, picker omits suggest button and endpoint returns 403`() {
        val material = materialRepository.save(Material().apply {
            name = "Test Note ${UUID.randomUUID()}"
            owner = testUser
            description = "Some description"
        })

        assistantPreferencesService.updatePreferences(
            testUser,
            AssistantPreferencesUpdateRequest(assistantEnabled = true, suggestFiguresEnabled = false)
        )

        val pickerResult = mockMvc.perform(get("/materials/${material.id}/figures/picker").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pickerResult.response.contentAsString)
        assertNull(doc.selectFirst("#figure-suggest-btn"), "Suggest figures button should not be rendered when disabled in preferences")

        mockMvc.perform(get("/materials/${material.id}/figures/suggest").with(asUser()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `when suggestFiguresEnabled is true, picker renders suggest button`() {
        val material = materialRepository.save(Material().apply {
            name = "Test Note ${UUID.randomUUID()}"
            owner = testUser
            description = "Some description"
        })

        assistantPreferencesService.updatePreferences(
            testUser,
            AssistantPreferencesUpdateRequest(assistantEnabled = true, suggestFiguresEnabled = true)
        )

        val pickerResult = mockMvc.perform(get("/materials/${material.id}/figures/picker").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(pickerResult.response.contentAsString)
        assertNotNull(doc.selectFirst("#figure-suggest-btn"), "Suggest figures button should be rendered when enabled in preferences")
    }
}
