package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.MedalBadgeStyle
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
class ProfileBadgeStyleWebTest {

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

        val existing = appUserRepository.findByUsername("badge_style_test_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "badge_style_test_user"
            displayName = "Badge Style Test User"
            role = Role.USER
        })
        testUser.medalBadgeStyle = null
        testUser = appUserRepository.save(testUser)
    }

    @Test
    fun `profile page renders badge style select field`() {
        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Medal Badge Style")))
            .andExpect(content().string(containsString("badgeStyleSelect")))
    }

    @Test
    fun `updating badge style persists to database and redirects`() {
        mockMvc.perform(
            post("/profile/badge-style")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("medalBadgeStyle", "MEDAL_SHAPES")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("badgeStyleSuccessMsg", true))

        val reloaded = appUserRepository.findById(testUser.id!!).get()
        assertEquals(MedalBadgeStyle.MEDAL_SHAPES, reloaded.medalBadgeStyle)
    }

    @Test
    fun `updating badge style via HTMX returns HX-Redirect header`() {
        mockMvc.perform(
            post("/profile/badge-style")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .header("HX-Request", "true")
                .param("medalBadgeStyle", "RING_AND_WORD")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("HX-Redirect", "/profile"))

        val reloaded = appUserRepository.findById(testUser.id!!).get()
        assertEquals(MedalBadgeStyle.RING_AND_WORD, reloaded.medalBadgeStyle)
    }

    @Test
    fun `selecting empty option resets badge style to follow app default`() {
        testUser.medalBadgeStyle = MedalBadgeStyle.MEDAL_ICON
        appUserRepository.save(testUser)

        mockMvc.perform(
            post("/profile/badge-style")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("medalBadgeStyle", "")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        val reloaded = appUserRepository.findById(testUser.id!!).get()
        assertNull(reloaded.medalBadgeStyle)
    }

    @Test
    fun `invalid badge style redirects with error and does not change stored preference`() {
        testUser.medalBadgeStyle = MedalBadgeStyle.RANK_BARS
        appUserRepository.save(testUser)

        mockMvc.perform(
            post("/profile/badge-style")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("medalBadgeStyle", "INVALID_STYLE_CODE")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("badgeStyleErrorMsg", true))

        val reloaded = appUserRepository.findById(testUser.id!!).get()
        assertEquals(MedalBadgeStyle.RANK_BARS, reloaded.medalBadgeStyle)
    }

    @Test
    fun `user choice applies to own session and does not affect another user or app default`() {
        systemSettingService.updateSetting("medal_badge_style", "rank_bars")

        testUser.medalBadgeStyle = MedalBadgeStyle.MEDAL_SHAPES
        appUserRepository.save(testUser)

        val secondUser = appUserRepository.findByUsername("second_badge_user") ?: appUserRepository.save(AppUser().apply {
            username = "second_badge_user"
            displayName = "Second User"
            role = Role.USER
        })
        secondUser.medalBadgeStyle = null
        appUserRepository.save(secondUser)

        mockMvc.perform(
            get("/profile").with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-badge-style=\"medal_shapes\"")))

        mockMvc.perform(
            get("/profile").with(user(secondUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-badge-style=\"rank_bars\"")))

        systemSettingService.updateSetting("medal_badge_style", "ring_and_word")

        mockMvc.perform(
            get("/profile").with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-badge-style=\"medal_shapes\"")))

        mockMvc.perform(
            get("/profile").with(user(secondUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-badge-style=\"ring_and_word\"")))
    }
}
