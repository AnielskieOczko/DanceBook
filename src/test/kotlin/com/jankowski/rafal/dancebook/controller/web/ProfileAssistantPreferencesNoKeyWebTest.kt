package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key="])
class ProfileAssistantPreferencesNoKeyWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var preferencesRepository: UserAssistantPreferencesRepository
    @Autowired private lateinit var assistantPreferencesService: AssistantPreferencesService

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("asst_nokey_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "asst_nokey_user"
            displayName = "Assistant No Key User"
            role = Role.USER
        })
    }

    private fun asUser() = user(testUser.username).roles("USER")

    @Test
    fun `profile shows not set up message and rows are disabled when API key is missing`() {
        // Save preferences with everything explicitly ON
        val entity = UserAssistantPreferences().apply {
            userId = testUser.id
            assistantEnabled = true
            semanticSearchEnabled = true
            relatedNotesEnabled = true
            suggestFiguresEnabled = true
            openInFullWindow = true
            replyLanguage = AssistantReplyLanguage.APP
        }
        preferencesRepository.save(entity)

        // Effective preferences still have assistant features OFF because API key is missing
        val effective = assistantPreferencesService.forUser(testUser)
        assertFalse(effective.assistantEnabled)
        assertFalse(effective.semanticSearchEnabled)
        assertFalse(effective.relatedNotesEnabled)
        assertTrue(effective.suggestFiguresEnabled)
        assertFalse(effective.openInFullWindow)

        val result = mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val section = doc.selectFirst("#assistant-section")
        org.junit.jupiter.api.Assertions.assertNotNull(section)
        assertTrue(section!!.text().contains("The assistant isn't set up on this server."))
        assertTrue(section.text().contains("Disabled: The assistant isn't set up on this server."))

        // All checkboxes are disabled
        val inputs = section.select("input[type=checkbox]")
        assertTrue(inputs.size >= 5)
        inputs.forEach { input ->
            assertTrue(input.hasAttr("disabled"), "Checkbox ${input.attr("name")} must be disabled")
        }

        // Reply language select is disabled
        val select = section.selectFirst("select[name=replyLanguage]")
        org.junit.jupiter.api.Assertions.assertNotNull(select)
        assertTrue(select!!.hasAttr("disabled"))
    }
}
