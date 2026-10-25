package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.CustomListWebController
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.*
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [CustomListWebController::class],
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
@Import(CustomListResponsiveTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class CustomListResponsiveTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var appUserService: AppUserService

    // NavbarAdvice & optional controller dependencies
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService

    private val currentUser = AppUser().apply {
        id = UUID.randomUUID()
        username = "list_user"
        displayName = "Dancer"
        role = Role.USER
    }

    private val sampleCat = DanceCategory().apply { id = UUID.randomUUID(); name = "Ballroom" }
    private val sampleType = DanceType().apply { id = UUID.randomUUID(); name = "Tango"; category = sampleCat }

    private fun customList(
        name: String = "Favorite Routines",
        isPublic: Boolean = true
    ) = CustomList().apply {
        id = UUID.randomUUID()
        this.name = name
        this.isPublic = isPublic
        this.danceCategories = mutableSetOf(sampleCat)
        this.danceTypes = mutableSetOf(sampleType)
        this.owner = currentUser
        this.minRating = 4
        this.createdAt = LocalDateTime.now().minusDays(1)
    }

    @BeforeEach
    fun setUp() {
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)
        `when`(customListService.findVisibleByCurrentUser()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(listOf(sampleCat))
        `when`(danceTypeService.findAll()).thenReturn(listOf(sampleType))
    }

    @Test
    fun `collections toolbar contains searchField, mobile and desktop filter buttons, and view toggle`() {
        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(listOf(customList()))

        val html = mockMvc.perform(get("/lists").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        // Search field with pill-input
        val searchInput = doc.selectFirst("#filterForm input[name=nameSearch]")
        assertNotNull(searchInput, "Search field must exist inside #filterForm")
        assertTrue(searchInput!!.hasClass("pill-input"), "Search input must have pill-input class")

        // Mobile filter button (< lg)
        val mobileFilterBtn = doc.selectFirst(".js-mobile-filter-open")
        assertNotNull(mobileFilterBtn, "Mobile filter open button must exist")
        assertTrue(mobileFilterBtn!!.hasClass("lg:hidden"), "Mobile filter button must be hidden on lg screens")
        assertEquals("filterSheet", mobileFilterBtn.attr("aria-controls"))

        // Desktop filter toggle (>= lg)
        val desktopFilterToggle = doc.selectFirst(".js-filter-toggle")
        assertNotNull(desktopFilterToggle, "Desktop filter toggle button must exist")
        assertTrue(desktopFilterToggle!!.hasClass("hidden"), "Desktop filter toggle must be hidden on small screens")

        // View toggle
        val viewRadios = doc.select("#filterForm input[name=view]")
        assertEquals(2, viewRadios.size, "View toggle must have list and grid radio inputs")

        // Mobile Filter Sheet dialog
        val sheetDialog = doc.selectFirst("dialog#filterSheet")
        assertNotNull(sheetDialog, "dialog#filterSheet must exist")
        assertNotNull(sheetDialog!!.selectFirst(".js-filter-sheet-clear"), "Filter sheet clear button must exist")
        assertNotNull(sheetDialog.selectFirst(".js-filter-sheet-apply"), "Filter sheet apply button must exist")
        assertNotNull(sheetDialog.selectFirst("input[data-sheet-filter='categoryIds']"), "Filter sheet category checkboxes must exist")
        assertNotNull(sheetDialog.selectFirst("input[data-sheet-filter='typeIds']"), "Filter sheet type checkboxes must exist")
        assertNotNull(sheetDialog.selectFirst("select[data-sheet-filter='sortBy']"), "Filter sheet sort select must exist")
    }

    @Test
    fun `active category filter displays removable chip on phone`() {
        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(listOf(customList()))

        val html = mockMvc.perform(get("/lists").param("categoryIds", sampleCat.id.toString()).with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        val chips = doc.select("#mobile-filter-chips .js-remove-chip")
        assertEquals(1, chips.size, "Active categoryIds filter must render a removable chip")
        val chip = chips.first()!!
        assertEquals("categoryIds", chip.attr("data-filter-name"))
        assertEquals(sampleCat.id.toString(), chip.attr("data-filter-value"))

        val badge = doc.selectFirst("#mobile-filter-badge")
        assertNotNull(badge)
        assertEquals("1", badge!!.text().trim())
    }

    @Test
    fun `renders dense table on desktop and compact rows on mobile with pagination footer`() {
        val cl = customList()
        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(listOf(cl))

        val html = mockMvc.perform(get("/lists").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        // Mobile compact row
        val mobileContainer = doc.selectFirst("#collections-mobile-cards")
        assertNotNull(mobileContainer, "Mobile cards container must exist")
        assertTrue(mobileContainer!!.hasClass("lg:hidden"), "Mobile cards must be hidden on lg screens")
        val compactRow = mobileContainer.selectFirst(".js-compact-row")
        assertNotNull(compactRow, "Compact row must exist")
        assertTrue(compactRow!!.hasClass("h-[76px]"), "Compact row must have standard 76px height")
        assertNotNull(compactRow.selectFirst(".js-menu-btn"), "Compact row must contain overflow menu trigger")

        // Desktop dense table
        val desktopTable = doc.selectFirst(".hidden.lg\\:block table")
        assertNotNull(desktopTable, "Desktop dense table must exist")
        val thCells = desktopTable!!.select("thead th")
        assertTrue(thCells.size >= 5, "Dense table must have headers for Title, Style, Category, Rating, Created, Actions")
        assertTrue(thCells.first()!!.hasClass("table-header-pinned"), "First th cell must be pinned")

        val tdCells = desktopTable.select("tbody tr td")
        assertTrue(tdCells.first()!!.hasClass("table-cell-pinned"), "First td cell must be pinned")

        // Pagination footer
        val footer = doc.selectFirst("#collections-footer")
        assertNotNull(footer, "Pagination footer must be present")
        assertTrue(footer!!.text().contains("1"), "Footer should report count")
    }
}
