package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantNavSupport
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Locale
import java.util.UUID

/**
 * Verification test for Issue #228:
 * Avatar dropdown with Profile, language and Log out.
 */
@WebMvcTest(
    controllers = [
        ProfileController::class
    ],
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
@Import(
    RichTextServiceImpl::class,
    AvatarDropdownWebTest.SecurityTestConfig::class
)
class AvatarDropdownWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var assistantNavSupport: AssistantNavSupport
    @MockitoBean private lateinit var calendarSyncService: com.jankowski.rafal.dancebook.service.CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockitoBean private lateinit var trainingStatsService: TrainingStatsService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var choreographyService: ChoreographyService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService

    private lateinit var adminUser: AppUser
    private lateinit var regularUser: AppUser

    @BeforeEach
    fun setUp() {
        adminUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Admin Person"
            email = "admin@example.com"
            role = Role.ADMIN
            locale = "en"
        }

        regularUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "dancer"
            displayName = "Regular Dancer"
            email = "dancer@example.com"
            role = Role.USER
            locale = null
        }

        val emptyStats = TrainingStats(
            period = StatsPeriod.THIS_YEAR,
            totalMinutesTrained = 0,
            counts = SessionCounts(0, 0, 0, 0, 0),
            attendanceRatePercent = null,
            currentStreak = 0,
            byCategory = emptyList(),
            byEventType = emptyList()
        )
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_YEAR)).thenReturn(emptyStats)
        `when`(trainingCalendarService.findAllVisibleTo(anyNonNull(adminUser))).thenReturn(emptyList())
        `when`(activityEventService.getRecentEventsByActor(anyNonNull(adminUser), org.mockito.ArgumentMatchers.anyInt())).thenReturn(emptyList())
        `when`(customListService.findVisibleByCurrentUser()).thenReturn(emptyList())
        `when`(systemSettingService.getIntSetting("polling_interval_minutes", 5)).thenReturn(5)
        `when`(systemSettingService.getIntSetting("auto_logout_minutes", 10)).thenReturn(10)
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @AfterEach
    fun tearDown() {
        TestSecurityContextHolder.clearContext()
    }

    private fun authenticate(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(activityEventService.getUnreadCount(user.id!!)).thenReturn(0L)
        val principal = User(user.username, "password", listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))
    }

    @Test
    fun `avatar dropdown renders header block, profile, language and logout for admin`() {
        authenticate(adminUser)

        val result = mockMvc.perform(get("/profile").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val menu = doc.selectFirst("#profile-menu")
        assertNotNull(menu, "#profile-menu must exist")

        // Trigger is summary with initial
        val summary = menu!!.selectFirst("summary")
        assertNotNull(summary)
        assertEquals("A", summary!!.text().trim())
        assertEquals("Menu", summary.attr("aria-label"))

        // Header block: name and role, not a link
        val headerBlock = menu.selectFirst(".border-b.border-outline-variant")
        assertNotNull(headerBlock, "Header block must exist")
        assertEquals(0, headerBlock!!.select("a").size, "Header block must be information only, not a link")
        assertTrue(headerBlock.text().contains("Admin Person"))
        assertTrue(headerBlock.text().contains("Administrator"))

        // Phone shortcuts: admin user sees Admin link
        val phoneShortcuts = menu.selectFirst(".phone-shortcuts")
        assertNotNull(phoneShortcuts)
        assertTrue(phoneShortcuts!!.hasClass("xl:hidden"), "Phone shortcuts must be hidden at xl")
        val shortcutLinks = phoneShortcuts.select("a").map { it.attr("href") }
        assertTrue(shortcutLinks.contains("/choreographies"))
        assertTrue(shortcutLinks.contains("/lists"))
        assertTrue(shortcutLinks.contains("/admin"), "Admin shortcut must be present for admin role")

        // Profile link
        val profileLink = menu.selectFirst("a[href='/profile']")
        assertNotNull(profileLink)
        assertEquals("Profile", profileLink!!.select("span:last-child").text().trim())

        // Language row
        val langBtn = menu.selectFirst("button[data-langbtn]")
        assertNotNull(langBtn, "Language row button must exist")
        assertTrue(langBtn!!.text().contains("Language"))
        val currentLang = langBtn.selectFirst("[data-langval]")
        assertEquals("English", currentLang?.text()?.trim())

        // Language options
        val langs = menu.selectFirst("[data-langs]")
        assertNotNull(langs)
        val langOptions = langs!!.select("button[role='menuitemradio']")
        assertEquals(3, langOptions.size, "Must have English, Polski, Browser default")
        val checkedOption = langs.selectFirst("button[aria-checked='true']")
        assertTrue(checkedOption?.text()?.contains("English") == true)

        // Log out form
        val logoutForm = menu.selectFirst("form[action='/logout']")
        assertNotNull(logoutForm, "Log out form must exist in avatar dropdown")
        assertEquals("post", logoutForm!!.attr("method").lowercase())
        val logoutBtn = logoutForm.selectFirst("button")
        assertTrue(logoutBtn!!.hasClass("text-error"), "Log out button must be red / text-error")
        assertTrue(logoutBtn.text().contains("Log Out"))

        // Log out must NOT exist in the profile page body
        val profileBodyLogout = doc.select("main form[action='/logout']")
        assertEquals(0, profileBodyLogout.size, "Log out must be removed from profile page foot")
    }

    @Test
    fun `avatar dropdown hides admin shortcut for non-admin user`() {
        authenticate(regularUser)

        val result = mockMvc.perform(get("/profile").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val menu = doc.selectFirst("#profile-menu")
        assertNotNull(menu)

        // Header block shows Dance Enthusiast role
        val headerBlock = menu!!.selectFirst(".border-b.border-outline-variant")
        assertTrue(headerBlock!!.text().contains("Regular Dancer"))
        assertTrue(headerBlock.text().contains("Dance Enthusiast"))

        // Phone shortcuts: non-admin does NOT see Admin
        val phoneShortcuts = menu.selectFirst(".phone-shortcuts")
        val shortcutLinks = phoneShortcuts!!.select("a").map { it.attr("href") }
        assertTrue(shortcutLinks.contains("/choreographies"))
        assertTrue(shortcutLinks.contains("/lists"))
        assertFalse(shortcutLinks.contains("/admin"), "Admin shortcut must NOT appear for non-admin")

        // Language row shows Browser default when locale is null
        val langBtn = menu.selectFirst("button[data-langbtn]")
        val currentLang = langBtn!!.selectFirst("[data-langval]")
        assertEquals("Browser default", currentLang?.text()?.trim())
    }

    @Test
    fun `avatar dropdown renders in Polish with key parity`() {
        val polishUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tancerz"
            displayName = "Jan Kowalski"
            role = Role.USER
            locale = "pl"
        }
        authenticate(polishUser)

        val result = mockMvc.perform(get("/profile").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val menu = doc.selectFirst("#profile-menu")
        assertNotNull(menu)

        // Header block
        val headerBlock = menu!!.selectFirst(".border-b.border-outline-variant")
        assertTrue(headerBlock!!.text().contains("Jan Kowalski"))
        assertTrue(headerBlock.text().contains("Entuzjasta tańca"))

        // Profile link
        val profileLink = menu.selectFirst("a[href='/profile']")
        assertEquals("Profil", profileLink!!.select("span:last-child").text().trim())

        // Language row
        val langBtn = menu.selectFirst("button[data-langbtn]")
        assertTrue(langBtn!!.text().contains("Język"))
        val currentLang = langBtn.selectFirst("[data-langval]")
        assertEquals("Polski", currentLang?.text()?.trim())

        // Logout
        val logoutBtn = menu.selectFirst("form[action='/logout'] button")
        assertTrue(logoutBtn!!.text().contains("Wyloguj się"))
    }
}
