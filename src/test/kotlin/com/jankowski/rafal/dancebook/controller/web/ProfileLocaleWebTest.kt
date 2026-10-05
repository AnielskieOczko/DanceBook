package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class ProfileLocaleWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("locale_test_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "locale_test_user"
            email = "locale_test@example.com"
            displayName = "Locale Test User"
            role = Role.USER
            locale = null
        })
        testUser.locale = null
        appUserRepository.save(testUser)
    }

    @Test
    fun `profile renders in Polish when user has stored Polish locale`() {
        testUser.locale = "pl"
        appUserRepository.save(testUser)

        val result = mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
                .header("Accept-Language", "en")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Ustawienia konta")))
            .andExpect(content().string(containsString("Moje kolekcje")))
            .andExpect(content().string(containsString("Zmień hasło")))
            .andExpect(content().string(containsString("Wyloguj się")))
            .andExpect(content().string(containsString("DanceBook")))
            .andExpect(content().string(not(containsString("Update Security"))))
            .andExpect(content().string(not(containsString("Zaktualizuj zabezpieczenia"))))
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val submitBtn = doc.selectFirst("#password-section button[type=submit]")
        assertEquals("Zmień hasło", submitBtn?.text()?.trim())
    }

    @Test
    fun `profile renders in English when user has stored English locale`() {
        testUser.locale = "en"
        appUserRepository.save(testUser)

        val result = mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
                .header("Accept-Language", "pl")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Account Settings")))
            .andExpect(content().string(containsString("My Collections")))
            .andExpect(content().string(containsString("Update Password")))
            .andExpect(content().string(containsString("Log Out")))
            .andExpect(content().string(containsString("DanceBook")))
            .andExpect(content().string(not(containsString("Update Security"))))
            .andExpect(content().string(not(containsString("Zaktualizuj zabezpieczenia"))))
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val submitBtn = doc.selectFirst("#password-section button[type=submit]")
        assertEquals("Update Password", submitBtn?.text()?.trim())
    }

    @Test
    fun `profile falls back to Accept-Language header when user has no stored locale`() {
        testUser.locale = null
        appUserRepository.save(testUser)

        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
                .header("Accept-Language", "pl-PL,pl;q=0.9")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Ustawienia konta")))
            .andExpect(content().string(containsString("Moje kolekcje")))

        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
                .header("Accept-Language", "en-US,en;q=0.9")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Account Settings")))
            .andExpect(content().string(containsString("My Collections")))
    }

    @Test
    fun `switching language in profile persists to database and redirects`() {
        mockMvc.perform(
            post("/profile/locale")
                .with(csrf())
                .with(user(testUser.username).roles("USER"))
                .param("locale", "pl")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        val userAfterPl = appUserRepository.findById(testUser.id!!).get()
        assertEquals("pl", userAfterPl.locale)

        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Ustawienia konta")))

        mockMvc.perform(
            post("/profile/locale")
                .with(csrf())
                .with(user(testUser.username).roles("USER"))
                .param("locale", "")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        val userAfterReset = appUserRepository.findById(testUser.id!!).get()
        assertNull(userAfterReset.locale)
    }

    @Test
    fun `switching language via HTMX returns HX-Redirect header`() {
        mockMvc.perform(
            post("/profile/locale")
                .with(csrf())
                .with(user(testUser.username).roles("USER"))
                .header("HX-Request", "true")
                .param("locale", "pl")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("HX-Redirect", "/profile"))
    }

    @Test
    fun `posting unsupported locale does not change stored locale and returns normal redirect without 5xx`() {
        testUser.locale = "en"
        appUserRepository.save(testUser)

        val result = mockMvc.perform(
            post("/profile/locale")
                .with(csrf())
                .with(user(testUser.username).roles("USER"))
                .param("locale", "xx")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andReturn()

        val userAfter = appUserRepository.findById(testUser.id!!).get()
        assertEquals("en", userAfter.locale, "Stored locale must remain unchanged when invalid value is posted")

        // Follow the redirect with flash attributes to verify the user-facing error message
        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
                .flashAttrs(result.flashMap)
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("The selected language is not supported.")))
    }

    @Test
    fun `posting over-long locale does not change stored locale and does not 500`() {
        testUser.locale = "en"
        appUserRepository.save(testUser)

        mockMvc.perform(
            post("/profile/locale")
                .with(csrf())
                .with(user(testUser.username).roles("USER"))
                .param("locale", "toolonglocalename12345")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        val userAfter = appUserRepository.findById(testUser.id!!).get()
        assertEquals("en", userAfter.locale, "Stored locale must remain unchanged when over-long value is posted")
    }
}
