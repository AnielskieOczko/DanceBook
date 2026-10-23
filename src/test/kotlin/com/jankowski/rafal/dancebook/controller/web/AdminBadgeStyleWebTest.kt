package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class AdminBadgeStyleWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var systemSettingService: SystemSettingService

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var adminUser: AppUser
    private lateinit var regularUser: AppUser

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        adminUser = appUserRepository.findByUsername("admin_badge_tester") ?: appUserRepository.save(AppUser().apply {
            username = "admin_badge_tester"
            displayName = "Admin Tester"
            role = Role.ADMIN
        })

        regularUser = appUserRepository.findByUsername("regular_badge_tester") ?: appUserRepository.save(AppUser().apply {
            username = "regular_badge_tester"
            displayName = "Regular Tester"
            role = Role.USER
        })

        systemSettingService.updateSetting("medal_badge_style", "rank_bars")
    }

    @Test
    fun `admin can view badge style select in system settings`() {
        mockMvc.perform(
            get("/admin")
                .with(user(adminUser.username).roles("ADMIN"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("adminMedalBadgeStyleSelect")))
    }

    @Test
    fun `admin can update app-wide badge style`() {
        mockMvc.perform(
            post("/admin/settings/system")
                .with(user(adminUser.username).roles("ADMIN"))
                .with(csrf())
                .param("pollInterval", "5")
                .param("autoLogout", "10")
                .param("medalBadgeStyle", "MEDAL_SHAPES")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("System settings updated successfully!")))

        assertEquals("medal_shapes", systemSettingService.getSetting("medal_badge_style", "rank_bars"))
    }

    @Test
    fun `non-admin cannot update app-wide badge style and gets 403`() {
        mockMvc.perform(
            post("/admin/settings/system")
                .with(user(regularUser.username).roles("USER"))
                .with(csrf())
                .param("pollInterval", "5")
                .param("autoLogout", "10")
                .param("medalBadgeStyle", "MEDAL_SHAPES")
        )
            .andExpect(status().isForbidden)

        assertEquals("rank_bars", systemSettingService.getSetting("medal_badge_style", "rank_bars"))
    }

    @Test
    fun `non-admin cannot access admin dashboard and gets 403`() {
        mockMvc.perform(
            get("/admin")
                .with(user(regularUser.username).roles("USER"))
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `non-admin gets 403 on every page and endpoint the admin dashboard opened up (#395)`() {
        for (uri in listOf("/admin/users", "/admin/storage", "/admin/storage/summary", "/admin/storage/files", "/admin/storage/files?preview=true")) {
            mockMvc.perform(get(uri).with(user(regularUser.username).roles("USER")))
                .andExpect(status().isForbidden)
        }
        mockMvc.perform(
            post("/admin/users")
                .with(user(regularUser.username).roles("USER"))
                .with(csrf())
                .param("username", "intruder").param("email", "i@example.com")
                .param("displayName", "Intruder").param("password", "secret123").param("role", "ADMIN")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin can open the full users page and the full storage page`() {
        mockMvc.perform(get("/admin/users").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("usersSection")))
        mockMvc.perform(get("/admin/storage").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/admin/storage/simulate")))
    }

    @Test
    fun `the dashboard lists only the first few users and the users page lists all of them`() {
        for (i in 1..7) {
            if (appUserRepository.findByUsername("preview_user_$i") == null) {
                appUserRepository.save(AppUser().apply {
                    username = "preview_user_$i"
                    displayName = "Preview User $i"
                    role = Role.USER
                })
            }
        }
        val total = appUserRepository.count()

        val dashboard = mockMvc.perform(get("/admin").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("All $total users")))
            .andReturn().response.contentAsString
        val shown = Regex("""id="user-row-""").findAll(dashboard).count()
        assertEquals(5, shown)

        val page = mockMvc.perform(get("/admin/users").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertEquals(total.toInt(), Regex("""id="user-row-""").findAll(page).count())
    }
}
