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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
@Import(MaterialPdfExportWebTest.TestConfig::class, RichTextServiceImpl::class)
class MaterialPdfExportWebTest {

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

    private lateinit var currentUser: AppUser
    private lateinit var otherUser: AppUser
    private lateinit var adminUser: AppUser

    @BeforeEach
    fun setUp() {
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "alice"
            displayName = "Alice Dancer"
            role = Role.USER
        }

        otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "bob"
            displayName = "Bob Dancer"
            role = Role.USER
        }

        adminUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Admin User"
            role = Role.ADMIN
        }

        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)

        val principal = User("alice", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `user can export their own private note to PDF`() {
        val noteId = UUID.randomUUID()
        val ownPrivateNote = Material().apply {
            id = noteId
            name = "My Private Samba Note"
            owner = currentUser
            visibility = Visibility.PRIVATE
        }

        val dummyPdf = "%PDF-1.4 dummy note pdf content".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(ownPrivateNote)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("My_Private_Samba_Note.pdf")))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `user can export a public note of another user to PDF`() {
        val noteId = UUID.randomUUID()
        val publicNote = Material().apply {
            id = noteId
            name = "Bob's Public Waltz Note"
            owner = otherUser
            visibility = Visibility.PUBLIC
        }

        val dummyPdf = "%PDF-1.4 public waltz pdf".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(publicNote)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `user can export a note shared with them to PDF`() {
        val noteId = UUID.randomUUID()
        val sharedNote = Material().apply {
            id = noteId
            name = "Shared Rumba Guide"
            owner = otherUser
            visibility = Visibility.PRIVATE
        }

        val dummyPdf = "%PDF-1.4 shared rumba pdf".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(sharedNote)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `normal user cannot export another user's unshared private note and receives 404`() {
        val noteId = UUID.randomUUID()
        `when`(materialService.findByIdForExport(noteId)).thenThrow(EntityNotFoundException("Could not find material with id $noteId"))

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `admin user cannot export another user's unshared private note and receives 404`() {
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)

        val noteId = UUID.randomUUID()
        `when`(materialService.findByIdForExport(noteId)).thenThrow(EntityNotFoundException("Could not find material with id $noteId"))

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `admin user can export their own private note to PDF`() {
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)

        val noteId = UUID.randomUUID()
        val adminOwnNote = Material().apply {
            id = noteId
            name = "Admin Private Routine"
            owner = adminUser
            visibility = Visibility.PRIVATE
        }

        val dummyPdf = "%PDF-1.4 admin pdf".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(adminOwnNote)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `admin user can export a public note to PDF`() {
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)

        val noteId = UUID.randomUUID()
        val publicNote = Material().apply {
            id = noteId
            name = "Public Foxtrot Note"
            owner = otherUser
            visibility = Visibility.PUBLIC
        }

        val dummyPdf = "%PDF-1.4 public pdf".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(publicNote)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `admin user can export a note shared with them to PDF`() {
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)

        val noteId = UUID.randomUUID()
        val sharedWithAdmin = Material().apply {
            id = noteId
            name = "Note Shared With Admin"
            owner = otherUser
            visibility = Visibility.PRIVATE
        }

        val dummyPdf = "%PDF-1.4 shared admin pdf".toByteArray()
        `when`(materialService.findByIdForExport(noteId)).thenReturn(sharedWithAdmin)
        `when`(pdfExportService.exportMaterialPdf(anyNonNull(Material()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/materials/{id}/pdf", noteId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `exporting non-existent note returns 404`() {
        val nonExistentId = UUID.randomUUID()
        `when`(materialService.findByIdForExport(nonExistentId)).thenThrow(EntityNotFoundException("Not found"))

        mockMvc.perform(get("/materials/{id}/pdf", nonExistentId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `note detail view renders Export PDF in overflow menu`() {
        val noteId = UUID.randomUUID()
        val note = Material().apply {
            id = noteId
            name = "Quickstep Routine"
            owner = currentUser
            visibility = Visibility.PUBLIC
        }

        `when`(materialService.findById(noteId)).thenReturn(note)
        `when`(materialService.findFiguresByMaterial(noteId)).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(noteId)).thenReturn(emptyList())

        val result = mockMvc.perform(get("/materials/{id}", noteId))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("/materials/$noteId/pdf"), "Note view should have export link")
        assertTrue(html.contains("Export PDF") || html.contains("Eksportuj do PDF"))
    }
}
