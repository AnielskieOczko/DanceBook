package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
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
class ProfileStreaksAndBadgesWebTest {

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
    @MockitoSpyBean private lateinit var assistantPreferencesService: AssistantPreferencesService

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        reset(assistantPreferencesService)
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("streak_pref_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "streak_pref_user"
            displayName = "Streak Preferences User"
            role = Role.USER
        })
        preferencesRepository.deleteById(testUser.id!!)
    }

    private fun asUser() = user(testUser.username).roles("USER")

    @Test
    fun `profile page renders streaks and badges row on by default with hero streak and badges row`() {
        val result = mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"streaks-badges-section\"")))
            .andExpect(content().string(containsString("Streaks and badges")))
            .andExpect(content().string(containsString("Showing streaks and badges")))
            .andExpect(content().string(containsString("Badges")))
            .andExpect(content().string(containsString("No active streak")))
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val checkbox = doc.selectFirst("#streaks-badges-section input[type=checkbox][name=showStreaksAndBadges]")
        assertTrue(checkbox != null && checkbox.hasAttr("checked"), "Checkbox must be checked by default")
    }

    @Test
    fun `profile page in Polish renders Polish labels`() {
        mockMvc.perform(get("/profile").with(asUser()).locale(Locale.forLanguageTag("pl")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Serie i odznaki")))
            .andExpect(content().string(containsString("Wyświetlanie serii i odznak")))
            .andExpect(content().string(containsString("Pokazuj serie i odznaki")))
            .andExpect(content().string(containsString("Zapisz preferencje")))
    }

    @Test
    fun `saving preference to false persists and redirects`() {
        mockMvc.perform(
            post("/profile/streaks-and-badges")
                .with(asUser())
                .with(csrf())
                .param("showStreaksAndBadges", "false")
                .param("_showStreaksAndBadges", "on")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("showStreaksAndBadgesSuccessMsg", true))

        val saved = preferencesRepository.findById(testUser.id!!).get()
        assertFalse(saved.showStreaksAndBadges)
    }

    @Test
    fun `when preference is off, hero streak and badges row are hidden, hours sessions attendance still render`() {
        val entity = UserAssistantPreferences().apply {
            userId = testUser.id
            showStreaksAndBadges = false
        }
        preferencesRepository.save(entity)

        val result = mockMvc.perform(get("/profile").with(asUser()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("No active streak"))))
            .andExpect(content().string(not(containsString("12-session streak"))))
            .andExpect(content().string(not(containsString("0 of 0 earned"))))
            .andExpect(content().string(containsString("Training")))
            .andExpect(content().string(containsString("No training sessions this year")))
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        // Badges accordion row is hidden
        val badgesSummary = doc.select("summary").toList().filter { it.text().contains("Badges") }
        assertTrue(badgesSummary.isEmpty(), "Badges row should be hidden when preference is off")

        // Streaks and badges settings row is still present
        val settingsSection = doc.selectFirst("#streaks-badges-section")
        assertTrue(settingsSection != null, "Streaks and badges settings section must still be available")
        assertTrue(settingsSection!!.text().contains("Streaks and badges hidden"))
    }

    @Test
    fun `updating preference via HTMX returns streaksAndBadgesSection fragment`() {
        mockMvc.perform(
            post("/profile/streaks-and-badges")
                .with(asUser())
                .with(csrf())
                .header("HX-Request", "true")
                .param("showStreaksAndBadges", "false")
                .param("_showStreaksAndBadges", "on")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"streaks-badges-section\"")))
            .andExpect(content().string(containsString("Preferences saved.")))

        val saved = preferencesRepository.findById(testUser.id!!).get()
        assertFalse(saved.showStreaksAndBadges)
    }

    @Test
    fun `unknown value in save is rejected with 400 and nothing is stored`() {
        mockMvc.perform(
            post("/profile/streaks-and-badges")
                .with(asUser())
                .with(csrf())
                .param("showStreaksAndBadges", "invalid_value")
        )
            .andExpect(status().isBadRequest)

        assertFalse(preferencesRepository.findById(testUser.id!!).isPresent)
    }

    @Test
    fun `other users are unaffected by user turning off streaks`() {
        val otherUser = appUserRepository.save(AppUser().apply {
            username = "other_user_${UUID.randomUUID()}"
            displayName = "Other User"
            role = Role.USER
        })

        // testUser turns streaks off
        preferencesRepository.save(UserAssistantPreferences().apply {
            userId = testUser.id
            showStreaksAndBadges = false
        })

        // otherUser still sees streaks and badges on
        mockMvc.perform(get("/profile").with(user(otherUser.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No active streak")))
            .andExpect(content().string(containsString("Badges")))
            .andExpect(content().string(containsString("Showing streaks and badges")))
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `failed save keeps old value on and shows error in row over HTMX`() {
        preferencesRepository.save(UserAssistantPreferences().apply {
            userId = testUser.id
            showStreaksAndBadges = true
        })

        doThrow(RuntimeException("Simulated persistence failure"))
            .`when`(assistantPreferencesService)
            .updatePreferences(anyNonNull(testUser), anyNonNull(AssistantPreferencesUpdateRequest()))

        mockMvc.perform(
            post("/profile/streaks-and-badges")
                .with(asUser())
                .with(csrf())
                .header("HX-Request", "true")
                .param("showStreaksAndBadges", "false")
                .param("_showStreaksAndBadges", "on")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"streaks-badges-section\"")))
            .andExpect(content().string(containsString("Failed to update preferences.")))
            .andExpect(content().string(not(containsString("Preferences saved."))))
            .andExpect(content().string(containsString("Showing streaks and badges")))
            .andExpect(content().string(containsString("checked")))

        val stored = preferencesRepository.findById(testUser.id!!).get()
        assertTrue(stored.showStreaksAndBadges)
    }

    @Test
    fun `failed save keeps old value on and shows error in row on full redirect`() {
        preferencesRepository.save(UserAssistantPreferences().apply {
            userId = testUser.id
            showStreaksAndBadges = true
        })

        doThrow(RuntimeException("Simulated persistence failure"))
            .`when`(assistantPreferencesService)
            .updatePreferences(anyNonNull(testUser), anyNonNull(AssistantPreferencesUpdateRequest()))

        val postResult = mockMvc.perform(
            post("/profile/streaks-and-badges")
                .with(asUser())
                .with(csrf())
                .param("showStreaksAndBadges", "false")
                .param("_showStreaksAndBadges", "on")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("showStreaksAndBadgesErrorMsg", true))
            .andReturn()

        mockMvc.perform(
            get("/profile")
                .with(asUser())
                .flashAttrs(postResult.flashMap)
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Failed to update preferences.")))
            .andExpect(content().string(not(containsString("Preferences saved."))))
            .andExpect(content().string(containsString("Showing streaks and badges")))
            .andExpect(content().string(containsString("No active streak")))
            .andExpect(content().string(containsString("Badges")))

        val stored = preferencesRepository.findById(testUser.id!!).get()
        assertTrue(stored.showStreaksAndBadges)
    }
}
