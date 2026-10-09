package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.config.UserLocaleResolver
import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantNav
import com.jankowski.rafal.dancebook.service.AssistantNavSupport
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.LocaleResolver
import java.util.UUID

@WebMvcTest(
    controllers = [
        TrainingEventWebController::class,
        NotificationController::class
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
    NavigationLocaleWebTest.SecurityTestConfig::class
)
class NavigationLocaleWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var assistantNavSupport: AssistantNavSupport

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "navadmin"
            displayName = "Admin User"
            email = "admin@example.com"
            role = Role.ADMIN
            locale = "en"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)

        val defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default Calendar"
            enabled = true
        }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(defaultCal))
        `when`(activeCalendarService.active()).thenReturn(null)
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val assistantNav = AssistantNav(PageContext(PageContextType.HOME, null), "Home")
        `when`(assistantNavSupport.forPath(org.mockito.ArgumentMatchers.anyString())).thenReturn(assistantNav)

        val principal = User("navadmin", "password", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    @Test
    fun `shell and navigation render in English when user locale is en`() {
        testUser.locale = "en"

        val result = mockMvc.perform(
            get("/training-events")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(testUser.username).roles("ADMIN"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Desktop nav links
        val desktopLinks = doc.select("header nav.hidden.xl\\:flex a")
        assertEquals(6, desktopLinks.size)
        val expectedDesktop = listOf(
            "/" to "Dashboard",
            "/materials" to "Notes",
            "/dance-figures" to "Figures",
            "/lists" to "Collections",
            "/choreographies" to "Choreographies",
            "/training-events" to "Training"
        )
        expectedDesktop.forEachIndexed { index, (href, text) ->
            assertEquals(href, desktopLinks[index].attr("href"))
            assertEquals(text, desktopLinks[index].text().trim())
        }

        // Mobile bottom nav links
        val mobileLinks = doc.select("nav.xl\\:hidden a")
        assertEquals(4, mobileLinks.size)
        val expectedMobile = listOf(
            "/" to "Home",
            "/training-events" to "Training",
            "/materials" to "Notes",
            "/dance-figures" to "Figures"
        )
        expectedMobile.forEachIndexed { index, (href, text) ->
            assertEquals(href, mobileLinks[index].attr("href"))
            assertEquals(text, mobileLinks[index].select("span.font-label-sm").text().trim())
        }

        // Header controls and accessibility labels
        val adminLink = doc.selectFirst("header a[href='/admin']")
        assertNotNull(adminLink)
        assertEquals("Admin Dashboard", adminLink!!.attr("aria-label"))
        assertEquals("Admin Dashboard", adminLink.attr("title"))

        val assistantBtn = doc.selectFirst("button[data-assistant-open]")
        assertNotNull(assistantBtn)
        assertEquals("Open assistant", assistantBtn!!.attr("aria-label"))
        assertEquals("Assistant (press /)", assistantBtn.attr("title"))

        val bellBtn = doc.selectFirst("#notification-bell")
        assertNotNull(bellBtn)
        assertEquals("Notifications", bellBtn!!.attr("aria-label"))
        assertEquals("Notifications", bellBtn.attr("title"))

        val profileLink = doc.selectFirst("header a[href='/profile']")
        assertNotNull(profileLink)
        assertEquals("Profile", profileLink!!.attr("aria-label"))

        val menuSummary = doc.selectFirst("#profile-menu summary")
        assertNotNull(menuSummary)
        assertEquals("Menu", menuSummary!!.attr("aria-label"))

        // Mobile profile menu items
        val menuLinks = doc.select("#profile-menu a")
        val menuTexts = menuLinks.map { it.select("span:last-child").text().trim() }
        assertTrue(menuTexts.contains("Profile"))
        assertTrue(menuTexts.contains("Choreographies"))
        assertTrue(menuTexts.contains("Collections"))
        assertTrue(menuTexts.contains("Admin"))

        // Training view switcher
        val switcher = doc.selectFirst("nav.view-switcher")
        assertNotNull(switcher)
        assertEquals("Training views", switcher!!.attr("aria-label"))
        val switcherLabels = switcher.select("a span:last-child").map { it.text().trim() }
        assertEquals(listOf("List", "Calendar", "Timeline", "Stats", "History"), switcherLabels)

        // Assistant widget launcher chrome
        val assistantInput = doc.selectFirst("#assistantBarInput")
        assertNotNull(assistantInput)
        assertEquals("Ask or tell DanceBook…", assistantInput!!.attr("placeholder"))
        assertEquals("Message the assistant", assistantInput.attr("aria-label"))

        val assistantTitle = doc.selectFirst("#assistantTitle")
        assertNotNull(assistantTitle)
        assertEquals("Assistant", assistantTitle!!.text().trim())
    }

    @Test
    fun `shell and navigation render in Polish when user locale is pl`() {
        testUser.locale = "pl"

        val result = mockMvc.perform(
            get("/training-events")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(testUser.username).roles("ADMIN"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Desktop nav links in Polish
        val desktopLinks = doc.select("header nav.hidden.xl\\:flex a")
        assertEquals(6, desktopLinks.size)
        val expectedDesktop = listOf(
            "/" to "Panel",
            "/materials" to "Notatki",
            "/dance-figures" to "Figury",
            "/lists" to "Kolekcje",
            "/choreographies" to "Choreografie",
            "/training-events" to "Trening"
        )
        expectedDesktop.forEachIndexed { index, (href, text) ->
            assertEquals(href, desktopLinks[index].attr("href"))
            assertEquals(text, desktopLinks[index].text().trim())
        }

        // Mobile bottom nav links in Polish
        val mobileLinks = doc.select("nav.xl\\:hidden a")
        assertEquals(4, mobileLinks.size)
        val expectedMobile = listOf(
            "/" to "Start",
            "/training-events" to "Trening",
            "/materials" to "Notatki",
            "/dance-figures" to "Figury"
        )
        expectedMobile.forEachIndexed { index, (href, text) ->
            assertEquals(href, mobileLinks[index].attr("href"))
            assertEquals(text, mobileLinks[index].select("span.font-label-sm").text().trim())
        }

        // Header controls and accessibility labels in Polish
        val adminLink = doc.selectFirst("header a[href='/admin']")
        assertNotNull(adminLink)
        assertEquals("Panel administratora", adminLink!!.attr("aria-label"))
        assertEquals("Panel administratora", adminLink.attr("title"))

        val assistantBtn = doc.selectFirst("button[data-assistant-open]")
        assertNotNull(assistantBtn)
        assertEquals("Otwórz asystenta", assistantBtn!!.attr("aria-label"))
        assertEquals("Asystent (naciśnij /)", assistantBtn.attr("title"))

        val bellBtn = doc.selectFirst("#notification-bell")
        assertNotNull(bellBtn)
        assertEquals("Powiadomienia", bellBtn!!.attr("aria-label"))
        assertEquals("Powiadomienia", bellBtn.attr("title"))

        val profileLink = doc.selectFirst("header a[href='/profile']")
        assertNotNull(profileLink)
        assertEquals("Profil", profileLink!!.attr("aria-label"))

        val menuSummary = doc.selectFirst("#profile-menu summary")
        assertNotNull(menuSummary)
        assertEquals("Menu", menuSummary!!.attr("aria-label"))

        // Mobile profile menu items in Polish
        val menuLinks = doc.select("#profile-menu a")
        val menuTexts = menuLinks.map { it.select("span:last-child").text().trim() }
        assertTrue(menuTexts.contains("Profil"))
        assertTrue(menuTexts.contains("Choreografie"))
        assertTrue(menuTexts.contains("Kolekcje"))
        assertTrue(menuTexts.contains("Administracja"))

        // Training view switcher in Polish
        val switcher = doc.selectFirst("nav.view-switcher")
        assertNotNull(switcher)
        assertEquals("Widoki treningów", switcher!!.attr("aria-label"))
        val switcherLabels = switcher.select("a span:last-child").map { it.text().trim() }
        assertEquals(listOf("Lista", "Kalendarz", "Oś czasu", "Statystyki", "Historia"), switcherLabels)

        // Assistant widget launcher chrome in Polish
        val assistantInput = doc.selectFirst("#assistantBarInput")
        assertNotNull(assistantInput)
        assertEquals("Zapytaj lub powiedz DanceBook…", assistantInput!!.attr("placeholder"))
        assertEquals("Wiadomość do asystenta", assistantInput.attr("aria-label"))

        val assistantTitle = doc.selectFirst("#assistantTitle")
        assertNotNull(assistantTitle)
        assertEquals("Asystent", assistantTitle!!.text().trim())
    }

    @Test
    fun `notification dropdown renders in Polish when locale is pl`() {
        testUser.locale = "pl"
        `when`(activityEventService.getUnreadEvents(testUser.id!!)).thenReturn(emptyList())
        `when`(activityEventService.getUnreadCount(testUser.id!!)).thenReturn(0)

        val result = mockMvc.perform(
            get("/notifications")
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        assertEquals("Powiadomienia", doc.selectFirst("h4")?.text()?.trim())
        assertEquals("Wszystko przeczytane!", doc.selectFirst("div.text-center p")?.text()?.trim())
        assertEquals("Zobacz całą aktywność", doc.selectFirst("a[href='/activity-history']")?.text()?.trim())
    }

    @Test
    fun `notification dropdown renders in English when locale is en`() {
        testUser.locale = "en"
        `when`(activityEventService.getUnreadEvents(testUser.id!!)).thenReturn(emptyList())
        `when`(activityEventService.getUnreadCount(testUser.id!!)).thenReturn(0)

        val result = mockMvc.perform(
            get("/notifications")
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        assertEquals("Notifications", doc.selectFirst("h4")?.text()?.trim())
        assertEquals("You're all caught up!", doc.selectFirst("div.text-center p")?.text()?.trim())
        assertEquals("View all activity", doc.selectFirst("a[href='/activity-history']")?.text()?.trim())
    }
}
