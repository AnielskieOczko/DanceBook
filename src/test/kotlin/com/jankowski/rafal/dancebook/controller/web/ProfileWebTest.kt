package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TargetType
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.ActivityEventRepository
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class ProfileWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var activityEventRepository: ActivityEventRepository

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        activityEventRepository.deleteAll()

        val existing = appUserRepository.findByUsername("profile_test_user")
        testUser = existing ?: appUserRepository.save(AppUser().apply {
            username = "profile_test_user"
            displayName = "Profile Test User"
            role = Role.USER
        })
        testUser.locale = null
        appUserRepository.save(testUser)
    }

    @Test
    fun `profile renders Direction C hero, badges, settings accordion rows and zero state`() {
        mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            // Hero
            .andExpect(content().string(containsString("Profile Test User")))
            .andExpect(content().string(containsString("Member since")))
            .andExpect(content().string(containsString("No active streak")))
            .andExpect(content().string(containsString("No training sessions this year")))
            // Real counts (zero state)
            .andExpect(content().string(containsString("Notes")))
            .andExpect(content().string(containsString("Choreographies")))
            .andExpect(content().string(containsString("Figures created")))
            // Badges row in accordion with dashed placeholder slots
            .andExpect(content().string(containsString("Badges")))
            .andExpect(content().string(containsString("0 of 0 earned")))
            .andExpect(content().string(containsString("Training streak")))
            .andExpect(content().string(containsString("Training hours")))
            .andExpect(content().string(containsString("Coming soon")))
            // No issue numbers in user-facing text
            .andExpect(content().string(not(containsString("(#35"))))
            .andExpect(content().string(not(containsString("(#39"))))
            .andExpect(content().string(not(containsString("(#309"))))
            .andExpect(content().string(not(containsString("#35,"))))
            .andExpect(content().string(not(containsString("??"))))
            // Settings accordion rows
            .andExpect(content().string(containsString("Account Settings")))
            .andExpect(content().string(containsString("Language")))
            .andExpect(content().string(containsString("Calendars")))
            .andExpect(content().string(containsString("Medal badge style")))
            .andExpect(content().string(containsString("Password")))
            .andExpect(content().string(containsString("Assistant")))
            // Frozen password fragment
            .andExpect(content().string(containsString("id=\"password-section\"")))
            // Recent activity
            .andExpect(content().string(containsString("Recent activity")))
    }

    @Test
    fun `updating default calendar via profile post updates default and redirects`() {
        val calendar = trainingCalendarRepository.save(TrainingCalendar().apply {
            displayName = "Personal Training Calendar"
            owner = testUser
            enabled = true
        })

        mockMvc.perform(
            post("/profile/default-calendar")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("id", calendar.id.toString())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))
            .andExpect(flash().attribute("calendarSuccess", "\"Personal Training Calendar\" is now your default calendar."))

        val reloadedUser = appUserRepository.findById(testUser.id!!).get()
        assertEquals(calendar.id, reloadedUser.defaultCalendar?.id)
    }

    @Test
    fun `locale redirect rejects open redirect attempts off-site and accepts safe local paths`() {
        // Attempt //evil.example
        mockMvc.perform(
            post("/profile/locale")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("locale", "en")
                .param("redirect", "//evil.example")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        // Attempt /\evil.example
        mockMvc.perform(
            post("/profile/locale")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("locale", "en")
                .param("redirect", "/\\evil.example")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        // Attempt backslash path
        mockMvc.perform(
            post("/profile/locale")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("locale", "en")
                .param("redirect", "\\evil.example")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        // Attempt control characters
        mockMvc.perform(
            post("/profile/locale")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("locale", "en")
                .param("redirect", "/profile\u0000evil")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/profile"))

        // Safe local path /materials
        mockMvc.perform(
            post("/profile/locale")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .param("locale", "en")
                .param("redirect", "/materials")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials"))
    }

    @Test
    fun `profile renders recent activity for user with multiple event types in English and Polish without missing keys`() {
        activityEventRepository.save(ActivityEvent().apply {
            eventType = EventType.MATERIAL_CREATED
            actor = testUser
            targetType = TargetType.MATERIAL
            targetName = "Waltz Basics"
            createdAt = LocalDateTime.now().minusDays(2)
        })
        activityEventRepository.save(ActivityEvent().apply {
            eventType = EventType.DANCE_FIGURE_UPDATED
            actor = testUser
            targetType = TargetType.DANCE_FIGURE
            targetName = "Natural Spin Turn"
            createdAt = LocalDateTime.now().minusDays(1)
        })
        activityEventRepository.save(ActivityEvent().apply {
            eventType = EventType.TRAINING_EVENT_CREATED
            actor = testUser
            targetType = TargetType.TRAINING_EVENT
            targetName = "Monday practice"
            createdAt = LocalDateTime.now()
        })

        // Another user's event that must NOT appear
        val otherUser = appUserRepository.save(AppUser().apply {
            username = "other_profile_user"
            displayName = "Other User"
            role = Role.USER
        })
        activityEventRepository.save(ActivityEvent().apply {
            eventType = EventType.MATERIAL_CREATED
            actor = otherUser
            targetType = TargetType.MATERIAL
            targetName = "Other User Note"
            createdAt = LocalDateTime.now()
        })

        // 1. English
        testUser.locale = "en"
        appUserRepository.save(testUser)

        val enHtml = mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Recent activity")))
            .andExpect(content().string(containsString("Created note")))
            .andExpect(content().string(containsString("Waltz Basics")))
            .andExpect(content().string(containsString("Updated figure")))
            .andExpect(content().string(containsString("Natural Spin Turn")))
            .andExpect(content().string(containsString("Scheduled training")))
            .andExpect(content().string(containsString("Monday practice")))
            .andExpect(content().string(not(containsString("Other User Note"))))
            .andExpect(content().string(not(containsString("??"))))
            .andExpect(content().string(not(containsString("(#35"))))
            .andExpect(content().string(not(containsString("(#39"))))
            .andExpect(content().string(not(containsString("(#309"))))
            .andReturn().response.contentAsString

        val enDoc = org.jsoup.Jsoup.parse(enHtml)
        val enRowTitles = enDoc.select("main details.group > summary b").map { it.text().trim() }
        assertEquals(listOf("Badges", "Calendars", "Language", "Medal badge style", "Assistant", "Password"), enRowTitles)

        // 2. Polish
        testUser.locale = "pl"
        appUserRepository.save(testUser)

        val plHtml = mockMvc.perform(
            get("/profile")
                .with(user(testUser.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Ostatnia aktywność")))
            .andExpect(content().string(containsString("Utworzono notatkę")))
            .andExpect(content().string(containsString("Waltz Basics")))
            .andExpect(content().string(containsString("Zaktualizowano figurę")))
            .andExpect(content().string(containsString("Natural Spin Turn")))
            .andExpect(content().string(containsString("Zaplanowano trening")))
            .andExpect(content().string(containsString("Monday practice")))
            .andExpect(content().string(not(containsString("Other User Note"))))
            .andExpect(content().string(not(containsString("??"))))
            .andExpect(content().string(not(containsString("(#35"))))
            .andExpect(content().string(not(containsString("(#39"))))
            .andExpect(content().string(not(containsString("(#309"))))
            .andReturn().response.contentAsString

        // Direction C row order verification in Polish:
        // 1. Badges (Odznaki), 2. Calendars (Kalendarze), 3. Language (Język),
        // 4. Medal badge style (Styl odznaki medalu), 5. Assistant (Asystent), 6. Password (Hasło)
        val plDoc = org.jsoup.Jsoup.parse(plHtml)
        val plRowTitles = plDoc.select("main details.group > summary b").map { it.text().trim() }
        assertEquals(listOf("Odznaki", "Kalendarze", "Język", "Styl odznaki medalu", "Asystent", "Hasło"), plRowTitles)
    }
}
