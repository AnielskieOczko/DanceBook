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
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [DanceFigureWebController::class],
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
@Import(DanceFigureLocaleWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceFigureLocaleWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService

    // NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val polishLocale = Locale.forLanguageTag("pl")
    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "testuser"
            displayName = "Test User"
            email = "test@example.com"
            role = Role.USER
            locale = null
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)
    }

    @Test
    fun `figures catalog list renders in Polish when locale is pl`() {
        val waltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Turn"
            danceType = waltz
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(figure))

        mockMvc.perform(
            get("/dance-figures")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Baza figur")))
            .andExpect(content().string(containsString("Filtry")))
            .andExpect(content().string(containsString("Style tańca")))
            .andExpect(content().string(containsString("Wszystkie klasy")))
            .andExpect(content().string(containsString("Nazwa")))
            .andExpect(content().string(containsString("Styl")))
            .andExpect(content().string(containsString("Kroki")))
            .andExpect(content().string(containsString("Akcje")))
    }

    @Test
    fun `figure view page renders in Polish when locale is pl`() {
        val figureId = UUID.randomUUID()
        val waltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            danceType = waltz
            notes = "Test notes"
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(waltz.id!!)).thenReturn(listOf(figure))

        mockMvc.perform(
            get("/dance-figures/{id}", figureId)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Główne szczegóły")))
            .andExpect(content().string(containsString("Powrót do bazy")))
            .andExpect(content().string(containsString("Kroki ze syllabusa")))
            .andExpect(content().string(containsString("Pozycje i ustawienie")))
            .andExpect(content().string(containsString("Połączenia")))
    }

    @Test
    fun `figure new form renders in Polish when locale is pl`() {
        val waltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        `when`(danceFigureService.findAll()).thenReturn(emptyList())

        mockMvc.perform(
            get("/dance-figures/new")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Utwórz nową figurę")))
            .andExpect(content().string(containsString("Podstawowe informacje")))
            .andExpect(content().string(containsString("Pozycje i praca stóp")))
            .andExpect(content().string(containsString("Zapisz figurę")))
    }

    @Test
    fun `figures catalog list renders in English when locale is en`() {
        val waltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Turn"
            danceType = waltz
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(figure))

        mockMvc.perform(
            get("/dance-figures")
                .locale(Locale.ENGLISH)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Figures Database")))
            .andExpect(content().string(containsString("Filters")))
            .andExpect(content().string(containsString("Dance Styles")))
            .andExpect(content().string(containsString("Name")))
            .andExpect(content().string(containsString("Style")))
            .andExpect(content().string(containsString("Steps")))
            .andExpect(content().string(containsString("Actions")))
    }
}
