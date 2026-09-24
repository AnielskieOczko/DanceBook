package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.DanceFigureWebController
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.io.File
import java.util.UUID

/**
 * Guards the responsive and accessible dense-table pattern in the figures catalog (Issue #132).
 *
 * Verifies:
 * 1. Scroll container with tabindex="0", role="region", aria-label="Figures".
 * 2. Pinned first column with table-header-pinned and table-cell-pinned on Name.
 * 3. 10 columns in order: Name, Style, Class, Timing, Position, Leader, Follower, Steps, Source, Actions.
 * 4. Sortable headers (Name, Style, Class) with aria-sort and sort toggle buttons.
 * 5. Missing values rendered as explicit dashes.
 * 6. Steps column displaying syllabus presence without per-row queries.
 * 7. Source column distinguishing predefined and custom figures.
 * 8. Row actions: Delete offered only on custom figures with proper confirmation; never on predefined figures.
 * 9. Responsive layout: mobile cards (lg:hidden) and dense table (hidden lg:block).
 */
@WebMvcTest(
    controllers = [DanceFigureWebController::class],
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
@Import(DanceFigureDenseTableTemplateTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceFigureDenseTableTemplateTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService

    // Global NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val templateFile = File("src/main/resources/templates/dance-figures/list.html")
    private val staticDoc by lazy {
        assertTrue(templateFile.isFile, "Template file not found at ${templateFile.absolutePath}")
        Jsoup.parse(templateFile.readText(), "", Parser.xmlParser())
    }

    @Test
    fun `static template defines focusable region with accessible name for dense table`() {
        val region = staticDoc.selectFirst("div.overflow-x-auto[role=region][aria-label='Figures']")
        assertNotNull(region, "Expected overflow-x-auto container with role='region' and aria-label='Figures'")
        assertEquals("0", region?.attr("tabindex"), "Region must have tabindex='0'")
        assertTrue(region?.hasClass("hidden") == true && region.hasClass("lg:block"), "Region must be hidden below desktop width (hidden lg:block)")
    }

    @Test
    fun `static template defines mobile cards container hidden on desktop`() {
        val mobileContainer = staticDoc.selectFirst("div.flex.flex-col.gap-3.lg\\:hidden")
        assertNotNull(mobileContainer, "Expected mobile cards container with lg:hidden")
    }

    @Test
    fun `rendered table has exact 10 columns in order`() {
        val figures = listOf(createSampleFigure(predefined = true))
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any())).thenReturn(figures)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val result = mockMvc.perform(get("/dance-figures").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val headers = doc.select("div[aria-label='Figures'] thead th").map { it.text().trim() }
        val headerNames = headers.map { text ->
            // Extract the plain text from sort buttons if present
            when {
                text.startsWith("Name") -> "Name"
                text.startsWith("Style") -> "Style"
                text.startsWith("Class") -> "Class"
                else -> text
            }
        }

        val expected = listOf(
            "Name", "Style", "Class", "Timing", "Position",
            "Leader", "Follower", "Steps", "Source", "Actions"
        )
        assertEquals(expected, headerNames, "Dense table columns must match expected 10 columns in order")
    }

    @Test
    fun `name column is pinned with table-header-pinned and table-cell-pinned`() {
        val figure = createSampleFigure(predefined = true)
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any())).thenReturn(listOf(figure))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val table = doc.selectFirst("div[aria-label='Figures'] table")
        assertNotNull(table)

        val firstTh = table?.selectFirst("thead th")
        assertTrue(firstTh?.hasClass("table-header-pinned") == true, "First <th> (Name) must have 'table-header-pinned'")
        assertTrue(firstTh?.hasClass("min-w-[200px]") == true, "First <th> must have bounded min width 'min-w-[200px]'")
        assertTrue(firstTh?.hasClass("max-w-[20rem]") == true, "First <th> must have bounded max width 'max-w-[20rem]'")
        assertFalse(firstTh?.hasClass("whitespace-nowrap") == true, "First <th> must allow wrapping (no 'whitespace-nowrap')")

        val firstRow = table?.selectFirst("tbody tr.table-row")
        assertNotNull(firstRow, "Table body row must have 'table-row' class")

        val firstTd = firstRow?.selectFirst("td")
        assertTrue(firstTd?.hasClass("table-cell-pinned") == true, "First <td> (Name) must have 'table-cell-pinned'")
        assertTrue(firstTd?.hasClass("min-w-[200px]") == true, "First <td> must have bounded min width 'min-w-[200px]'")
        assertTrue(firstTd?.hasClass("max-w-[20rem]") == true, "First <td> must have bounded max width 'max-w-[20rem]'")
        assertFalse(firstTd?.hasClass("whitespace-nowrap") == true, "First <td> must allow wrapping (no 'whitespace-nowrap')")
        assertFalse(firstTd?.className()?.contains("bg-") == true, "Pinned cell must not set static background; must inherit from table-row")

        val link = firstTd?.selectFirst("a")
        assertNotNull(link, "Name cell must contain link to figure view")
        assertEquals("/dance-figures/${figure.id}", link?.attr("href"))
        assertEquals("Natural Spin Turn", link?.text())
    }

    @Test
    fun `header sort state reflects selectedSortBy and sets correct aria-sort`() {
        val figure = createSampleFigure(predefined = true)
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any())).thenReturn(listOf(figure))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        // 1. Unsorted / default: all sortable headers aria-sort="none"
        var result = mockMvc.perform(get("/dance-figures").with(csrf())).andExpect(status().isOk).andReturn()
        var doc = Jsoup.parse(result.response.contentAsString)
        var table = doc.selectFirst("div[aria-label='Figures']")

        var ths = table?.select("thead th")
        assertEquals("none", ths?.get(0)?.attr("aria-sort"), "Name aria-sort must be none when unsorted")
        assertEquals("none", ths?.get(1)?.attr("aria-sort"), "Style aria-sort must be none when unsorted")
        assertEquals("none", ths?.get(2)?.attr("aria-sort"), "Class aria-sort must be none when unsorted")

        // 2. Sort by Name Ascending: Name aria-sort="ascending", data-sort-by="name_desc"
        result = mockMvc.perform(get("/dance-figures").param("sortBy", "name_asc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Figures']")
        ths = table?.select("thead th")
        assertEquals("ascending", ths?.get(0)?.attr("aria-sort"))
        assertEquals("none", ths?.get(1)?.attr("aria-sort"))
        assertEquals("none", ths?.get(2)?.attr("aria-sort"))
        val nameBtn = ths?.get(0)?.selectFirst("button.js-sort-header")
        assertEquals("name_desc", nameBtn?.attr("data-sort-by"))

        // 3. Sort by Name Descending: Name aria-sort="descending", data-sort-by="name_asc"
        result = mockMvc.perform(get("/dance-figures").param("sortBy", "name_desc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Figures']")
        ths = table?.select("thead th")
        assertEquals("descending", ths?.get(0)?.attr("aria-sort"))
        assertEquals("name_asc", ths?.get(0)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 4. Sort by Style Descending: Style aria-sort="descending"
        result = mockMvc.perform(get("/dance-figures").param("sortBy", "style_desc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Figures']")
        ths = table?.select("thead th")
        assertEquals("none", ths?.get(0)?.attr("aria-sort"))
        assertEquals("descending", ths?.get(1)?.attr("aria-sort"))
        assertEquals("style_asc", ths?.get(1)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 5. Sort by Class Ascending: Class aria-sort="ascending"
        result = mockMvc.perform(get("/dance-figures").param("sortBy", "class_asc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Figures']")
        ths = table?.select("thead th")
        assertEquals("none", ths?.get(0)?.attr("aria-sort"))
        assertEquals("none", ths?.get(1)?.attr("aria-sort"))
        assertEquals("ascending", ths?.get(2)?.attr("aria-sort"))
        assertEquals("class_desc", ths?.get(2)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))
    }

    @Test
    fun `steps and source values render correctly and delete is never offered on predefined rows`() {
        val predefinedFigId = UUID.randomUUID()
        val customFigId = UUID.randomUUID()

        val predefinedFigure = createSampleFigure(
            id = predefinedFigId,
            name = "Predefined Figure",
            predefined = true,
            timing = "123",
            startPos = "Closed Position",
            endPos = "Promenade Position",
            startFootL = "LF",
            endFootL = "RF",
            startFootF = "RF",
            endFootF = "LF"
        )
        val customFigure = createSampleFigure(
            id = customFigId,
            name = "Custom Figure",
            predefined = false,
            timing = null,
            startPos = null,
            endPos = null,
            startFootL = null,
            endFootL = null,
            startFootF = null,
            endFootF = null
        )

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(predefinedFigure, customFigure))
        // predefinedFigure has steps syllabus, customFigure does not
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList())))
            .thenReturn(setOf(predefinedFigId))

        val result = mockMvc.perform(get("/dance-figures").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val rows = doc.select("div[aria-label='Figures'] tbody tr.table-row")
        assertEquals(2, rows.size)

        // ── Predefined Row ──────────────────────────────────────────────
        val row1 = rows[0]
        val cells1 = row1.select("td")
        assertEquals("Predefined Figure", cells1[0].text().trim())
        assertEquals("Waltz", cells1[1].text().trim())
        assertEquals("D", cells1[2].text().trim())
        assertEquals("123", cells1[3].text().trim())
        assertTrue(cells1[4].text().contains("Closed Position"), "Position must contain start position")
        assertTrue(cells1[4].text().contains("Promenade Position"), "Position must contain end position")
        assertTrue(cells1[5].text().contains("LF") && cells1[5].text().contains("RF"), "Leader feet must show LF -> RF")
        assertTrue(cells1[6].text().contains("RF") && cells1[6].text().contains("LF"), "Follower feet must show RF -> LF")
        // Steps column has steps -> "Yes"
        assertTrue(cells1[7].text().contains("Yes"), "Steps column must render 'Yes' for figure with syllabus")
        // Source column predefined -> "Predefined"
        assertTrue(cells1[8].text().contains("Predefined"), "Source column must render 'Predefined'")
        // Actions: Edit is present, Delete form is ABSENT
        assertNotNull(cells1[9].selectFirst("a[href*='/edit']"), "Edit link must be present")
        assertNull(cells1[9].selectFirst("form[action*='/delete']"), "Delete form must NEVER be offered on predefined figure")

        // ── Custom Row ──────────────────────────────────────────────────
        val row2 = rows[1]
        val cells2 = row2.select("td")
        assertEquals("Custom Figure", cells2[0].text().trim())
        // Missing values must render as explicit dash
        assertEquals("-", cells2[3].text().trim(), "Missing timing must render as '-'")
        assertEquals("-", cells2[4].text().trim(), "Missing position must render as '-'")
        assertEquals("-", cells2[5].text().trim(), "Missing leader feet must render as '-'")
        assertEquals("-", cells2[6].text().trim(), "Missing follower feet must render as '-'")
        // Steps column has no steps -> "-"
        assertEquals("-", cells2[7].text().trim(), "Steps column must render '-' for figure without syllabus")
        // Source column custom -> "Custom"
        assertTrue(cells2[8].text().contains("Custom"), "Source column must render 'Custom'")
        // Actions: Edit is present, Delete form IS present with data-confirm
        assertNotNull(cells2[9].selectFirst("a[href*='/edit']"), "Edit link must be present")
        val deleteForm = cells2[9].selectFirst("form[action*='/delete']")
        assertNotNull(deleteForm, "Delete form must be present on custom figure")
        assertEquals(
            "Are you sure you want to delete this custom figure? This will also remove it from any mapped material sequences.",
            deleteForm?.attr("data-confirm")
        )
        val deleteBtn = deleteForm?.selectFirst("button[type=submit]")
        assertNotNull(deleteBtn, "Delete submit button must exist")
        assertTrue(deleteBtn?.hasClass("text-danger") == true, "Delete button must use text-danger token")
        assertTrue(deleteBtn?.hasClass("hover:bg-danger-soft") == true, "Delete button must use hover:bg-danger-soft token")
        assertFalse(deleteBtn?.className()?.contains("error-soft") == true, "Delete button must not use non-existent error-soft token")
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun assertNull(actual: Any?, message: String) {
        assertTrue(actual == null, message)
    }

    private fun createSampleFigure(
        id: UUID = UUID.randomUUID(),
        name: String = "Natural Spin Turn",
        predefined: Boolean = false,
        timing: String? = "123",
        startPos: String? = "Closed Position",
        endPos: String? = "Promenade Position",
        startFootL: String? = "LF",
        endFootL: String? = "RF",
        startFootF: String? = "RF",
        endFootF: String? = "LF"
    ): DanceFigure {
        val dt = DanceType().apply {
            this.id = UUID.randomUUID()
            this.name = "Waltz"
        }
        return DanceFigure().apply {
            this.id = id
            this.name = name
            this.danceType = dt
            this.danceClass = DanceClass.D
            this.predefined = predefined
            this.alternativeTiming = timing
            this.startingPosition = startPos
            this.endingPosition = endPos
            this.startingFootLeader = startFootL
            this.endingFootLeader = endFootL
            this.startingFootFollower = startFootF
            this.endingFootFollower = endFootF
        }
    }
}
