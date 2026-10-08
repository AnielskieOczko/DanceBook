package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.config.UserLocaleResolver
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
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

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
@Import(MaterialLocaleWebTest.TestConfig::class, RichTextServiceImpl::class)
class MaterialLocaleWebTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var noteRewriteService: NoteRewriteService
    @MockBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockBean private lateinit var trainingEventService: TrainingEventService

    // NavbarAdvice collaborators
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var testUser: AppUser
    private val materialId: UUID = UUID.randomUUID()

    private val danceCategory = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Standard"
    }

    private val danceType = DanceType().apply {
        id = UUID.randomUUID()
        name = "Slow Foxtrot"
        category = danceCategory
    }

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "locale_user"
            displayName = "Tancerz Jan"
            email = "jan@example.com"
            role = Role.USER
            locale = "pl"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)

        val principal = User("locale_user", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))

        `when`(danceCategoryService.findAll()).thenReturn(listOf(danceCategory))
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))
        `when`(danceTypeService.findByCategoryId(danceCategory.id!!)).thenReturn(listOf(danceType))
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `notes list page renders in Polish when user locale is pl`() {
        testUser.locale = "pl"
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))

        val result = mockMvc.perform(
            get("/materials")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Header title & subtitle
        val headerTitle = doc.selectFirst(".page-title")
        assertNotNull(headerTitle)
        assertEquals("Notatki", headerTitle!!.text().trim())

        val headerSubtitle = doc.selectFirst(".page-subtitle")
        assertNotNull(headerSubtitle)
        assertEquals("0 notatek", headerSubtitle!!.text().trim())

        // Search placeholder
        val searchInput = doc.selectFirst("input[name=nameSearch]")
        assertNotNull(searchInput)
        assertEquals("Szukaj...", searchInput!!.attr("placeholder"))

        // Filters button
        val filterBtn = doc.selectFirst(".js-filter-toggle")
        assertNotNull(filterBtn)
        assertTrue(filterBtn!!.text().contains("Filtry"))

        // New note button
        val newNoteBtn = doc.selectFirst("a[href='/materials/new']")
        assertNotNull(newNoteBtn)
        assertTrue(newNoteBtn!!.text().contains("Nowa notatka"))

        // Empty state
        assertTrue(html.contains("Nie znaleziono notatek"))

        // Verify English text is absent
        assertFalse(html.contains("No notes found"))
    }

    @Test
    fun `notes list dense table renders Polish column headers when materials are present`() {
        testUser.locale = "pl"
        val sample = Material().apply {
            id = materialId
            name = "Walc angielski"
            owner = testUser
            danceType = this@MaterialLocaleWebTest.danceType
            rating = 4
        }
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged())))
            .thenReturn(PageImpl(listOf(sample)))
        `when`(materialService.findFigureCounts(anyNonNull(emptyList()))).thenReturn(emptyMap())

        val result = mockMvc.perform(
            get("/materials")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val tableHeaders = doc.select("table thead th").map { it.text().trim() }
        assertTrue(tableHeaders.any { it.startsWith("Tytuł") })
        assertTrue(tableHeaders.contains("Styl"))
        assertTrue(tableHeaders.contains("Kategoria"))
        assertTrue(tableHeaders.any { it.startsWith("Ocena") })
        assertFalse(tableHeaders.any { it.startsWith("Title") })
    }

    @Test
    fun `notes list page renders in English when user locale is en`() {
        testUser.locale = "en"
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))

        val result = mockMvc.perform(
            get("/materials")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        val headerTitle = doc.selectFirst(".page-title")
        assertNotNull(headerTitle)
        assertEquals("Notes", headerTitle!!.text().trim())

        val headerSubtitle = doc.selectFirst(".page-subtitle")
        assertNotNull(headerSubtitle)
        assertEquals("0 notes", headerSubtitle!!.text().trim())

        val searchInput = doc.selectFirst("input[name=nameSearch]")
        assertNotNull(searchInput)
        assertEquals("Search...", searchInput!!.attr("placeholder"))

        val filterBtn = doc.selectFirst(".js-filter-toggle")
        assertNotNull(filterBtn)
        assertTrue(filterBtn!!.text().contains("Filters"))

        assertTrue(html.contains("No notes found"))
    }

    @Test
    fun `notes view page renders in Polish when user locale is pl`() {
        testUser.locale = "pl"

        val material = Material().apply {
            id = materialId
            name = "Kroki piórkowe: praca stóp"
            description = "<div>Opis pracy stóp w feather step.</div>"
            owner = testUser
            this.danceType = this@MaterialLocaleWebTest.danceType
            rating = 5
            visibility = Visibility.PUBLIC
            createdAt = LocalDateTime.of(2026, 9, 10, 12, 0)
            updatedAt = LocalDateTime.of(2026, 9, 15, 14, 0)
            driveFileId = "drive-123"
            videoLink = "https://youtube.com/watch?v=feather"
            sourceLink = "https://example.com/handout"
        }
        `when`(materialService.findById(materialId)).thenReturn(material)
        `when`(materialService.findFiguresByMaterial(materialId)).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(emptyList())

        val result = mockMvc.perform(
            get("/materials/{id}", materialId)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Contextual header: back link & edit button
        val backLink = doc.selectFirst("header a[href='/materials']")
        assertNotNull(backLink)
        assertTrue(backLink!!.text().contains("Notatki"))

        val editButton = doc.selectFirst("header a[href='/materials/$materialId/edit']")
        assertNotNull(editButton)
        assertTrue(editButton!!.text().contains("Edytuj notatkę"))

        // Visibility badge
        val badge = doc.selectFirst(".badge-success")
        assertNotNull(badge)
        assertEquals("Publiczna", badge!!.text().trim())

        // Figures heading & pin button & empty state
        val figuresHeading = doc.selectFirst("#pinnedFiguresSection h2")
        assertNotNull(figuresHeading)
        assertTrue(figuresHeading!!.text().contains("Figury"))

        val pinButton = doc.selectFirst("#pinnedFiguresSection button[hx-target='#confirmModalContainer']")
        assertNotNull(pinButton)
        assertTrue(pinButton!!.text().contains("Przypnij figurę"))

        assertTrue(html.contains("Brak przypiętych figur do tej notatki."))

        // Media section
        assertTrue(html.contains("Wideo i źródło"))
        assertTrue(html.contains("Wideo do obejrzenia dla tej notatki"))
        assertTrue(html.contains("Skąd pochodzi ten materiał"))

        // Comments heading & empty state
        val commentsHeading = doc.selectFirst("#comment-section h2")
        assertNotNull(commentsHeading)
        assertTrue(commentsHeading!!.text().contains("Komentarze"))
        assertTrue(html.contains("Brak komentarzy."))

        // Verify English leftovers are absent
        assertFalse(html.contains("Edit note"))
        assertFalse(html.contains("Pin figure"))
        assertFalse(html.contains("No figures pinned yet."))
        assertFalse(html.contains("Video and source"))
        assertFalse(html.contains("A video to watch for this note"))
        assertFalse(html.contains("Where this material came from"))
        assertFalse(html.contains("No comments yet."))
    }

    @Test
    fun `notes new form renders in Polish when user locale is pl`() {
        testUser.locale = "pl"

        val result = mockMvc.perform(
            get("/materials/new")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Page title & subtitle
        val pageTitle = doc.selectFirst(".page-title")
        assertNotNull(pageTitle)
        assertEquals("Nowy materiał", pageTitle!!.text().trim())

        // Labels
        assertTrue(html.contains("Nazwa"))
        assertTrue(html.contains("Opis"))
        assertTrue(html.contains("Kategoria"))
        assertTrue(html.contains("Styl tańca"))
        assertTrue(html.contains("Ocena (1–5)"))
        assertTrue(html.contains("Link do wideo"))
        assertTrue(html.contains("Link do źródła"))

        // Drive upload
        assertTrue(html.contains("Prześlij wideo na Dysk Google"))
        assertTrue(html.contains("Kliknij, aby wybrać plik wideo..."))
        assertTrue(html.contains("Prześlij"))

        // Public toggle
        assertTrue(html.contains("Notatka publiczna"))
        assertTrue(html.contains("Udostępnij tę notatkę wszystkim użytkownikom platformy"))

        // Cancel and Save buttons
        val cancelBtn = doc.selectFirst("a[href='/materials'].btn-outline")
        assertNotNull(cancelBtn)
        assertEquals("Anuluj", cancelBtn!!.text().trim())

        val saveBtn = doc.selectFirst("#saveBtn")
        assertNotNull(saveBtn)
        assertTrue(saveBtn!!.text().contains("Zapisz materiał"))

        // Verify English text is absent
        assertFalse(html.contains("New Material"))
        assertFalse(html.contains("Save Material"))
        assertFalse(html.contains("Upload Video to Google Drive"))
        assertFalse(html.contains("Click to select a video file..."))
        assertFalse(html.contains("Public Note"))
    }

    @Test
    fun `figure picker fragment renders in Polish when user locale is pl`() {
        testUser.locale = "pl"

        val material = Material().apply {
            id = materialId
            name = "Test Note"
            description = "<div>Opis notatki</div>"
            owner = testUser
            danceType = this@MaterialLocaleWebTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)

        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Feather Step"
            danceType = this@MaterialLocaleWebTest.danceType
            danceClass = DanceClass.B
            alternativeTiming = "S Q Q"
        }
        `when`(danceFigureService.findAll(typeIds = listOf(danceType.id!!), sortBy = "name_asc"))
            .thenReturn(listOf(figure))

        val result = mockMvc.perform(
            get("/materials/{materialId}/figures/picker", materialId)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Modal title
        val title = doc.selectFirst("#figurePickerDialog h3")
        assertNotNull(title)
        assertEquals("Przypnij figurę", title!!.text().trim())

        // Search placeholder
        val search = doc.selectFirst("#figureSearchQuery")
        assertNotNull(search)
        assertEquals("Szukaj w katalogu po nazwie...", search!!.attr("placeholder"))

        // All styles filter
        assertTrue(html.contains("Szukaj we wszystkich stylach tańca"))
        assertTrue(html.contains("Styl notatki: Slow Foxtrot"))

        // Suggest figures button
        assertTrue(html.contains("Zaproponuj figury"))

        // Done button
        val doneBtn = doc.selectFirst("dialog form[method=dialog] button.btn-outline")
        assertNotNull(doneBtn)
        assertEquals("Gotowe", doneBtn!!.text().trim())

        // Figure row
        assertTrue(html.contains("Klasa B"))
        val pinBtn = doc.selectFirst(".js-figure-action button")
        assertNotNull(pinBtn)
        assertTrue(pinBtn!!.text().contains("Przypnij"))

        // Verify English is absent
        assertFalse(html.contains("Pin Figure"))
        assertFalse(html.contains("Search catalog by name..."))
        assertFalse(html.contains("Search all dance styles"))
        assertFalse(html.contains("Note style:"))
        assertFalse(html.contains("Suggest figures"))
        assertFalse(html.contains("Class B"))
    }
}
