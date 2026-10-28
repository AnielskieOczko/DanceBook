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
import com.jankowski.rafal.dancebook.service.PdfListCover
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
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
@Import(DanceFigureListExportWebTest.TestConfig::class, RichTextServiceImpl::class)
class DanceFigureListExportWebTest {

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

    private fun figures(count: Int) = (1..count).map {
        DanceFigure().apply { id = UUID.randomUUID(); name = "Figure $it"; danceType = DanceType().apply { name = "Waltz" } }
    }

    /** Stubs the list query so that [total] figures match and each requested page holds the right slice. */
    private fun stubFindAll(total: Int) {
        val all = figures(total)
        `when`(
            danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 1) as Pageable))
        ).thenAnswer { invocation ->
            val pageable = invocation.getArgument<Pageable>(7)
            val from = minOf(pageable.offset.toInt(), all.size)
            val to = minOf(from + pageable.pageSize, all.size)
            PageImpl(all.subList(from, to), pageable, total.toLong())
        }
    }

    @Test
    fun `export of up to 100 figures goes straight to the single PDF and keeps the filters`() {
        stubFindAll(40)

        mockMvc.perform(get("/dance-figures/export").param("nameSearch", "turn").param("danceClass", "B").param("view", "grid"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dance-figures/export/pdf?danceClass=B&nameSearch=turn&part=1"))
    }

    @Test
    fun `export of more than 100 figures lists every part with its item range`() {
        stubFindAll(250)

        mockMvc.perform(get("/dance-figures/export"))
            .andExpect(status().isOk)
            .andExpect(view().name("exports/parts"))
            .andExpect(content().string(containsString("Export figures to PDF")))
            .andExpect(content().string(containsString("split into 3 parts")))
            .andExpect(content().string(containsString("Items 201\u2013250")))
            .andExpect(content().string(containsString("/dance-figures/export/pdf?part=3")))
    }

    @Test
    fun `export with no matching figures says there is nothing to export`() {
        stubFindAll(0)

        mockMvc.perform(get("/dance-figures/export"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nothing to export")))
    }

    @Test
    fun `a part is a normal request for at most 100 figures with a part label on the cover`() {
        stubFindAll(250)
        var exported: List<*>? = null
        var cover: PdfListCover? = null
        `when`(pdfExportService.exportDanceFigureListPdf(anyNonNull(emptyList<DanceFigure>()), anyNonNull(PdfListCover("", emptyList())), anyNonNull(Locale.ENGLISH)))
            .thenAnswer { invocation ->
                exported = invocation.getArgument(0)
                cover = invocation.getArgument(1)
                "%PDF part three".toByteArray()
            }

        mockMvc.perform(get("/dance-figures/export/pdf").param("part", "3"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("dancebook-figures-part-3-of-3.pdf")))

        assertEquals(50, exported!!.size)
        assertEquals(3, cover!!.part)
        assertEquals(3, cover!!.totalParts)
        assertEquals(250L, cover!!.totalItems)
    }

    @Test
    fun `a part past the end of the list is not found`() {
        stubFindAll(250)

        mockMvc.perform(get("/dance-figures/export/pdf").param("part", "4")).andExpect(status().isNotFound)
        mockMvc.perform(get("/dance-figures/export/pdf").param("part", "0")).andExpect(status().isNotFound)
    }
}
