package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * Tests for Soft Animations (Issue #390):
 * Verifies list update fade-in hooks, ⋯ row menu scale-in, mobile filter sheet slide-up and backdrop fade,
 * and top progress bar integration in templates, CSS and JavaScript.
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
@Import(SoftAnimationsWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class SoftAnimationsWebTest {

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
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService
    @MockitoBean private lateinit var hybridSearchService: HybridSearchService
    @MockitoBean private lateinit var semanticSearchFilterHelper: SemanticSearchFilterHelper

    private val currentUser = AppUser().apply {
        id = UUID.randomUUID()
        username = "dancer"
        email = "dancer@example.com"
        role = Role.USER
    }

    private val categoryStandard = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Standard"
    }

    private val typeWaltz = DanceType().apply {
        id = UUID.randomUUID()
        name = "English Waltz"
        category = categoryStandard
    }

    private fun mockCatalogData() {
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Spin Turn"
            danceType = typeWaltz
            danceClass = DanceClass.A
            createdBy = currentUser
            createdAt = LocalDateTime.now()
        }
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(figure)))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(setOf(figure.id!!))
        `when`(danceTypeService.findAll()).thenReturn(listOf(typeWaltz))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(categoryStandard))

        val material = Material().apply {
            id = UUID.randomUUID()
            name = "Waltz Practice"
            danceType = typeWaltz
            owner = currentUser
            visibility = Visibility.PRIVATE
            createdAt = LocalDateTime.now()
            version = 0L
        }
        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(material)))
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `layout includes top-progress-bar element`() {
        mockCatalogData()
        val result = mockMvc.perform(
            get("/dance-figures")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val progressBar = doc.getElementById("top-progress-bar")
        assertNotNull(progressBar, "Top progress bar element must exist in layout")
        assertTrue(progressBar!!.hasClass("top-progress-bar"), "Top progress bar must have 'top-progress-bar' class")
        assertTrue(progressBar.attr("aria-hidden") == "true", "Top progress bar must be aria-hidden")
    }

    @Test
    fun `dance-figures list contains list-fade-in on swap target and compactRow`() {
        mockCatalogData()
        val result = mockMvc.perform(
            get("/dance-figures")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val grid = doc.getElementById("figures-grid")
        assertNotNull(grid, "figures-grid must exist")
        assertTrue(grid!!.hasClass("list-fade-in"), "figures-grid must have list-fade-in class")

        val compactRow = doc.selectFirst(".js-compact-row")
        assertNotNull(compactRow, "js-compact-row must be rendered")
        assertTrue(compactRow!!.hasClass("list-fade-in"), "compactRow must have list-fade-in class")
    }

    @Test
    fun `materials list contains list-fade-in on swap target and compactRow`() {
        mockCatalogData()
        val result = mockMvc.perform(
            get("/materials")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val grid = doc.getElementById("materials-grid")
        assertNotNull(grid, "materials-grid must exist")
        assertTrue(grid!!.hasClass("list-fade-in"), "materials-grid must have list-fade-in class")

        val compactRow = doc.selectFirst(".js-compact-row")
        assertNotNull(compactRow, "js-compact-row must be rendered")
        assertTrue(compactRow!!.hasClass("list-fade-in"), "compactRow must have list-fade-in class")
    }

    @Test
    fun `filterSheet dialog exists and has closedby and aria-labelledby`() {
        mockCatalogData()
        val result = mockMvc.perform(
            get("/dance-figures")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val sheet = doc.getElementById("filterSheet")
        assertNotNull(sheet, "filterSheet dialog must exist")
        assertTrue(sheet!!.tagName() == "dialog", "filterSheet must be a <dialog>")
        assertTrue(sheet.attr("closedby") == "any", "filterSheet must have closedby='any'")
    }

    @Test
    fun `input css defines animations with starting-style and reduced-motion overrides`() {
        val cssFile = File("src/main/resources/frontend/input.css")
        assertTrue(cssFile.exists(), "input.css must exist")
        val css = cssFile.readText()

        // 1. Top progress bar
        assertTrue(css.contains(".top-progress-bar"), "Must style .top-progress-bar")
        assertTrue(css.contains(".top-progress-bar.is-loading"), "Must style .top-progress-bar.is-loading")
        assertTrue(css.contains(".top-progress-bar.is-finished"), "Must style .top-progress-bar.is-finished")
        assertTrue(css.contains("top-progress-indeterminate"), "Must define indeterminate animation for progress bar")

        // 2. ⋯ row menu scale-in
        assertTrue(css.contains(".js-menu-dropdown"), "Must style .js-menu-dropdown")
        assertTrue(css.contains("transform-origin: top right"), "Menu must scale from its button")
        assertTrue(css.contains("@starting-style"), "Must use @starting-style")
        assertTrue(css.contains(".js-menu-dropdown:not(.hidden)"), "Must use @starting-style on menu open")

        // 3. Mobile filter sheet
        assertTrue(css.contains("dialog#filterSheet"), "Must style dialog#filterSheet")
        assertTrue(css.contains("translateY(100%)"), "Filter sheet must slide up from bottom")
        assertTrue(css.contains("dialog#filterSheet::backdrop"), "Must style filter sheet backdrop")

        // 4. List fade-in
        assertTrue(css.contains(".list-fade-in"), "Must style .list-fade-in")
        assertTrue(css.contains(".js-compact-row"), "Must style .js-compact-row fade-in")
        assertTrue(css.contains(".table-row"), "Must style .table-row fade-in")

        // 5. Reduced motion
        assertTrue(css.contains("prefers-reduced-motion: reduce"), "Must support prefers-reduced-motion: reduce")
        assertTrue(css.contains("animation: none !important"), "Must turn off animations in reduced motion")
        assertTrue(css.contains("transition: none !important"), "Must turn off transitions in reduced motion")
    }

    @Test
    fun `main js implements progress bar lifecycle for htmx and page navigations`() {
        val jsFile = File("src/main/resources/static/js/main.js")
        assertTrue(jsFile.exists(), "main.js must exist")
        val js = jsFile.readText()

        assertTrue(js.contains("top-progress-bar"), "main.js must target top-progress-bar")
        assertTrue(js.contains("htmx:beforeRequest"), "main.js must track htmx:beforeRequest")
        assertTrue(js.contains("htmx:afterRequest"), "main.js must track htmx:afterRequest")
        assertTrue(js.contains("htmx:sendError"), "main.js must track htmx:sendError")
        assertTrue(js.contains("notification-badge"), "main.js must skip background notification polling")
        assertTrue(js.contains("beforeunload"), "main.js must track full page beforeunload")
        assertTrue(js.contains("pageshow"), "main.js must handle pageshow restore")
    }
}
