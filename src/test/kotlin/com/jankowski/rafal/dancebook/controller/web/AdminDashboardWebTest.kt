package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.AdminDriveFileDto
import com.jankowski.rafal.dancebook.dto.AdminOverview
import com.jankowski.rafal.dancebook.dto.AdminUsersView
import com.jankowski.rafal.dancebook.dto.DriveInspection
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * The admin dashboard of #395: a summary panel with "Needs attention", and rows that show a value and open in place.
 * Services are mocked, so these tests pin what the page renders from them; `AdminBadgeStyleWebTest` covers the
 * admin-only rule against the real security configuration.
 */
@WebMvcTest(
    controllers = [AdminController::class, AdminCalendarController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
        OAuth2ClientWebSecurityAutoConfiguration::class
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [SecurityConfig::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
@Import(RichTextServiceImpl::class, AdminDashboardWebTest.SecurityTestConfig::class)
class AdminDashboardWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var adminOverviewService: AdminOverviewService
    @MockitoBean private lateinit var adminStorageService: AdminStorageService
    @MockitoBean private lateinit var storageCleanupJob: StorageCleanupJob
    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var fileStorageService: FileStorageService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var syllabusImporterService: SyllabusImporterService
    @MockitoBean private lateinit var knowledgeIndexService: KnowledgeIndexService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var googleCalendarClient: GoogleCalendarClient

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val english = Locale.ENGLISH
    private lateinit var admin: AppUser

    @BeforeEach
    fun setUp() {
        admin = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Administrator"
            email = "admin@example.com"
            role = Role.ADMIN
        }
        `when`(appUserService.getCurrentUser()).thenReturn(admin)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(admin)
        `when`(systemSettingService.getIntSetting("polling_interval_minutes", 5)).thenReturn(2)
        `when`(systemSettingService.getIntSetting("auto_logout_minutes", 10)).thenReturn(30)
        `when`(trainingCalendarService.findAll()).thenReturn(emptyList())
        `when`(adminOverviewService.users(false, 5)).thenReturn(AdminUsersView(listOf(admin), 12L, 1L, false))
        `when`(adminOverviewService.users(true, 5)).thenReturn(AdminUsersView(listOf(admin), 12L, 1L, true))
        `when`(adminOverviewService.lastCleanupAt()).thenReturn(LocalDateTime.of(2026, 10, 20, 9, 0))
        overview()
    }

    private fun overview(
        stale: Boolean = false,
        status: KnowledgeIndexStatus? = KnowledgeIndexStatus(totalChunks = 10, embeddedChunks = 10, isStale = false),
        defaultCalendar: String? = "Primary Calendar"
    ) {
        `when`(adminOverviewService.overview(admin)).thenReturn(
            AdminOverview(
                userCount = 12L,
                figureCount = 374L,
                noteCount = 43L,
                calendarCount = 2,
                defaultCalendarName = defaultCalendar,
                danceTypeCount = 11,
                categoryCount = 4,
                categoryNames = listOf("Standard", "Latin", "Social", "Ballroom"),
                pollInterval = 2,
                autoLogout = 30,
                knowledgeStatus = status,
                knowledgeIndexStale = stale,
                lastCleanupAt = null
            )
        )
    }

    private fun dashboard() = Jsoup.parse(
        mockMvc.perform(get("/admin").with(csrf()).locale(english))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
    )

    private fun inspection(orphaned: Int, linked: Int = 1) = DriveInspection(
        files = List(orphaned + linked) { AdminDriveFileDto("f$it", "clip-$it.mp4", 1048576L, 0L, it < orphaned) },
        orphanedSize = orphaned * 1048576L,
        linkedSize = linked * 1048576L,
        orphanedCount = orphaned,
        linkedCount = linked
    )

    // ── Summary panel ─────────────────────────────────────────────────────────

    @Test
    fun `summary shows the counts of users, figures, notes and training calendars`() {
        val tiles = dashboard().select("#adminSummary b").eachText()
        assertEquals(listOf("12", "374", "43", "2"), tiles)
    }

    @Test
    fun `needs attention reads Nothing needs attention when there is no condition`() {
        val doc = dashboard()
        assertTrue(doc.select("#attentionChips [data-attention]").isEmpty(), "no chip should be present")
        assertEquals("Nothing needs attention", doc.selectFirst("[data-attention-none]")?.text())
    }

    @Test
    fun `a stale knowledge index shows its chip, which links to the row, and drops the none chip`() {
        overview(stale = true)
        val doc = dashboard()
        val chip = doc.selectFirst("#attentionChips a[data-attention]")
        assertEquals("Knowledge index out of date", chip?.ownText()?.trim())
        assertEquals("#admin-row-knowledge", chip?.attr("href"))
        assertTrue(doc.select("[data-attention-none]").isEmpty())
        assertTrue(doc.select("details#admin-row-knowledge").isNotEmpty(), "the row the chip links to must exist")
    }

    @Test
    fun `an incomplete knowledge index shows its own chip`() {
        overview(status = KnowledgeIndexStatus(totalChunks = 10, embeddedChunks = 4, isStale = false))
        val doc = dashboard()
        assertEquals("Knowledge index incomplete", doc.selectFirst("#attentionChips a[data-attention]")?.ownText()?.trim())
        assertTrue(doc.select("[data-attention-none]").isEmpty())
    }

    @Test
    fun `there is no unverified-users chip because users have no verified state`() {
        overview(stale = true)
        assertThat(dashboard().select("#attentionChips").text(), not(containsString("verified")))
    }

    // ── Rows ──────────────────────────────────────────────────────────────────

    @Test
    fun `rows are grouped and each shows its current value`() {
        val doc = dashboard()
        assertEquals(
            listOf("People", "Content", "Training", "Search and storage", "System"),
            doc.select("section > p.uppercase").eachText()
        )
        assertEquals("Accounts: 12 · Admins: 1", doc.selectFirst("#usersRowValue")?.text())
        assertEquals("Notifications every 2 min · auto-logout after 30 min", doc.selectFirst("#settingsRowValue")?.text())
        assertEquals("Up to date · 10 chunks", doc.selectFirst("details#admin-row-knowledge summary small")?.text())
        assertEquals("Calendars: 2 · Default: Primary Calendar", doc.selectFirst("details#admin-row-calendars summary small")?.text())
        assertEquals("Figures in the catalog: 374", doc.selectFirst("details#admin-row-syllabus summary small")?.text())
    }

    @Test
    fun `dance styles and categories rows link to the existing pages`() {
        val doc = dashboard()
        val styles = doc.selectFirst("a[href=/dance-types]")
        val categories = doc.selectFirst("a[href=/dance-categories]")
        assertEquals("Styles: 11 · Categories: 4", styles?.selectFirst("small")?.text())
        assertEquals("Standard, Latin, Social", categories?.selectFirst("small")?.text())
    }

    @Test
    fun `a row with nothing to show for a default calendar still reads cleanly`() {
        overview(defaultCalendar = null)
        assertEquals("Calendars: 2", dashboard().selectFirst("details#admin-row-calendars summary small")?.text())
    }

    @Test
    fun `the users row shows a link to all users when it is only showing the first few`() {
        val link = dashboard().selectFirst("#usersSection a[href=/admin/users]")
        assertEquals("All 12 users", link?.ownText()?.trim())
    }

    @Test
    fun `the full users page lists every user and links back to Admin`() {
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/users").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        assertEquals("Users", doc.selectFirst("h1 > span:last-child")?.text())
        assertEquals("/admin", doc.selectFirst("a:contains(Admin)")?.attr("href"))
        assertTrue(doc.select("#usersSection a[href=/admin/users]").isEmpty(), "the full page has no 'All users' link")
        assertEquals(1, doc.select("#usersSection tbody tr[id^=user-row-]").size)
    }

    @Test
    fun `creating an account from the full page re-renders the full list and refreshes the row value out of band`() {
        mockMvc.perform(
            post("/admin/users").with(csrf()).locale(english)
                .param("all", "true")
                .param("username", "newuser").param("email", "new@example.com")
                .param("displayName", "New User").param("password", "secret123").param("role", "USER")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Account created successfully!")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
            .andExpect(content().string(containsString("id=\"usersRowValue\"")))
        org.mockito.Mockito.verify(adminOverviewService).users(true, 5)
    }

    @Test
    fun `saving application settings refreshes the row value out of band`() {
        mockMvc.perform(
            post("/admin/settings/system").with(csrf()).locale(english)
                .param("pollInterval", "2").param("autoLogout", "30")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("System settings updated successfully!")))
            .andExpect(content().string(containsString("id=\"settingsRowValue\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
    }

    // ── Google Drive row: filled in after the page, because Drive is slow ─────

    @Test
    fun `the dashboard does not wait on Google Drive`() {
        dashboard()
        org.mockito.Mockito.verifyNoInteractions(googleDriveService)
        org.mockito.Mockito.verify(adminStorageService, org.mockito.Mockito.never()).inspectDrive()
    }

    @Test
    fun `the drive summary adds the orphaned chip and flag when files are orphaned`() {
        `when`(adminStorageService.inspectDrive()).thenReturn(inspection(orphaned = 14))
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/storage/summary").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        val chip = doc.selectFirst("a#driveAttention[data-attention]")
        assertEquals("Orphaned Drive files: 14", chip?.ownText()?.trim())
        assertEquals("#admin-row-drive", chip?.attr("href"))
        assertEquals("14 orphaned", doc.selectFirst("#driveRowFlag")?.text())
        assertTrue(doc.selectFirst("#driveRowValue")!!.text().contains("15.00 MB used"))
        assertTrue(doc.selectFirst("#driveRowValue")!!.text().contains("last cleanup Oct 20, 2026"))
    }

    @Test
    fun `the drive summary adds no chip when nothing is orphaned`() {
        `when`(adminStorageService.inspectDrive()).thenReturn(inspection(orphaned = 0))
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/storage/summary").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        assertTrue(doc.select("[data-attention]").isEmpty(), "no orphaned files, no chip")
        assertTrue(doc.selectFirst("#driveRowFlag")!!.text().isEmpty())
    }

    @Test
    fun `the drive summary says so when Google Drive cannot be reached, and adds no chip`() {
        `when`(adminStorageService.inspectDrive()).thenThrow(IllegalStateException("401"))
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/storage/summary").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        assertEquals("Google Drive could not be reached", doc.selectFirst("#driveRowValue")?.text())
        assertTrue(doc.select("[data-attention]").isEmpty())
    }

    @Test
    fun `the row preview shows the first few files and links to the rest`() {
        `when`(adminStorageService.inspectDrive()).thenReturn(inspection(orphaned = 3, linked = 5))
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/storage/files").param("preview", "true").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        assertEquals(5, doc.select("tbody tr.table-row").size)
        assertEquals("All 8 files", doc.selectFirst("a[data-all-files-link]")?.ownText()?.trim())
    }

    @Test
    fun `the full storage page lists every file and every cleanup run`() {
        `when`(adminStorageService.inspectDrive()).thenReturn(inspection(orphaned = 3, linked = 5))
        val doc = Jsoup.parse(
            mockMvc.perform(get("/admin/storage/files").with(csrf()).locale(english))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        )
        assertEquals(8, doc.select("tbody tr.table-row").size)
        assertTrue(doc.select("a[data-all-files-link]").isEmpty())

        mockMvc.perform(get("/admin/storage").with(csrf()).locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Cleanup Job History")))
            .andExpect(content().string(containsString("Simulate Cleanup")))
    }

    // ── Rules from AGENTS.md ──────────────────────────────────────────────────

    @Test
    fun `the admin controller reads no repository`() {
        val source = File("src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminController.kt").readText()
        assertTrue(!Regex("""\w+Repository""").containsMatchIn(source), "AdminController must go through services, not repositories")
    }

    private fun assertThat(actual: String, matcher: org.hamcrest.Matcher<String>) = org.hamcrest.MatcherAssert.assertThat(actual, matcher)
}
