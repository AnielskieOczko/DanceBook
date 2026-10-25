package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.PdfExportService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
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
    controllers = [DanceFigureWebController::class, GlobalNotFoundExceptionHandler::class],
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
@Import(DanceFigurePdfExportWebTest.TestConfig::class, RichTextServiceImpl::class)
class DanceFigurePdfExportWebTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var pdfExportService: PdfExportService
    @MockitoBean private lateinit var appUserService: AppUserService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tancerz"
            displayName = "Tancerz Testowy"
            role = Role.USER
        }

        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)

        val principal = User("tancerz", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
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
    fun `user can export figure to PDF`() {
        val figureId = UUID.randomUUID()
        val figure = DanceFigure().apply {
            id = figureId
            name = "Whisk"
            danceType = DanceType().apply { name = "Waltz" }
        }

        val dummyPdf = "%PDF-1.4 dummy figure pdf".toByteArray()
        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(pdfExportService.exportDanceFigurePdf(anyNonNull(DanceFigure()), anyNonNull(Locale.ENGLISH))).thenReturn(dummyPdf)

        mockMvc.perform(get("/dance-figures/{id}/pdf", figureId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("Whisk.pdf")))
            .andExpect(content().bytes(dummyPdf))
    }

    @Test
    fun `exporting non-existent figure returns 404`() {
        val nonExistentId = UUID.randomUUID()
        `when`(danceFigureService.findById(nonExistentId)).thenThrow(EntityNotFoundException("Not found"))

        mockMvc.perform(get("/dance-figures/{id}/pdf", nonExistentId))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `figure detail view renders Export PDF in overflow menu even for predefined figure`() {
        val figureId = UUID.randomUUID()
        val figure = DanceFigure().apply {
            id = figureId
            name = "Telemark"
            predefined = true // Syllabus figure, regular user cannot delete it
            danceType = DanceType().apply { name = "Foxtrot" }
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)

        val result = mockMvc.perform(get("/dance-figures/{id}", figureId))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("/dance-figures/$figureId/pdf"), "Figure view should have PDF export link")
        assertTrue(html.contains("Export PDF") || html.contains("Eksportuj do PDF"))
    }
}
