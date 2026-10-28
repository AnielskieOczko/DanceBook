package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
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
import com.jankowski.rafal.dancebook.service.PdfExportService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import jakarta.persistence.EntityNotFoundException
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import com.jankowski.rafal.dancebook.service.PdfListCover
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [MaterialWebController::class, GlobalNotFoundExceptionHandler::class],
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
@Import(MaterialListExportWebTest.TestConfig::class, RichTextServiceImpl::class)
class MaterialListExportWebTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var pdfExportService: PdfExportService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var user: AppUser

    @BeforeEach
    fun setUp() {
        user = AppUser().apply {
            id = UUID.randomUUID()
            username = "alice"
            displayName = "Alice Dancer"
            role = Role.USER
        }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val principal = User("alice", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        TestSecurityContextHolder.setContext(
            SecurityContextImpl(UsernamePasswordAuthenticationToken(principal, "password", principal.authorities))
        )
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun notes(count: Int) = (1..count).map {
        Material().apply { id = UUID.randomUUID(); name = "Note $it"; owner = user; visibility = Visibility.PRIVATE }
    }

    private fun pageOf(total: Long, content: List<Material>) = PageImpl(content, PageRequest.of(0, maxOf(1, content.size)), total)

    @Test
    fun `export of up to 100 notes goes straight to the single PDF and keeps the filters`() {
        val typeId = UUID.randomUUID()
        `when`(materialService.findForExport(listOf(typeId), null, null, "turn", 0, 1)).thenReturn(pageOf(100, notes(1)))

        mockMvc.perform(get("/materials/export").param("typeIds", typeId.toString()).param("nameSearch", "turn").param("view", "grid"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/export/pdf?typeIds=$typeId&nameSearch=turn&part=1"))
    }

    @Test
    fun `export of more than 100 notes lists every part with its item range`() {
        `when`(materialService.findForExport(null, null, null, null, 0, 1)).thenReturn(pageOf(250, notes(1)))

        mockMvc.perform(get("/materials/export"))
            .andExpect(status().isOk)
            .andExpect(view().name("exports/parts"))
            .andExpect(content().string(containsString("250 items match your filters")))
            .andExpect(content().string(containsString("split into 3 parts")))
            .andExpect(content().string(containsString("Part 1 of 3")))
            .andExpect(content().string(containsString("Items 1\u2013100")))
            .andExpect(content().string(containsString("Items 101\u2013200")))
            .andExpect(content().string(containsString("Items 201\u2013250")))
            .andExpect(content().string(containsString("/materials/export/pdf?part=1")))
            .andExpect(content().string(containsString("/materials/export/pdf?part=2")))
            .andExpect(content().string(containsString("/materials/export/pdf?part=3")))
            .andExpect(content().string(containsString("Download part 3")))
    }

    @Test
    fun `the parts page is in Polish`() {
        `when`(materialService.findForExport(null, null, null, null, 0, 1)).thenReturn(pageOf(120, notes(1)))

        mockMvc.perform(get("/materials/export").locale(Locale.forLanguageTag("pl")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Eksport notatek do PDF")))
            .andExpect(content().string(containsString("Część 2 z 2")))
            .andExpect(content().string(containsString("Pobierz część 2")))
    }

    @Test
    fun `export with no matching notes says there is nothing to export`() {
        `when`(materialService.findForExport(null, null, 5, null, 0, 1)).thenReturn(pageOf(0, emptyList()))

        mockMvc.perform(get("/materials/export").param("minRating", "5"))
            .andExpect(status().isOk)
            .andExpect(view().name("exports/parts"))
            .andExpect(content().string(containsString("Nothing to export")))
    }

    @Test
    fun `a part is a normal request for at most 100 notes with a part label on the cover`() {
        val content = notes(100)
        `when`(materialService.findForExport(null, null, null, null, 1, 100)).thenReturn(PageImpl(content, PageRequest.of(1, 100), 250))
        val pdf = "%PDF part two".toByteArray()
        var cover: PdfListCover? = null
        var exported: List<*>? = null
        `when`(pdfExportService.exportMaterialListPdf(anyNonNull(emptyList<Material>()), anyNonNull(PdfListCover("", emptyList())), anyNonNull(Locale.ENGLISH)))
            .thenAnswer { invocation ->
                exported = invocation.getArgument(0)
                cover = invocation.getArgument(1)
                pdf
            }

        mockMvc.perform(get("/materials/export/pdf").param("part", "2"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("dancebook-notes-part-2-of-3.pdf")))
            .andExpect(content().bytes(pdf))

        assertEquals(content, exported)
        assertEquals(2, cover!!.part)
        assertEquals(3, cover!!.totalParts)
        assertEquals(250L, cover!!.totalItems)
        assertEquals("Alice Dancer", cover!!.ownerName)
    }

    @Test
    fun `a single-part export is named without a part suffix`() {
        `when`(materialService.findForExport(null, null, null, null, 0, 100)).thenReturn(PageImpl(notes(3), PageRequest.of(0, 100), 3))
        `when`(pdfExportService.exportMaterialListPdf(anyNonNull(emptyList<Material>()), anyNonNull(PdfListCover("", emptyList())), anyNonNull(Locale.ENGLISH))).thenReturn("%PDF".toByteArray())

        mockMvc.perform(get("/materials/export/pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("dancebook-notes.pdf")))
    }

    @Test
    fun `a part past the end of the list is not found`() {
        `when`(materialService.findForExport(null, null, null, null, 4, 100)).thenReturn(PageImpl(emptyList(), PageRequest.of(4, 100), 250))

        mockMvc.perform(get("/materials/export/pdf").param("part", "5")).andExpect(status().isNotFound)
        mockMvc.perform(get("/materials/export/pdf").param("part", "0")).andExpect(status().isNotFound)
    }

    @Test
    fun `export routes are not taken for a note id and the single-note PDF still works`() {
        val noteId = UUID.randomUUID()
        val note = Material().apply { id = noteId; name = "One"; owner = user; visibility = Visibility.PRIVATE }
        `when`(materialService.findByIdForExport(noteId)).thenReturn(note)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn("%PDF one".toByteArray())

        mockMvc.perform(get("/materials/{id}/pdf", noteId)).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("One.pdf")))
    }
}
