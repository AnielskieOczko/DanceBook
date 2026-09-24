package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingHistoryService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [
        TrainingEventWebController::class,
        TrainingTimelineWebController::class,
        TrainingStatsWebController::class,
        TrainingHistoryWebController::class
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
@Import(RichTextServiceImpl::class, TrainingNavAndSwitcherTest.SecurityTestConfig::class)
class TrainingNavAndSwitcherTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService

    @MockBean private lateinit var trainingTimelineService: TrainingTimelineService
    @MockBean private lateinit var trainingStatsService: TrainingStatsService
    @MockBean private lateinit var trainingHistoryService: TrainingHistoryService

    // NavbarAdvice collaborators
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    @ParameterizedTest(name = "Path {0} maps to training-events in activeNav")
    @ValueSource(
        strings = [
            "/training-events",
            "/training-events/calendar",
            "/training-events/timeline",
            "/training-events/stats",
            "/training-events/history",
            "/training-events/new",
            "/training-events/75b8e1f0-94cb-4d43-85e3-46c5a8984920",
            "/training-events/75b8e1f0-94cb-4d43-85e3-46c5a8984920/edit"
        ]
    )
    fun `NavbarAdvice maps every training path to training-events`(path: String) {
        val advice = NavbarAdvice(customListService, appUserService, activityEventService, systemSettingService)
        val request = MockHttpServletRequest("GET", path)

        assertEquals("training-events", advice.activeNav(request))
    }

    @Test
    fun `NavbarAdvice correctly maps other top-level routes`() {
        val advice = NavbarAdvice(customListService, appUserService, activityEventService, systemSettingService)

        assertEquals("home", advice.activeNav(MockHttpServletRequest("GET", "/")))
        assertEquals("materials", advice.activeNav(MockHttpServletRequest("GET", "/materials")))
        assertEquals("dance-figures", advice.activeNav(MockHttpServletRequest("GET", "/dance-figures")))
        assertEquals("lists", advice.activeNav(MockHttpServletRequest("GET", "/lists")))
        assertEquals("choreographies", advice.activeNav(MockHttpServletRequest("GET", "/choreographies")))
    }

    @Test
    fun `desktop and mobile nav show exactly six items in same order with same labels`() {
        val principal = User("dancer", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))

        val testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "dancer"
            displayName = "Dancer"
            email = "dancer@example.com"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val result = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Desktop nav
        val desktopLinks = doc.select("header nav.hidden.md\\:flex a")
        assertEquals(6, desktopLinks.size, "Desktop nav must contain exactly 6 links")

        val expectedDesktop = listOf(
            "/" to "Dashboard",
            "/materials" to "Notes",
            "/dance-figures" to "Figures",
            "/lists" to "Collections",
            "/choreographies" to "Choreographies",
            "/training-events" to "Training"
        )

        expectedDesktop.forEachIndexed { index, (expectedHref, expectedText) ->
            val link = desktopLinks[index]
            assertEquals(expectedHref, link.attr("href"))
            assertEquals(expectedText, link.text().trim())
        }

        // Training link is active
        val desktopTrainingLink = desktopLinks[5]
        assertTrue(desktopTrainingLink.hasClass("text-primary"))
        assertTrue(desktopTrainingLink.hasClass("bg-surface-container-high"))

        // Timeline and Profile are NOT in desktop nav
        assertFalse(desktopLinks.any { it.attr("href") == "/training-events/timeline" })
        assertFalse(desktopLinks.any { it.attr("href") == "/profile" })

        // Avatar still links to /profile
        val avatarLink = doc.selectFirst("header a[href='/profile']")
        assertNotNull(avatarLink, "Header avatar must link to /profile")

        // Mobile bottom nav
        val mobileLinks = doc.select("nav.md\\:hidden a")
        assertEquals(6, mobileLinks.size, "Mobile bottom nav must contain exactly 6 links")

        val expectedMobile = listOf(
            Triple("/", "Dashboard", "Dashboard"),
            Triple("/materials", "Notes", "Notes"),
            Triple("/dance-figures", "Figures", "Figures"),
            Triple("/lists", "Collections", "Collections"),
            Triple("/choreographies", "Choreos", "Choreographies"),
            Triple("/training-events", "Training", "Training")
        )

        expectedMobile.forEachIndexed { index, (expectedHref, expectedText, expectedAria) ->
            val link = mobileLinks[index]
            assertEquals(expectedHref, link.attr("href"))
            assertEquals(expectedText, link.select("span.font-label-sm").text().trim())
            if (expectedText != expectedAria) {
                assertEquals(expectedAria, link.attr("aria-label"), "Accessible name for $expectedText must be $expectedAria")
            }
            assertTrue(link.hasClass("min-h-[44px]"), "Mobile nav link $expectedText must have 44px min touch target")
        }

        // Mobile training link is active
        val mobileTrainingLink = mobileLinks[5]
        assertTrue(mobileTrainingLink.hasClass("bottom-nav-item-active"))
    }

    @Test
    fun `training list view renders shared switcher with list marked as active`() {
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val result = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        assertEquals("Training", doc.selectFirst("h1.page-title span")?.text()?.trim())

        val switcher = doc.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher nav must be present")

        assertSwitcherLinks(switcher!!, activeView = "list")

        // Primary action Add Session remains
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on list view")
    }

    @Test
    fun `training calendar view renders shared switcher with calendar marked as active`() {
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

        val result = mockMvc.perform(get("/training-events/calendar").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        assertEquals("Training", doc.selectFirst("h1.page-title span")?.text()?.trim())

        val switcher = doc.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher nav must be present")

        assertSwitcherLinks(switcher!!, activeView = "calendar")

        // Primary action Add Session remains
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on calendar view")
    }

    @Test
    fun `training timeline view renders shared switcher with timeline marked as active`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), any())).thenReturn(
            TrainingTimeline(months = emptyList(), hasMore = false, nextPage = 1, lastMonthLabel = null, isFirstPage = true)
        )

        val result = mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        assertEquals("Training", doc.selectFirst("h1.page-title span")?.text()?.trim())

        val switcher = doc.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher nav must be present")

        assertSwitcherLinks(switcher!!, activeView = "timeline")
    }

    @Test
    fun `training stats view renders shared switcher with stats marked as active and keeps period chips`() {
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(
            TrainingStats(
                period = StatsPeriod.ALL_TIME,
                counts = com.jankowski.rafal.dancebook.dto.SessionCounts(upcoming = 0, unconfirmed = 0, attended = 0, skipped = 0, cancelled = 0),
                totalMinutesTrained = 0,
                attendanceRatePercent = null,
                currentStreak = 0,
                byCategory = emptyList(),
                byEventType = emptyList()
            )
        )

        val result = mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        assertEquals("Training", doc.selectFirst("h1.page-title span")?.text()?.trim())

        val switcher = doc.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher nav must be present")

        assertSwitcherLinks(switcher!!, activeView = "stats")

        // Period chips preserved
        val periodChips = doc.select(".chip-group a.chip")
        assertFalse(periodChips.isEmpty(), "Period selector chips must be preserved on stats page")

        // Switcher icons must not inherit pageHeader title="Training"
        val switcherIcons = switcher.select("span.material-symbols-outlined")
        assertEquals(5, switcherIcons.size)
        switcherIcons.forEach { icon ->
            assertFalse(
                icon.hasAttr("title"),
                "Switcher icon '${icon.text()}' on stats page must not carry title='Training' from pageHeader"
            )
        }
    }

    @Test
    fun `training history view renders shared switcher with history marked as active`() {
        `when`(trainingHistoryService.historyForCurrentUser(any())).thenReturn(
            TrainingHistory(months = emptyList())
        )

        val result = mockMvc.perform(get("/training-events/history").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        assertEquals("Training", doc.selectFirst("h1.page-title span")?.text()?.trim())

        val switcher = doc.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher nav must be present")

        assertSwitcherLinks(switcher!!, activeView = "history")
    }

    private fun assertSwitcherLinks(switcher: org.jsoup.nodes.Element, activeView: String) {
        assertTrue(switcher.hasClass("view-switcher"), "Switcher nav must have view-switcher utility class")

        val links = switcher.select("a")
        assertEquals(5, links.size, "Switcher must have exactly 5 view links")

        val expected = listOf(
            "list" to ("/training-events" to "List"),
            "calendar" to ("/training-events/calendar" to "Calendar"),
            "timeline" to ("/training-events/timeline" to "Timeline"),
            "stats" to ("/training-events/stats" to "Stats"),
            "history" to ("/training-events/history" to "History")
        )

        expected.forEachIndexed { index, (viewKey, pair) ->
            val (expectedHref, expectedLabel) = pair
            val link = links[index]
            assertEquals(expectedHref, link.attr("href"))
            assertEquals(expectedLabel, link.select("span").last()?.text()?.trim())
            assertTrue(link.hasClass("view-switcher-item"), "Switcher link $expectedLabel must have view-switcher-item class")

            // Icon must carry no tooltip title
            val icon = link.selectFirst("span.material-symbols-outlined")
            assertNotNull(icon, "Switcher link $expectedLabel must have an icon")
            assertFalse(icon!!.hasAttr("title"), "Switcher link $expectedLabel icon must carry no title attribute")

            if (viewKey == activeView) {
                assertEquals("page", link.attr("aria-current"), "Active view link must have aria-current='page'")
                assertTrue(link.hasClass("view-switcher-item-active"), "Active view link must have view-switcher-item-active class")
                // Accent discipline: must NOT use text-primary or bg-primary
                assertFalse(link.hasClass("bg-primary"), "Active switcher link must not use bg-primary (accent)")
                assertFalse(link.hasClass("text-primary"), "Active switcher link must not use text-primary (accent)")
            } else {
                assertFalse(link.hasAttr("aria-current"), "Inactive view link $expectedLabel must not have aria-current")
                assertFalse(link.hasClass("view-switcher-item-active"), "Inactive view link must not have active class")
            }
        }
    }
}
