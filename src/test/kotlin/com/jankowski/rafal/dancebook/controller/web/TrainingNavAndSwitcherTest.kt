package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
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
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService

    @MockitoBean private lateinit var trainingTimelineService: TrainingTimelineService
    @MockitoBean private lateinit var trainingStatsService: TrainingStatsService
    @MockitoBean private lateinit var trainingHistoryService: TrainingHistoryService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "navuser"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        val defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default Calendar"
            enabled = true
        }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(defaultCal))
        `when`(activeCalendarService.active()).thenReturn(null)
    }

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
    fun `desktop nav shows six items and the phone tab bar shows four, with the rest behind the avatar menu`() {
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
        val desktopLinks = doc.select("header nav.hidden.xl\\:flex a")
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

        // Mobile bottom nav: four tabs since #147. Everything else is behind the avatar menu.
        val mobileLinks = doc.select("nav.xl\\:hidden a")
        assertEquals(4, mobileLinks.size, "Mobile bottom nav must contain exactly 4 links")

        val expectedMobile = listOf(
            "/" to "Home",
            "/training-events" to "Training",
            "/materials" to "Notes",
            "/dance-figures" to "Figures"
        )

        expectedMobile.forEachIndexed { index, (expectedHref, expectedText) ->
            val link = mobileLinks[index]
            assertEquals(expectedHref, link.attr("href"))
            assertEquals(expectedText, link.select("span.font-label-sm").text().trim())
            assertTrue(link.hasClass("min-h-[44px]"), "Mobile nav link $expectedText must have 44px min touch target")
        }

        // Mobile training link is active, and says so to assistive technology
        val mobileTrainingLink = mobileLinks[1]
        assertTrue(mobileTrainingLink.hasClass("bottom-nav-item-active"))
        assertEquals("page", mobileTrainingLink.attr("aria-current"))

        // The destinations that lost a tab stay reachable from the phone's profile menu
        val menuLinks = doc.select("#profile-menu a").map { it.attr("href") }
        assertTrue(menuLinks.containsAll(listOf("/profile", "/choreographies", "/lists")),
            "Profile menu must offer Profile, Choreographies and Collections, was $menuLinks")
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
    fun `training list view renders rounded search box with icon and pill calendar selector`() {
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val result = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val searchInput = doc.selectFirst("input[type='search'][name='search']")
        assertNotNull(searchInput, "Search input must be present")
        assertTrue(searchInput!!.hasClass("pill-input"), "Search input must have pill-input class (pill radius)")
        assertTrue(searchInput.hasClass("pl-11"), "Search input must have pl-11 for the search icon")

        val searchWrapper = searchInput.parent()
        assertNotNull(searchWrapper, "Search input wrapper must be present")
        val searchIcon = searchWrapper!!.selectFirst("span.material-symbols-outlined")
        assertNotNull(searchIcon, "Search icon must be present inside search input wrapper")

        val calSelector = doc.selectFirst(".btn-icon[href='/training-calendars']")
        assertNotNull(calSelector, "Calendar selector manage button must use btn-icon (pill)")
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

        // Primary action Add Session remains on desktop but is hidden on phone (floating button exists there)
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on calendar view")
        assertTrue(addSessionBtn!!.hasClass("hidden") && addSessionBtn.hasClass("md:inline-flex"),
            "Calendar view Add Session button must be hidden on mobile (< md)")
        val addSessionWrapper = addSessionBtn.parent()
        assertNotNull(addSessionWrapper)
        assertTrue(addSessionWrapper!!.hasClass("hidden") && addSessionWrapper.hasClass("md:block"),
            "Calendar view Add Session wrapper must be hidden on mobile (< md)")
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

        // Primary action Add Session remains
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on timeline view")
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

        // Primary action Add Session remains
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on stats view")

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

        // Primary action Add Session remains
        val addSessionBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addSessionBtn, "Add Session button must be preserved on history view")
    }

    @ParameterizedTest(name = "Path {0} renders Add Session in main row and switcher in secondary row")
    @ValueSource(
        strings = [
            "/training-events",
            "/training-events/calendar",
            "/training-events/timeline",
            "/training-events/stats",
            "/training-events/history"
        ]
    )
    fun `all five training views render tidy header with Add Session on right and switcher in subrow`(path: String) {
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), any())).thenReturn(
            TrainingTimeline(months = emptyList(), hasMore = false, nextPage = 1, lastMonthLabel = null, isFirstPage = true)
        )
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
        `when`(trainingHistoryService.historyForCurrentUser(any())).thenReturn(
            TrainingHistory(months = emptyList())
        )

        val result = mockMvc.perform(get(path).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // 1. Title on the left
        val title = doc.selectFirst("h1.page-title span")
        assertEquals("Training", title?.text()?.trim())

        // 2. Primary action Add Session exists with btn-primary
        val addBtn = doc.selectFirst("a.btn-primary[href='/training-events/new']")
        assertNotNull(addBtn, "Add Session button must be present on $path")
        if (path == "/training-events/calendar") {
            assertTrue(addBtn!!.hasClass("hidden") && addBtn.hasClass("md:inline-flex"),
                "Calendar view Add Session must be hidden on mobile (< md)")
            assertTrue(addBtn.parent()!!.hasClass("hidden") && addBtn.parent()!!.hasClass("md:block"),
                "Calendar view Add Session wrapper must be hidden on mobile (< md)")
        } else {
            assertFalse(addBtn!!.hasClass("hidden"), "Non-calendar views must keep Add Session visible on mobile: $path")
        }

        // 3. Add Session is in the header row, outside the secondary controls row
        val subrow = doc.selectFirst("nav[aria-label='Training views']")?.closest(".mt-4")
        assertNotNull(subrow, "Secondary subrow must be present")
        assertFalse(
            subrow!!.select("a.btn-primary[href='/training-events/new']").isNotEmpty(),
            "Add Session must not be placed inside the secondary subrow"
        )

        // 4. Controls row allows wrapping so switcher is not squeezed at 1024px
        val controlsRow = switcherControlsRow(subrow)
        assertTrue(controlsRow.hasClass("flex-wrap"), "Controls row must have flex-wrap to prevent clipping at 1024px")

        // 5. View switcher is in the subrow, and protected against shrinking at 1024px while allowing mobile scroll
        val switcher = subrow.selectFirst("nav[aria-label='Training views']")
        assertNotNull(switcher, "Switcher must be inside subrow")
        val switcherWrapper = switcher!!.parent()
        assertNotNull(switcherWrapper)
        assertTrue(switcherWrapper!!.hasClass("overflow-x-auto"), "Switcher wrapper must have overflow-x-auto for mobile")
        assertTrue(switcherWrapper.hasClass("min-w-0"), "Switcher wrapper must have min-w-0 for mobile")
        assertTrue(switcherWrapper.hasClass("sm:shrink-0"), "Switcher wrapper must have sm:shrink-0 to prevent shrinking at 1024px")
        assertTrue(switcherWrapper.hasClass("lg:overflow-visible"), "Switcher wrapper must have lg:overflow-visible to avoid desktop clipping")

        // 6. Calendar selector is also inside the subrow
        val calManage = subrow.selectFirst("a[href='/training-calendars']")
        assertNotNull(calManage, "Calendar manage link must be inside subrow")
    }

    private fun switcherControlsRow(subrow: org.jsoup.nodes.Element): org.jsoup.nodes.Element {
        val directDiv = subrow.selectFirst("> div")
        return directDiv ?: subrow
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
