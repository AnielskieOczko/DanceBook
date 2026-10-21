package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.FigureSuggestionService
import com.jankowski.rafal.dancebook.service.HybridSearchService
import com.jankowski.rafal.dancebook.service.KnowledgeRetrievalService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SemanticSearchFilterHelper
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [DanceFigureWebController::class, MaterialWebController::class],
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
@Import(ListPaginationWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class ListPaginationWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService
    @MockitoBean private lateinit var hybridSearchService: HybridSearchService
    @MockitoBean private lateinit var semanticSearchFilterHelper: SemanticSearchFilterHelper
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val danceTypeWaltz = DanceType().apply { id = UUID.randomUUID(); name = "Walc angielski" }

    @BeforeEach
    fun setUp() {
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceTypeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun createFigure(index: Int): DanceFigure {
        return DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Figure $index"
            danceType = danceTypeWaltz
            danceClass = DanceClass.F
        }
    }

    private fun createNote(index: Int): Material {
        return Material().apply {
            id = UUID.randomUUID()
            name = "Note $index"
            danceType = danceTypeWaltz
            rating = 4
        }
    }

    // ────────────────────────────────────────────────────────────────
    // FIGURES LIST PAGINATION TESTS
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `figures list page 0 renders first page, total count, and show more button`() {
        val totalElements = 50L
        val page0Figures = (1..24).map { createFigure(it) }
        val page0 = PageImpl(page0Figures, PageRequest.of(0, 24), totalElements)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/list"))
            .andExpect(model().attribute("totalCount", 50L))
            .andExpect(model().attribute("shownCount", 24L))
            .andExpect(model().attribute("hasMore", true))
            .andExpect(model().attribute("nextPage", 1))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Subtitle shows total count (50), not page size (24)
        val subtitle = doc.selectFirst(".page-subtitle")?.text()
        assertTrue(subtitle?.contains("50") == true, "Subtitle should contain total elements 50, was: $subtitle")

        // Footer shows "Showing 24 of 50"
        val footer = doc.selectFirst("#figures-footer")
        assertNotNull(footer, "Pagination footer must be present")
        assertTrue(footer?.text()?.contains("Showing 24 of 50") == true, "Footer text should be 'Showing 24 of 50', was: ${footer?.text()}")

        // Show more button is present with next page URL
        val showMoreBtn = footer?.selectFirst("button[hx-get*='page=1']")
        assertNotNull(showMoreBtn, "Show more button targeting page=1 must be present")
        assertEquals("Show more", showMoreBtn?.text()?.trim())
    }

    @Test
    fun `figures list htmx page 1 renders middle page with more fragment and next page button`() {
        val totalElements = 50L
        val page1Figures = (25..48).map { createFigure(it) }
        val page1 = PageImpl(page1Figures, PageRequest.of(1, 24), totalElements)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(1, 24))))
            .thenReturn(page1)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures?page=1")
            .header("HX-Request", "true")
            .locale(Locale.ENGLISH)
            .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/list :: figuresMore"))
            .andExpect(model().attribute("totalCount", 50L))
            .andExpect(model().attribute("shownCount", 48L))
            .andExpect(model().attribute("hasMore", true))
            .andExpect(model().attribute("nextPage", 2))
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Footer is updated to "Showing 48 of 50"
        val footer = doc.selectFirst("#figures-footer")
        assertNotNull(footer, "Pagination footer must be present in figuresMore")
        assertTrue(footer?.text()?.contains("Showing 48 of 50") == true, "Footer should say 'Showing 48 of 50', was: ${footer?.text()}")

        // Button points to page=2
        val showMoreBtn = footer?.selectFirst("button[hx-get*='page=2']")
        assertNotNull(showMoreBtn, "Show more button targeting page=2 must be present")

        // OOB swaps present and rows preserved by HTML parser
        assertTrue(html.contains("hx-swap-oob=\"beforeend:#figures-table-body\""))
        assertTrue(html.contains("hx-swap-oob=\"beforeend:#figures-mobile-cards\""))
        val tableRows = doc.select("#figures-table-body tr")
        assertEquals(24, tableRows.size, "Browser/Jsoup HTML parser must retain all 24 table rows in OOB tbody")
        val mobileCards = doc.select("#figures-mobile-cards article")
        assertEquals(24, mobileCards.size, "Browser/Jsoup HTML parser must retain all 24 mobile cards in OOB div")
    }

    @Test
    fun `figures list htmx last page removes show more button and displays total`() {
        val totalElements = 50L
        val page2Figures = (49..50).map { createFigure(it) }
        val page2 = PageImpl(page2Figures, PageRequest.of(2, 24), totalElements)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(2, 24))))
            .thenReturn(page2)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures?page=2")
            .header("HX-Request", "true")
            .locale(Locale.ENGLISH)
            .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/list :: figuresMore"))
            .andExpect(model().attribute("totalCount", 50L))
            .andExpect(model().attribute("shownCount", 50L))
            .andExpect(model().attribute("hasMore", false))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val footer = doc.selectFirst("#figures-footer")
        assertNotNull(footer)
        assertTrue(footer?.text()?.contains("Showing 50 of 50") == true)

        // Button must NOT exist
        val btn = footer?.selectFirst("button")
        assertNull(btn, "Show more button should be gone on the last page")
    }

    @Test
    fun `figures list empty result renders empty state and no pagination footer`() {
        val emptyPage = PageImpl(emptyList<DanceFigure>(), PageRequest.of(0, 24), 0L)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(emptyPage)

        val result = mockMvc.perform(get("/dance-figures").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(model().attribute("totalCount", 0L))
            .andExpect(model().attribute("shownCount", 0L))
            .andExpect(model().attribute("hasMore", false))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val footer = doc.selectFirst("#figures-footer")
        assertNull(footer, "Footer should not render when 0 items exist")
    }

    @Test
    fun `figures list filter change without page param resets to page 0`() {
        val page0Figures = listOf(createFigure(1))
        val page0 = PageImpl(page0Figures, PageRequest.of(0, 24), 1L)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        mockMvc.perform(get("/dance-figures?danceClass=F")
            .header("HX-Request", "true")
            .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/list :: figuresTable"))
            .andExpect(model().attribute("nextPage", 1))
    }

    @Test
    fun `figures list in Polish locale renders Polish footer text and button`() {
        val totalElements = 412L
        val page0Figures = (1..24).map { createFigure(it) }
        val page0 = PageImpl(page0Figures, PageRequest.of(0, 24), totalElements)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val footer = doc.selectFirst("#figures-footer")
        assertNotNull(footer)
        assertTrue(footer?.text()?.contains("Pokazano 24 z 412") == true, "Footer text was: ${footer?.text()}")

        val btn = footer?.selectFirst("button")
        assertEquals("Pokaż więcej", btn?.text()?.trim())
    }

    // ────────────────────────────────────────────────────────────────
    // NOTES LIST PAGINATION TESTS
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `notes list page 0 renders first page, total count, and show more button`() {
        val totalElements = 128L
        val page0Notes = (1..24).map { createNote(it) }
        val page0 = PageImpl(page0Notes, PageRequest.of(0, 24), totalElements)

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(get("/materials").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/list"))
            .andExpect(model().attribute("totalCount", 128L))
            .andExpect(model().attribute("shownCount", 24L))
            .andExpect(model().attribute("hasMore", true))
            .andExpect(model().attribute("nextPage", 1))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Subtitle shows total count (128 notes)
        val subtitle = doc.selectFirst(".page-subtitle")?.text()
        assertTrue(subtitle?.contains("128") == true, "Subtitle should contain total elements 128, was: $subtitle")

        // Footer shows "Showing 24 of 128"
        val footer = doc.selectFirst("#materials-footer")
        assertNotNull(footer, "Materials pagination footer must be present")
        assertTrue(footer?.text()?.contains("Showing 24 of 128") == true, "Footer should say 'Showing 24 of 128', was: ${footer?.text()}")

        // Show more button is present targeting page=1
        val showMoreBtn = footer?.selectFirst("button[hx-get*='page=1']")
        assertNotNull(showMoreBtn)
        assertEquals("Show more", showMoreBtn?.text()?.trim())
    }

    @Test
    fun `notes list htmx page 1 renders middle page with materialsMore fragment`() {
        val totalElements = 128L
        val page1Notes = (25..48).map { createNote(it) }
        val page1 = PageImpl(page1Notes, PageRequest.of(1, 24), totalElements)

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(1, 24))))
            .thenReturn(page1)
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(get("/materials?page=1")
            .header("HX-Request", "true")
            .locale(Locale.ENGLISH)
            .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/list :: materialsMore"))
            .andExpect(model().attribute("totalCount", 128L))
            .andExpect(model().attribute("shownCount", 48L))
            .andExpect(model().attribute("hasMore", true))
            .andExpect(model().attribute("nextPage", 2))
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        val footer = doc.selectFirst("#materials-footer")
        assertNotNull(footer)
        assertTrue(footer?.text()?.contains("Showing 48 of 128") == true)

        val btn = footer?.selectFirst("button[hx-get*='page=2']")
        assertNotNull(btn)

        assertTrue(html.contains("hx-swap-oob=\"beforeend:#materials-table-body\""))
        assertTrue(html.contains("hx-swap-oob=\"beforeend:#materials-mobile-cards\""))
        val tableRows = doc.select("#materials-table-body tr")
        assertEquals(24, tableRows.size, "Browser/Jsoup HTML parser must retain all 24 table rows in OOB tbody")
        val mobileCards = doc.select("#materials-mobile-cards article")
        assertEquals(24, mobileCards.size, "Browser/Jsoup HTML parser must retain all 24 mobile cards in OOB div")
    }

    @Test
    fun `notes list htmx last page removes show more button and displays total`() {
        val totalElements = 25L
        val page1Notes = listOf(createNote(25))
        val page1 = PageImpl(page1Notes, PageRequest.of(1, 24), totalElements)

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(1, 24))))
            .thenReturn(page1)
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(get("/materials?page=1")
            .header("HX-Request", "true")
            .locale(Locale.ENGLISH)
            .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/list :: materialsMore"))
            .andExpect(model().attribute("totalCount", 25L))
            .andExpect(model().attribute("shownCount", 25L))
            .andExpect(model().attribute("hasMore", false))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val footer = doc.selectFirst("#materials-footer")
        assertNotNull(footer)
        assertTrue(footer?.text()?.contains("Showing 25 of 25") == true)
        assertNull(footer?.selectFirst("button"), "Button should not be present on last page")
    }

    @Test
    fun `notes list in Polish locale renders Polish footer text and button`() {
        val totalElements = 128L
        val page0Notes = (1..24).map { createNote(it) }
        val page0 = PageImpl(page0Notes, PageRequest.of(0, 24), totalElements)

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(get("/materials").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val footer = doc.selectFirst("#materials-footer")
        assertNotNull(footer)
        assertTrue(footer?.text()?.contains("Pokazano 24 z 128") == true)

        val btn = footer?.selectFirst("button")
        assertEquals("Pokaż więcej", btn?.text()?.trim())
    }

    @Test
    fun `grid view renders cards and pagination footer under grid`() {
        val totalElements = 50L
        val page0Figures = (1..24).map { createFigure(it) }
        val page0 = PageImpl(page0Figures, PageRequest.of(0, 24), totalElements)

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(page0)
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        val result = mockMvc.perform(get("/dance-figures?view=grid").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val gridItems = doc.selectFirst("#figures-grid-items")
        assertNotNull(gridItems, "#figures-grid-items must be present in grid view")
        val footer = doc.selectFirst("#figures-footer")
        assertNotNull(footer, "Footer must be present under grid")
    }
}
