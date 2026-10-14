package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantReplyLanguage
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.Locale
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class ProfileAssistantPreferencesWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var preferencesRepository: UserAssistantPreferencesRepository

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("asst_pref_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "asst_pref_user"
            displayName = "Assistant Preferences User"
            role = Role.USER
        })
        preferencesRepository.deleteById(testUser.id!!)
    }

    private fun asUser() = user(testUser.username).roles("USER")

    @Test
    fun `profile page renders assistant preferences with defaults`() {
        mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-section\"")))
            .andExpect(content().string(containsString("Assistant on")))
            .andExpect(content().string(containsString("Semantic search")))
            .andExpect(content().string(containsString("Related notes")))
            .andExpect(content().string(containsString("Suggest figures")))
            .andExpect(content().string(containsString("Open in full window")))
            .andExpect(content().string(containsString("Assistant reply language")))
            .andExpect(content().string(containsString("Preferences configured")))
    }

    @Test
    fun `profile page in Polish renders Polish labels`() {
        mockMvc.perform(get("/profile").with(asUser()).locale(Locale.forLanguageTag("pl")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Asystent włączony")))
            .andExpect(content().string(containsString("Wyszukiwanie semantyczne")))
            .andExpect(content().string(containsString("Powiązane notatki")))
            .andExpect(content().string(containsString("Sugerowanie figur")))
            .andExpect(content().string(containsString("Otwieraj w pełnym oknie")))
            .andExpect(content().string(containsString("Język odpowiedzi asystenta")))
            .andExpect(content().string(containsString("Skonfigurowano preferencje")))
    }

    @Test
    fun `include shared items is saved off only when the form says so and defaults on`() {
        mockMvc.perform(
            post("/profile/assistant-preferences").with(asUser()).with(csrf())
                .param("includeSharedItems", "false").param("_includeSharedItems", "on")
        ).andExpect(status().is3xxRedirection)
        assertFalse(preferencesRepository.findById(testUser.id!!).get().includeSharedItems)

        mockMvc.perform(
            post("/profile/assistant-preferences").with(asUser()).with(csrf())
                .param("includeSharedItems", "true").param("_includeSharedItems", "on")
        ).andExpect(status().is3xxRedirection)
        assertTrue(preferencesRepository.findById(testUser.id!!).get().includeSharedItems)
    }

    @Test
    fun `the profile shows the include shared items row, on by default, in both languages`() {
        val en = mockMvc.perform(get("/profile").with(asUser())).andExpect(status().isOk).andReturn().response.contentAsString
        val input = org.jsoup.Jsoup.parse(en).selectFirst("input[name=includeSharedItems]")!!
        assertTrue(input.hasAttr("checked"))
        assertTrue(en.contains("Include shared items"))

        val pl = mockMvc.perform(get("/profile").with(asUser()).locale(Locale.forLanguageTag("pl"))).andReturn().response.contentAsString
        assertTrue(pl.contains("Uwzględniaj udostępnione"))
    }

    @Test
    fun `updating assistant preferences persists and redirects`() {
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser())
                .with(csrf())
                .param("assistantEnabled", "true")
                .param("semanticSearchEnabled", "false")
                .param("relatedNotesEnabled", "false")
                .param("suggestFiguresEnabled", "true")
                .param("openInFullWindow", "true")
                .param("replyLanguage", "PL")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("assistantSuccessMsg", true))

        val saved = preferencesRepository.findById(testUser.id!!).get()
        assertTrue(saved.assistantEnabled)
        assertFalse(saved.semanticSearchEnabled)
        assertFalse(saved.relatedNotesEnabled)
        assertTrue(saved.suggestFiguresEnabled)
        assertTrue(saved.openInFullWindow)
        assertEquals(AssistantReplyLanguage.PL, saved.replyLanguage)
    }

    @Test
    fun `updating assistant preferences via HTMX returns assistantSection fragment`() {
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser())
                .with(csrf())
                .header("HX-Request", "true")
                .param("openInFullWindow", "true")
                .param("replyLanguage", "EN")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-section\"")))
            .andExpect(content().string(containsString("Assistant preferences saved.")))

        val saved = preferencesRepository.findById(testUser.id!!).get()
        assertTrue(saved.openInFullWindow)
        assertEquals(AssistantReplyLanguage.EN, saved.replyLanguage)
    }

    @Test
    fun `unknown reply language is rejected with 400 and nothing is stored`() {
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser())
                .with(csrf())
                .param("replyLanguage", "INVALID_LANG")
        )
            .andExpect(status().isBadRequest)

        assertFalse(preferencesRepository.findById(testUser.id!!).isPresent)
    }

    @Test
    fun `saving for another user is impossible because endpoint only acts on authenticated user`() {
        val otherUser = appUserRepository.save(AppUser().apply {
            username = "victim_user_${UUID.randomUUID()}"
            displayName = "Victim"
            role = Role.USER
        })

        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser())
                .with(csrf())
                .param("id", otherUser.id.toString())
                .param("userId", otherUser.id.toString())
                .param("openInFullWindow", "true")
        )
            .andExpect(status().is3xxRedirection)

        // otherUser's preferences were not created or touched
        assertFalse(preferencesRepository.findById(otherUser.id!!).isPresent)
        // testUser's preferences were updated
        assertTrue(preferencesRepository.findById(testUser.id!!).get().openInFullWindow)
    }

    @Test
    fun `when assistant is off the other rows and the save button stay usable`() {
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser())
                .with(csrf())
                .param("assistantEnabled", "false")
                .param("_assistantEnabled", "on")
        )
            .andExpect(status().is3xxRedirection)

        val result = mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Assistant off")))
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val section = doc.selectFirst("#assistant-section")
        org.junit.jupiter.api.Assertions.assertNotNull(section)

        val asstInput = section!!.selectFirst("input[name=assistantEnabled]")
        org.junit.jupiter.api.Assertions.assertNotNull(asstInput)
        assertFalse(asstInput!!.hasAttr("disabled"))
        assertFalse(asstInput.hasAttr("checked"))

        // A disabled checkbox is not submitted while its hidden marker is, so disabling the other rows would
        // switch their saved choices off when the assistant is turned back on. They must stay enabled.
        for (name in listOf("semanticSearchEnabled", "relatedNotesEnabled", "suggestFiguresEnabled", "openInFullWindow")) {
            assertFalse(section.selectFirst("input[name=$name]")!!.hasAttr("disabled"), name)
        }
        assertFalse(section.selectFirst("select[name=replyLanguage]")!!.hasAttr("disabled"))

        // With the assistant off the user must still be able to save, or they could never turn it on again.
        assertFalse(section.selectFirst("button[type=submit]")!!.hasAttr("disabled"))
    }

    @Test
    fun `the assistant row uses no inline event handlers because the content security policy blocks them`() {
        val result = mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andReturn()

        val section = org.jsoup.Jsoup.parse(result.response.contentAsString).selectFirst("#assistant-section")
        org.junit.jupiter.api.Assertions.assertNotNull(section)
        val withHandler = section!!.getAllElements().filter { el -> el.attributes().any { it.key.startsWith("on") } }
        assertTrue(withHandler.isEmpty(), "inline handlers: " + withHandler.map { it.tagName() })
    }

    @Test
    fun `turning the assistant back on keeps the other saved choices`() {
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser()).with(csrf())
                .param("assistantEnabled", "false").param("_assistantEnabled", "on")
                .param("_semanticSearchEnabled", "on")
                .param("relatedNotesEnabled", "true").param("_relatedNotesEnabled", "on")
                .param("suggestFiguresEnabled", "true").param("_suggestFiguresEnabled", "on")
                .param("_openInFullWindow", "on")
        ).andExpect(status().is3xxRedirection)
        assertFalse(preferencesRepository.findById(testUser.id!!).get().semanticSearchEnabled)

        // The browser submits exactly what the page shows: semantic search unchecked, the others as saved.
        mockMvc.perform(
            post("/profile/assistant-preferences")
                .with(asUser()).with(csrf())
                .param("assistantEnabled", "true").param("_assistantEnabled", "on")
                .param("_semanticSearchEnabled", "on")
                .param("relatedNotesEnabled", "true").param("_relatedNotesEnabled", "on")
                .param("suggestFiguresEnabled", "true").param("_suggestFiguresEnabled", "on")
                .param("_openInFullWindow", "on")
        ).andExpect(status().is3xxRedirection)

        val saved = preferencesRepository.findById(testUser.id!!).get()
        assertTrue(saved.assistantEnabled)
        assertFalse(saved.semanticSearchEnabled)
        assertTrue(saved.relatedNotesEnabled)
        assertTrue(saved.suggestFiguresEnabled)
    }
}
