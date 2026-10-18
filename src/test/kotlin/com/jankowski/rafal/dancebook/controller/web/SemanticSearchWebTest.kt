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
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SearchHit
import com.jankowski.rafal.dancebook.service.SearchHitType
import com.jankowski.rafal.dancebook.service.SearchMatch
import com.jankowski.rafal.dancebook.service.SemanticSearchFilterHelper
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [MaterialWebController::class, DanceFigureWebController::class],
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
@Import(SemanticSearchWebTest.TestConfig::class, RichTextServiceImpl::class, SemanticSearchFilterHelper::class)
class SemanticSearchWebTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var hybridSearchService: HybridSearchService

    // Global NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var testUser: AppUser
    private val waltzCategory = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val waltzType = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = waltzCategory }

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "test_user"
            displayName = "Test User"
            email = "test@example.com"
            role = Role.USER
            locale = "en"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)

        val principal = User("test_user", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))

        `when`(danceCategoryService.findAll()).thenReturn(listOf(waltzCategory))
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltzType))
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun createNote(title: String, rating: Int = 4): Material {
        return Material().apply {
            id = UUID.randomUUID()
            name = title
            danceType = waltzType
            this.rating = rating.toShort()
            createdAt = LocalDateTime.now()
            visibility = Visibility.PUBLIC
        }
    }

    private fun createFigure(name: String, danceClass: DanceClass = DanceClass.H): DanceFigure {
        return DanceFigure().apply {
            id = UUID.randomUUID()
            this.name = name
            danceType = waltzType
            this.danceClass = danceClass
            predefined = true
        }
    }

    @Test
    fun `notes search renders result count, divider, and related rows in English`() {
        val kwNote1 = createNote("Natural Spin Turn: head position")
        val kwNote2 = createNote("Contra Check, head and frame")
        val semNote1 = createNote("Keeping the left side long through the turn")
        val semNote2 = createNote("Rise and sway: where the weight sits")
        val semNote3 = createNote("Frame in closed position: top line")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(kwNote1, kwNote2)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("head dropping", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.NOTE, id = semNote1.id!!, match = SearchMatch.SEMANTIC, note = semNote1),
                SearchHit(type = SearchHitType.NOTE, id = semNote2.id!!, match = SearchMatch.SEMANTIC, note = semNote2),
                SearchHit(type = SearchHitType.NOTE, id = semNote3.id!!, match = SearchMatch.SEMANTIC, note = semNote3)
            )
        )

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "head dropping")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Count line
        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("2 results · 3 related for “head dropping”", count!!.text().trim())

        // Heading divider
        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNotNull(relatedHeading)
        assertEquals("Also related · 3", relatedHeading!!.text().trim())

        // Keyword row is not tinted
        val rows = doc.select("#materials-grid tr.table-row")
        assertEquals(5, rows.size)
        assertFalse(rows[0].hasClass("table-row-related"))
        assertFalse(rows[1].hasClass("table-row-related"))

        // Related rows have table-row-related and related badge
        assertTrue(rows[2].hasClass("table-row-related"))
        assertTrue(rows[3].hasClass("table-row-related"))
        assertTrue(rows[4].hasClass("table-row-related"))

        val relatedBadges = doc.select("#materials-grid .badge-related")
        assertTrue(relatedBadges.isNotEmpty())
        assertEquals("Related", relatedBadges.first()!!.text().trim())
    }

    @Test
    fun `notes search with 1 keyword result formats singular count`() {
        val kwNote = createNote("Natural Spin Turn")
        val semNote = createNote("Reverse Turn")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(kwNote)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("turn", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.NOTE, id = semNote.id!!, match = SearchMatch.SEMANTIC, note = semNote)
            )
        )

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "turn")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("1 result · 1 related for “turn”", count!!.text().trim())
    }

    @Test
    fun `notes search with keyword only formats keyword-only count without divider`() {
        val kwNote1 = createNote("Natural Spin Turn")
        val kwNote2 = createNote("Reverse Turn")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(kwNote1, kwNote2)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("turn", null)).thenReturn(emptyList())

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "turn")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("2 results for “turn”", count!!.text().trim())

        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNull(relatedHeading)
    }

    @Test
    fun `notes search with 0 keyword hits and 2 related hits renders empty keyword message and related group`() {
        val semNote1 = createNote("Keeping the left side long")
        val semNote2 = createNote("Rise and sway")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("shoulder lead", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.NOTE, id = semNote1.id!!, match = SearchMatch.SEMANTIC, note = semNote1),
                SearchHit(type = SearchHitType.NOTE, id = semNote2.id!!, match = SearchMatch.SEMANTIC, note = semNote2)
            )
        )

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "shoulder lead")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val emptyTitle = doc.selectFirst(".empty-state-title")
        assertNotNull(emptyTitle)
        assertEquals("No notes match “shoulder lead”", emptyTitle!!.text().trim())

        val emptyText = doc.selectFirst(".empty-state-text")
        assertNotNull(emptyText)
        assertEquals("Nothing contains those words, but 2 notes are related by meaning.", emptyText!!.text().trim())

        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNotNull(relatedHeading)
        assertEquals("Also related · 2", relatedHeading!!.text().trim())

        val rows = doc.select("#materials-grid tr.table-row")
        assertEquals(2, rows.size)
        assertTrue(rows[0].hasClass("table-row-related"))
    }

    @Test
    fun `notes search with 0 hits anywhere renders plain empty state with clear filters`() {
        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())
        `when`(hybridSearchService.search("zzxq", null)).thenReturn(emptyList())

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "zzxq")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val emptyTitle = doc.selectFirst(".empty-state-title")
        assertNotNull(emptyTitle)
        assertEquals("No notes match “zzxq”", emptyTitle!!.text().trim())

        val emptyText = doc.selectFirst(".empty-state-text")
        assertNotNull(emptyText)
        assertEquals("Try fewer words or check the style filter.", emptyText!!.text().trim())

        val clearBtn = doc.selectFirst(".empty-state a[href='/materials']")
        assertNotNull(clearBtn)
        assertEquals("Clear filters", clearBtn!!.text().trim())

        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNull(relatedHeading)
    }

    @Test
    fun `notes search fallback when hybrid search throws exception`() {
        val kwNote = createNote("Natural Spin Turn")
        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(kwNote)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())
        `when`(hybridSearchService.search("head dropping", null)).thenThrow(RuntimeException("Rate limit reached"))

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "head dropping")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("1 result for “head dropping”", count!!.text().trim())

        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNull(relatedHeading)
    }

    @Test
    fun `notes search renders Polish locale formatting`() {
        testUser.locale = "pl"
        val kwNote1 = createNote("Natural Spin Turn")
        val kwNote2 = createNote("Contra Check")
        val semNote1 = createNote("Keeping left side long")
        val semNote2 = createNote("Rise and sway")
        val semNote3 = createNote("Top line")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(kwNote1, kwNote2)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("turn", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.NOTE, id = semNote1.id!!, match = SearchMatch.SEMANTIC, note = semNote1),
                SearchHit(type = SearchHitType.NOTE, id = semNote2.id!!, match = SearchMatch.SEMANTIC, note = semNote2),
                SearchHit(type = SearchHitType.NOTE, id = semNote3.id!!, match = SearchMatch.SEMANTIC, note = semNote3)
            )
        )

        val result = mockMvc.perform(
            get("/materials")
                .locale(Locale.of("pl"))
                .param("nameSearch", "turn")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("Wyniki: 2, powiązane: 3 dla „turn”", count!!.text().trim())

        val relatedHeading = doc.selectFirst("#materials-grid .search-related-heading")
        assertNotNull(relatedHeading)
        assertEquals("Również powiązane · 3", relatedHeading!!.text().trim())

        val relatedChip = doc.selectFirst("#materials-grid .badge-related")
        assertNotNull(relatedChip)
        assertEquals("Powiązane", relatedChip!!.text().trim())
    }

    @Test
    fun `figures search renders count, divider, and related rows in English`() {
        val kwFig = createFigure("Natural Spin Turn")
        val semFig1 = createFigure("Reverse Turn")
        val semFig2 = createFigure("Closed Telemark")
        val semFig3 = createFigure("Hover Corte")

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(kwFig))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        `when`(hybridSearchService.search("turn", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.FIGURE, id = semFig1.id!!, match = SearchMatch.SEMANTIC, figure = semFig1),
                SearchHit(type = SearchHitType.FIGURE, id = semFig2.id!!, match = SearchMatch.SEMANTIC, figure = semFig2),
                SearchHit(type = SearchHitType.FIGURE, id = semFig3.id!!, match = SearchMatch.SEMANTIC, figure = semFig3)
            )
        )

        val result = mockMvc.perform(
            get("/dance-figures")
                .param("nameSearch", "turn")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val count = doc.selectFirst("#figures-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("1 result · 3 related for “turn”", count!!.text().trim())

        val relatedHeading = doc.selectFirst("#figures-grid .search-related-heading")
        assertNotNull(relatedHeading)
        assertEquals("Also related · 3", relatedHeading!!.text().trim())

        val rows = doc.select("#figures-grid tr.table-row")
        assertEquals(4, rows.size)
        assertFalse(rows[0].hasClass("table-row-related"))
        assertTrue(rows[1].hasClass("table-row-related"))
        assertTrue(rows[2].hasClass("table-row-related"))
        assertTrue(rows[3].hasClass("table-row-related"))

        val relatedBadges = doc.select("#figures-grid .badge-related")
        assertTrue(relatedBadges.isNotEmpty())
        assertEquals("Related", relatedBadges.first()!!.text().trim())
    }

    @Test
    fun `figures search renders Polish locale formatting`() {
        testUser.locale = "pl"
        val kwFig = createFigure("Natural Spin Turn")
        val semFig = createFigure("Reverse Turn")

        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(kwFig))
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())

        `when`(hybridSearchService.search("turn", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.FIGURE, id = semFig.id!!, match = SearchMatch.SEMANTIC, figure = semFig)
            )
        )

        val result = mockMvc.perform(
            get("/dance-figures")
                .locale(Locale.of("pl"))
                .param("nameSearch", "turn")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val count = doc.selectFirst("#figures-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("Wyniki: 1, powiązane: 1 dla „turn”", count!!.text().trim())

        val relatedHeading = doc.selectFirst("#figures-grid .search-related-heading")
        assertNotNull(relatedHeading)
        assertEquals("Również powiązane · 1", relatedHeading!!.text().trim())

        val relatedChip = doc.selectFirst("#figures-grid .badge-related")
        assertNotNull(relatedChip)
        assertEquals("Powiązane", relatedChip!!.text().trim())
    }

    @Test
    fun `figures search empty states and fallback`() {
        // 1. Keyword empty, semantic exists
        val semFig = createFigure("Reverse Turn")
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())
        `when`(danceFigureService.findFigureIdsWithSteps(anyNonNull(emptyList()))).thenReturn(emptySet())
        `when`(hybridSearchService.search("spin", null)).thenReturn(
            listOf(SearchHit(type = SearchHitType.FIGURE, id = semFig.id!!, match = SearchMatch.SEMANTIC, figure = semFig))
        )

        var result = mockMvc.perform(get("/dance-figures").param("nameSearch", "spin").with(csrf()))
            .andExpect(status().isOk).andReturn()
        var doc = Jsoup.parse(result.response.contentAsString)
        assertEquals("No figures match “spin”", doc.selectFirst(".empty-state-title")?.text()?.trim())
        assertEquals("Nothing contains those words, but 1 figures are related by meaning.", doc.selectFirst(".empty-state-text")?.text()?.trim())
        assertEquals("Also related · 1", doc.selectFirst("#figures-grid .search-related-heading")?.text()?.trim())

        // 2. Both empty
        `when`(hybridSearchService.search("zzxq", null)).thenReturn(emptyList())
        result = mockMvc.perform(get("/dance-figures").param("nameSearch", "zzxq").with(csrf()))
            .andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        assertEquals("No figures match “zzxq”", doc.selectFirst(".empty-state-title")?.text()?.trim())
        assertEquals("Try fewer words or check the style filter.", doc.selectFirst(".empty-state-text")?.text()?.trim())
        assertEquals("Clear filters", doc.selectFirst(".empty-state a[href='/dance-figures']")?.text()?.trim())

        // 3. Exception fallback
        val kwFig = createFigure("Natural Spin Turn")
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(kwFig))
        `when`(hybridSearchService.search("turn", null)).thenThrow(RuntimeException("AI service unavailable"))
        result = mockMvc.perform(get("/dance-figures").param("nameSearch", "turn").with(csrf()))
            .andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        assertEquals("1 result for “turn”", doc.selectFirst("#figures-grid p.text-sm.text-on-surface-variant")?.text()?.trim())
        assertNull(doc.selectFirst("#figures-grid .search-related-heading"))
    }

    @Test
    fun `notes search shows note marked BOTH in keyword list and never repeats it in related group`() {
        val bothNote = createNote("Dropping the head in the lowering")
        val semNote = createNote("Waltz keep head weight left")

        `when`(materialService.findAll(any(), any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(bothNote)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        `when`(hybridSearchService.search("head dropping", null)).thenReturn(
            listOf(
                SearchHit(type = SearchHitType.NOTE, id = bothNote.id!!, match = SearchMatch.BOTH, note = bothNote),
                SearchHit(type = SearchHitType.NOTE, id = semNote.id!!, match = SearchMatch.SEMANTIC, note = semNote)
            )
        )

        val result = mockMvc.perform(
            get("/materials")
                .param("nameSearch", "head dropping")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        // Count line shows 1 keyword result, 1 related
        val count = doc.selectFirst("#materials-grid p.text-sm.text-on-surface-variant")
        assertNotNull(count)
        assertEquals("1 result · 1 related for “head dropping”", count!!.text().trim())

        // 2 total rows: 1 keyword row (not tinted), 1 related row (tinted)
        val rows = doc.select("#materials-grid tr.table-row")
        assertEquals(2, rows.size)

        // First row is the BOTH note in the keyword list
        assertFalse(rows[0].hasClass("table-row-related"))
        assertTrue(rows[0].text().contains("Dropping the head in the lowering"))

        // Second row is the SEMANTIC note in the related group
        assertTrue(rows[1].hasClass("table-row-related"))
        assertTrue(rows[1].text().contains("Waltz keep head weight left"))
    }
}
