package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.MaterialWebController
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
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
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Guards the responsive and accessible dense-table pattern in the notes list (Issue #143).
 *
 * Verifies:
 * 1. Scroll container with tabindex="0", role="region", aria-label="Notes".
 * 2. Pinned first column with table-header-pinned and table-cell-pinned on Title.
 * 3. 8 columns in order: Title, Style, Category, Rating, Figures, Media, Created, Actions.
 * 4. Sortable headers (Title, Rating, Created) with aria-sort and sort toggle buttons.
 * 5. Missing values rendered as explicit dashes.
 * 6. Figures and Media columns display without per-row queries.
 * 7. Row actions: Edit and Delete with confirmation offered only to the note's owner or admin.
 * 8. Responsive layout: mobile cards (lg:hidden) and dense table (hidden lg:block).
 * 9. Wording: Notes (not Movements Library), 12 notes (singular for one), New note, No notes found.
 */
@WebMvcTest(
    controllers = [MaterialWebController::class],
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
@Import(MaterialDenseTableTemplateTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialDenseTableTemplateTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var appUserService: AppUserService

    // Global NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val templateFile = File("src/main/resources/templates/materials/list.html")
    private val staticDoc by lazy {
        assertTrue(templateFile.isFile, "Template file not found at ${templateFile.absolutePath}")
        Jsoup.parse(templateFile.readText(), "", Parser.xmlParser())
    }

    @Test
    fun `static template defines focusable region with accessible name for dense table`() {
        val region = staticDoc.selectFirst("div.overflow-x-auto[role=region][aria-label='Notes']")
        assertNotNull(region, "Expected overflow-x-auto container with role='region' and aria-label='Notes'")
        assertEquals("0", region?.attr("tabindex"), "Region must have tabindex='0'")
        assertTrue(region?.hasClass("hidden") == true && region.hasClass("lg:block"), "Region must be hidden below desktop width (hidden lg:block)")
    }

    @Test
    fun `static template defines mobile cards container hidden on desktop`() {
        val mobileContainer = staticDoc.selectFirst("div.flex.flex-col.gap-3.lg\\:hidden")
        assertNotNull(mobileContainer, "Expected mobile cards container with lg:hidden")
    }

    @Test
    fun `rendered table has exact 8 columns in order`() {
        val material = createSampleMaterial()
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(material)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(mapOf(material.id!! to 2))
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val result = mockMvc.perform(get("/materials").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val headers = doc.select("div[aria-label='Notes'] thead th").map { it.text().trim() }
        val headerNames = headers.map { text ->
            when {
                text.startsWith("Title") -> "Title"
                text.startsWith("Rating") -> "Rating"
                text.startsWith("Created") -> "Created"
                else -> text
            }
        }

        val expected = listOf(
            "Title", "Style", "Category", "Rating", "Figures", "Media", "Created", "Actions"
        )
        assertEquals(expected, headerNames, "Dense table columns must match expected 8 columns in order")
    }

    @Test
    fun `title column is pinned with table-header-pinned and table-cell-pinned`() {
        val material = createSampleMaterial()
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(material)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(mapOf(material.id!! to 2))

        val result = mockMvc.perform(get("/materials").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val table = doc.selectFirst("div[aria-label='Notes'] table")
        assertNotNull(table)

        val firstTh = table?.selectFirst("thead th")
        assertTrue(firstTh?.hasClass("table-header-pinned") == true, "First <th> (Title) must have 'table-header-pinned'")
        assertTrue(firstTh?.hasClass("min-w-[200px]") == true, "First <th> must have bounded min width 'min-w-[200px]'")
        assertTrue(firstTh?.hasClass("max-w-[20rem]") == true, "First <th> must have bounded max width 'max-w-[20rem]'")
        assertFalse(firstTh?.hasClass("whitespace-nowrap") == true, "First <th> must allow wrapping (no 'whitespace-nowrap')")

        val firstRow = table?.selectFirst("tbody tr.table-row")
        assertNotNull(firstRow, "Table body row must have 'table-row' class")

        val firstTd = firstRow?.selectFirst("td")
        assertTrue(firstTd?.hasClass("table-cell-pinned") == true, "First <td> (Title) must have 'table-cell-pinned'")
        assertTrue(firstTd?.hasClass("min-w-[200px]") == true, "First <td> must have bounded min width 'min-w-[200px]'")
        assertTrue(firstTd?.hasClass("max-w-[20rem]") == true, "First <td> must have bounded max width 'max-w-[20rem]'")
        assertFalse(firstTd?.hasClass("whitespace-nowrap") == true, "First <td> must allow wrapping (no 'whitespace-nowrap')")
        assertFalse(firstTd?.className()?.contains("bg-") == true, "Pinned cell must not set static background; must inherit from table-row")

        val link = firstTd?.selectFirst("a")
        assertNotNull(link, "Title cell must contain link to material view")
        assertEquals("/materials/${material.id}", link?.attr("href"))
        assertEquals("Samba Routine", link?.text())
    }

    @Test
    fun `header sort state reflects currentSort and sets correct aria-sort`() {
        val material = createSampleMaterial()
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(material)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        // 1. Default sort: createdAt,desc -> Created aria-sort="descending", Title/Rating "none"
        var result = mockMvc.perform(get("/materials").with(csrf())).andExpect(status().isOk).andReturn()
        var doc = Jsoup.parse(result.response.contentAsString)
        var table = doc.selectFirst("div[aria-label='Notes']")

        var ths = table?.select("thead th")
        assertEquals("none", ths?.get(0)?.attr("aria-sort"), "Title aria-sort must be none by default")
        assertEquals("none", ths?.get(3)?.attr("aria-sort"), "Rating aria-sort must be none by default")
        assertEquals("descending", ths?.get(6)?.attr("aria-sort"), "Created aria-sort must be descending by default")
        assertEquals("createdAt,asc", ths?.get(6)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 2. Sort by Title Ascending: Title aria-sort="ascending", data-sort-by="name,desc"
        result = mockMvc.perform(get("/materials").param("sort", "name,asc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Notes']")
        ths = table?.select("thead th")
        assertEquals("ascending", ths?.get(0)?.attr("aria-sort"))
        assertEquals("name,desc", ths?.get(0)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))
        assertEquals("none", ths?.get(3)?.attr("aria-sort"))
        assertEquals("none", ths?.get(6)?.attr("aria-sort"))

        // 3. Sort by Title Descending: Title aria-sort="descending", data-sort-by="name,asc"
        result = mockMvc.perform(get("/materials").param("sort", "name,desc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Notes']")
        ths = table?.select("thead th")
        assertEquals("descending", ths?.get(0)?.attr("aria-sort"))
        assertEquals("name,asc", ths?.get(0)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 4. Sort by Rating Descending: Rating aria-sort="descending", data-sort-by="rating,asc"
        result = mockMvc.perform(get("/materials").param("sort", "rating,desc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Notes']")
        ths = table?.select("thead th")
        assertEquals("descending", ths?.get(3)?.attr("aria-sort"))
        assertEquals("rating,asc", ths?.get(3)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 5. Sort by Rating Ascending: Rating aria-sort="ascending", data-sort-by="rating,desc"
        result = mockMvc.perform(get("/materials").param("sort", "rating,asc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Notes']")
        ths = table?.select("thead th")
        assertEquals("ascending", ths?.get(3)?.attr("aria-sort"))
        assertEquals("rating,desc", ths?.get(3)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))

        // 6. Sort by Created Ascending: Created aria-sort="ascending", data-sort-by="createdAt,desc"
        result = mockMvc.perform(get("/materials").param("sort", "createdAt,asc").with(csrf())).andExpect(status().isOk).andReturn()
        doc = Jsoup.parse(result.response.contentAsString)
        table = doc.selectFirst("div[aria-label='Notes']")
        ths = table?.select("thead th")
        assertEquals("ascending", ths?.get(6)?.attr("aria-sort"))
        assertEquals("createdAt,desc", ths?.get(6)?.selectFirst("button.js-sort-header")?.attr("data-sort-by"))
    }

    @Test
    fun `table renders missing values as dash and media badges correctly`() {
        val viewer = AppUser().apply { id = UUID.randomUUID(); username = "viewer"; role = Role.USER }

        val completeNote = createSampleMaterial(
            id = UUID.randomUUID(),
            name = "Complete Note",
            rating = 5,
            videoLink = "https://youtube.com/watch?v=123",
            sourceLink = "https://instagram.com/p/123",
            driveFileId = "drive-123",
            isPublic = false
        ).apply { owner = viewer }

        val minimalNote = createSampleMaterial(
            id = UUID.randomUUID(),
            name = "Minimal Note",
            rating = null,
            videoLink = null,
            sourceLink = null,
            driveFileId = null,
            danceType = null,
            isPublic = true
        ).apply { owner = AppUser().apply { id = UUID.randomUUID() } }

        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(completeNote, minimalNote)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList())))
            .thenReturn(mapOf(completeNote.id!! to 4, minimalNote.id!! to 0))

        val result = mockMvc.perform(get("/materials").with(csrf()).flashAttr("currentUser", viewer))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val rows = doc.select("div[aria-label='Notes'] tbody tr.table-row")
        assertEquals(2, rows.size)

        // ── Complete Note Row ───────────────────────────────────────────
        val row1 = rows[0]
        val cells1 = row1.select("td")
        assertEquals(8, cells1.size)
        assertTrue(cells1[0].text().contains("Complete Note"))
        assertTrue(cells1[0].text().contains("Private"), "Private note must render Private badge")
        assertEquals("Samba", cells1[1].text().trim())
        assertEquals("Latin", cells1[2].text().trim())
        assertTrue(cells1[3].text().contains("5"), "Rating must show 5")
        assertEquals("4", cells1[4].text().trim(), "Figures count must show 4")
        assertTrue(cells1[5].text().contains("Video"), "Media must show Video badge")
        assertTrue(cells1[5].text().contains("Source"), "Media must show Source badge")
        assertFalse(cells1[6].text().trim() == "-", "Created date must be rendered")
        // Actions: Viewer is owner -> Edit and Delete are present
        assertNotNull(cells1[7].selectFirst("a[href*='/edit']"), "Edit link must be present for owner")
        val deleteForm = cells1[7].selectFirst("form[action*='/delete']")
        assertNotNull(deleteForm, "Delete form must be present for owner")
        assertEquals("Are you sure you want to delete this note?", deleteForm?.attr("data-confirm"))

        // ── Minimal Note Row ────────────────────────────────────────────
        val row2 = rows[1]
        val cells2 = row2.select("td")
        assertEquals(8, cells2.size)
        assertTrue(cells2[0].text().contains("Minimal Note"))
        assertFalse(cells2[0].text().contains("Private"), "Public note must not render Private badge")
        assertEquals("-", cells2[1].text().trim(), "Missing style must render as '-'")
        assertEquals("-", cells2[2].text().trim(), "Missing category must render as '-'")
        assertEquals("-", cells2[3].text().trim(), "Missing rating must render as '-'")
        assertEquals("-", cells2[4].text().trim(), "Zero figures must render as '-'")
        assertEquals("-", cells2[5].text().trim(), "Missing media must render as '-'")
        // Actions: Viewer is NOT owner -> Edit and Delete are absent, renders '-'
        assertNull(cells2[7].selectFirst("a[href*='/edit']"), "Edit link must NOT be present for non-owner")
        assertNull(cells2[7].selectFirst("form[action*='/delete']"), "Delete form must NOT be present for non-owner")
        assertEquals("-", cells2[7].text().trim(), "Non-permitted actions must render as '-'")
    }

    @Test
    fun `wording reflects notes terminology and contains no occurrences of movement`() {
        val singleNote = createSampleMaterial(name = "Solo Note")
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(singleNote)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(get("/materials").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val content = result.response.contentAsString

        // Singular note count check
        assertTrue(content.contains("1 note"), "Subtitle must say '1 note' for singular count")
        assertTrue(content.contains("New note"), "Header action must say 'New note'")
        assertFalse(content.contains("movement", ignoreCase = true), "Rendered page must not contain 'movement'")

        // Empty state check
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))

        val emptyResult = mockMvc.perform(get("/materials").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val emptyContent = emptyResult.response.contentAsString
        assertTrue(emptyContent.contains("No notes found"), "Empty state must say 'No notes found'")
        assertFalse(emptyContent.contains("movement", ignoreCase = true), "Empty page must not contain 'movement'")
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun createSampleMaterial(
        id: UUID = UUID.randomUUID(),
        name: String = "Samba Routine",
        rating: Short? = 4,
        videoLink: String? = null,
        sourceLink: String? = null,
        driveFileId: String? = null,
        isPublic: Boolean = true,
        danceType: DanceType? = DanceType().apply {
            this.id = UUID.randomUUID()
            this.name = "Samba"
            this.category = DanceCategory().apply {
                this.id = UUID.randomUUID()
                this.name = "Latin"
            }
        }
    ): Material {
        return Material().apply {
            this.id = id
            this.name = name
            this.rating = rating
            this.videoLink = videoLink
            this.sourceLink = sourceLink
            this.driveFileId = driveFileId
            this.visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE
            this.danceType = danceType
            this.createdAt = LocalDateTime.of(2026, 3, 20, 14, 30)
            this.owner = AppUser().apply {
                this.id = UUID.randomUUID()
                this.username = "owner"
            }
        }
    }
}
