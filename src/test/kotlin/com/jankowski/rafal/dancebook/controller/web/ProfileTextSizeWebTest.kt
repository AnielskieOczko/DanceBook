package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TextSize
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
class ProfileTextSizeWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var systemSettingService: com.jankowski.rafal.dancebook.service.SystemSettingService

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("text_size_test_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "text_size_test_user"
            displayName = "Text Size Test User"
            role = Role.USER
        })
        testUser.textSize = null
        testUser = appUserRepository.save(testUser)
    }

    @Test
    fun `profile page renders text size select and default html attribute`() {
        mockMvc.perform(get("/profile").with(user(testUser.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("textSizeSelect")))
            .andExpect(content().string(containsString("data-text-size=\"normal\"")))
    }

    @Test
    fun `updating text size persists and html attribute follows it`() {
        mockMvc.perform(
            post("/profile/text-size")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("textSize", "EXTRA_LARGE")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("textSizeSuccessMsg", true))

        assertEquals(TextSize.EXTRA_LARGE, appUserRepository.findById(testUser.id!!).get().textSize)

        mockMvc.perform(get("/profile").with(user(testUser.username).roles("USER")))
            .andExpect(content().string(containsString("data-text-size=\"extra_large\"")))
    }

    @Test
    fun `updating text size via HTMX returns HX-Redirect header`() {
        mockMvc.perform(
            post("/profile/text-size")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .header("HX-Request", "true")
                .param("textSize", "LARGE")
        )
            .andExpect(header().string("HX-Redirect", "/profile"))

        assertEquals(TextSize.LARGE, appUserRepository.findById(testUser.id!!).get().textSize)
    }

    @Test
    fun `invalid text size keeps stored preference and flags an error`() {
        testUser.textSize = TextSize.LARGE
        appUserRepository.save(testUser)

        mockMvc.perform(
            post("/profile/text-size")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("textSize", "GIGANTIC")
        )
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("textSizeErrorMsg", true))

        assertEquals(TextSize.LARGE, appUserRepository.findById(testUser.id!!).get().textSize)
    }
}
