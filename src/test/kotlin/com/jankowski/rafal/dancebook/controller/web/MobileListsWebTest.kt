package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
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
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * Tests for Mobile Lists (Issue #366):
 * Verifies compact rows, mobile toolbar with active filter count, removable chips,
 * and bottom filter sheet on both Figures and Notes lists.
 */
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
@Import(MobileListsWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MobileListsWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService
    @MockitoBean private lateinit var hybridSearchService: HybridSearchService
    @MockitoBean private lateinit var semanticSearchFilterHelper: SemanticSearchFilterHelper

    private val categoryStandard = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Standard"
    }
    private val typeWaltz = DanceType().apply {
        id = UUID.randomUUID()
        name = "English Waltz"
        category = categoryStandard
    }

    // ── Figures List Tests ──────────────────────────────────────────────────

    @Test
    fun `figures list mobile renders toolbar, filter button with badge, chips, compact rows and sheet`() {
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Spin Turn"
            danceType = typeWaltz
            danceClass = DanceClass.A
            medalLevel = MedalLevel.BRONZE
        }

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(figure)))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(setOf(figure.id!!))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/dance-figures?typeIds=${typeWaltz.id}&danceClass=A")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("activeFilterCount", 2))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // 1. Mobile Filter Button has active count badge with '2'
        val filterBtn = doc.selectFirst("button.js-mobile-filter-open")
        assertNotNull(filterBtn, "Mobile filter button must be present")
        val badge = filterBtn?.selectFirst("#mobile-filter-badge")
        assertNotNull(badge, "Active filter badge must be present")
        assertTrue(badge?.text()?.contains("2") == true, "Badge should display active filter count '2'")

        // 2. Removable chips are present in #mobile-filter-chips
        val chips = doc.select("#mobile-filter-chips .js-remove-chip")
        assertEquals(2, chips.size, "Should have 2 removable chips for typeIds and danceClass")
        val typeChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='typeIds'][data-filter-value='${typeWaltz.id}']")
        assertNotNull(typeChip, "Type chip with data-filter-name='typeIds' must be present")
        val classChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='danceClass'][data-filter-value='A']")
        assertNotNull(classChip, "Class chip with data-filter-name='danceClass' must be present")

        // 3. Compact row rendered for figure with hairline border, tap target, badges, and ⋯ menu
        val row = doc.selectFirst("#figures-mobile-cards article")
        assertNotNull(row, "Compact row article must be present in #figures-mobile-cards")
        assertTrue(row!!.hasClass("border-b"), "Row must have hairline divider (border-b)")
        assertTrue(row.hasClass("h-[72px]"), "Row must have fixed height 72px")
        val link = row.selectFirst("a[href='/dance-figures/${figure.id}']")
        assertNotNull(link, "Full-row tap link must point to figure view")
        assertTrue(row.text().contains("Natural Spin Turn"), "Row must display figure name")
        assertTrue(row.text().contains("English Waltz"), "Row must display style badge")
        assertTrue(row.text().contains("Class A"), "Row must display class badge")
        assertTrue(row.text().contains("Steps"), "Row must display steps flag badge")
        assertNotNull(row.selectFirst(".js-menu-btn"), "Row must have ⋯ overflow menu button")

        // 4. Native <dialog id='filterSheet'> is present with closedby='any'
        val sheet = doc.selectFirst("dialog#filterSheet")
        assertNotNull(sheet, "Filter bottom sheet dialog must be present")
        assertEquals("any", sheet?.attr("closedby"), "Dialog must have closedby='any'")
        assertNotNull(sheet?.selectFirst(".js-filter-sheet-clear"), "Clear button must be present when filters are active")
        val applyBtn = sheet?.selectFirst(".js-filter-sheet-apply")
        assertNotNull(applyBtn, "Apply button must be present in sheet footer")
        assertEquals("Apply filters", applyBtn?.text()?.trim())
    }

    @Test
    fun `figures list mobile without active filters shows zero count and no chips`() {
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(emptyList()))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(get("/dance-figures").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(model().attribute("activeFilterCount", 0))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val badge = doc.selectFirst("#mobile-filter-badge span")
        assertNull(badge, "Badge should not be rendered when activeFilterCount is 0")
        val chips = doc.select("#mobile-filter-chips .js-remove-chip")
        assertEquals(0, chips.size, "No chips should be rendered when no filters are active")
    }

    @Test
    fun `figures list htmx returns figuresTable with OOB swapped chips and badge`() {
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(emptyList()))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/dance-figures?typeIds=${typeWaltz.id}")
                .header("HX-Request", "true")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("id=\"mobile-filter-chips\""), "OOB swap container for mobile chips must be present")
        assertTrue(html.contains("hx-swap-oob=\"true\""), "hx-swap-oob must be present on chips")
        assertTrue(html.contains("id=\"mobile-filter-badge\""), "OOB swap for badge must be present")
        assertTrue(html.contains("English Waltz"), "Active chip for English Waltz must be present in OOB swap")
    }

    @Test
    fun `figures list in Polish locale renders localized chips and no doubled class name`() {
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Spin Turn"
            danceType = typeWaltz
            danceClass = DanceClass.F
            medalLevel = MedalLevel.BRONZE
        }

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(figure)))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(setOf(figure.id!!))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/dance-figures?danceClass=F&medalLevel=BRONZE&hasSteps=true")
                .locale(Locale.forLanguageTag("pl"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("activeFilterCount", 3))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Class F chip in Polish is "Klasa F", NOT "Class Class F"
        val classChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='danceClass'][data-filter-value='F']")
        assertNotNull(classChip, "Class F chip must be present")
        assertEquals("Klasa F", classChip?.parent()?.selectFirst("span")?.text()?.trim())

        // Medal level chip in Polish is "Brąz"
        val medalChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='medalLevel'][data-filter-value='BRONZE']")
        assertNotNull(medalChip, "Medal chip must be present")
        assertEquals("Brąz", medalChip?.parent()?.selectFirst("span")?.text()?.trim())

        // Steps chip in Polish is "Tylko z krokami"
        val stepsChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='hasSteps'][data-filter-value='true']")
        assertNotNull(stepsChip, "Steps chip must be present")
        assertEquals("Tylko z krokami", stepsChip?.parent()?.selectFirst("span")?.text()?.trim())

        // Apply button in Polish is "Zastosuj filtry" without count
        val sheet = doc.selectFirst("dialog#filterSheet")
        val applyBtn = sheet?.selectFirst(".js-filter-sheet-apply")
        assertEquals("Zastosuj filtry", applyBtn?.text()?.trim())
    }

    @Test
    fun `figures list in English locale renders Class F without doubling`() {
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(emptyList()))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/dance-figures?danceClass=F")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val classChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='danceClass'][data-filter-value='F']")
        assertNotNull(classChip, "Class F chip must be present")
        assertEquals("Class F", classChip?.parent()?.selectFirst("span")?.text()?.trim())
    }

    // ── Notes List Tests ────────────────────────────────────────────────────

    @Test
    fun `materials list mobile renders toolbar, chips, compact rows with stats and filter sheet`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            email = "dancer@example.com"
            username = "dancer"
            role = Role.USER
        }
        val note = Material().apply {
            id = UUID.randomUUID()
            name = "Rise and Fall Practice"
            danceType = typeWaltz
            rating = 5
            visibility = Visibility.PRIVATE
            createdAt = LocalDateTime.of(2026, 10, 14, 12, 0)
            owner = user
        }

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(note)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(mapOf(note.id!! to 3))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/materials?categoryIds=${categoryStandard.id}&minRating=4")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("activeFilterCount", 2))
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // 1. Mobile Filter Button has active count badge '2'
        val filterBtn = doc.selectFirst("button.js-mobile-filter-open")
        assertNotNull(filterBtn, "Mobile filter button must be present on Notes list")
        val badge = filterBtn?.selectFirst("#mobile-filter-badge")
        assertTrue(badge?.text()?.contains("2") == true, "Notes badge must show active count 2")

        // 2. Active filter chips
        val categoryChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='categoryIds'][data-filter-value='${categoryStandard.id}']")
        assertNotNull(categoryChip, "Category chip must be present")
        val ratingChip = doc.selectFirst("#mobile-filter-chips [data-filter-name='minRating'][data-filter-value='4']")
        assertNotNull(ratingChip, "Rating chip must be present")

        // 3. Compact row rendered for note with tags, stats line, and ⋯ menu
        val row = doc.selectFirst("#materials-mobile-cards article")
        assertNotNull(row, "Compact row must be present in #materials-mobile-cards")
        assertTrue(row!!.hasClass("border-b"), "Row must have hairline divider")
        assertTrue(row.hasClass("h-[88px]"), "Row must have fixed height 88px")
        val link = row.selectFirst("a[href='/materials/${note.id}']")
        assertNotNull(link, "Full-row tap link must point to note view")
        assertTrue(row.text().contains("Rise and Fall Practice"), "Row must display note title")
        assertTrue(row.text().contains("English Waltz"), "Row must display style badge")
        assertTrue(row.text().contains("Private"), "Row must display private badge")
        assertTrue(row.text().contains("5"), "Row must display rating 5")
        assertTrue(row.text().contains("3 figures"), "Row must display figure count")
        assertTrue(row.text().contains("14 Oct"), "Row must display formatted date")
        assertNotNull(row.selectFirst(".js-menu-btn"), "Row must have ⋯ overflow menu button")

        // 4. Notes filter bottom sheet dialog
        val sheet = doc.selectFirst("dialog#filterSheet")
        assertNotNull(sheet, "Notes filter sheet dialog must be present")
        assertEquals("any", sheet?.attr("closedby"), "Dialog must have closedby='any'")
        assertNotNull(sheet?.selectFirst(".js-filter-sheet-clear"), "Clear button must be present")
        assertNotNull(sheet?.selectFirst(".js-filter-sheet-apply"), "Apply button must be present")
    }

    @Test
    fun `materials list htmx returns materialsTable with OOB swapped chips and badge`() {
        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(emptyList()))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val result = mockMvc.perform(
            get("/materials?categoryIds=${categoryStandard.id}")
                .header("HX-Request", "true")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("id=\"mobile-filter-chips\""), "OOB swap container for mobile chips must be present in materialsTable")
        assertTrue(html.contains("hx-swap-oob=\"true\""), "hx-swap-oob must be present on chips")
        assertTrue(html.contains("id=\"mobile-filter-badge\""), "OOB swap for badge must be present in materialsTable")
        assertTrue(html.contains("Standard"), "Active chip for Standard must be present in OOB swap")
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }
}
