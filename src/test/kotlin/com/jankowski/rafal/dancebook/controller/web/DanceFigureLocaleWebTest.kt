package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.DanceFigureStepSet
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
import org.hamcrest.Matchers.not
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
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

    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val polishLocale = Locale.forLanguageTag("pl")
    private lateinit var testUser: AppUser

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

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
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(figure)))

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
            .andExpect(content().string(containsString("Figury")))
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
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any(), anyNonNull(PageRequest.of(0, 24))))
            .thenReturn(PageImpl(listOf(figure)))

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

    @Test
    fun `figure edit form renders in Polish without English section and header strings`() {
        val figureId = UUID.randomUUID()
        val waltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            danceType = waltz
            startingPosition = "Closed Position"
            startingFootLeader = "RF"
            startingFootFollower = "LF"
            endingPosition = "Promenade Position"
            endingFootLeader = "LF"
            endingFootFollower = "RF"
        }
        val stepSet = DanceFigureStepSet().apply {
            id = UUID.randomUUID()
            name = "Default"
            isDefault = true
            danceFigure = figure
        }
        val leaderStep = DanceFigureStep().apply {
            id = UUID.randomUUID()
            stepNumber = 1
            timing = "1"
            role = "LEADER"
            foot = "RF"
            action = "Forward right foot"
            danceFigureStepSet = stepSet
        }
        val followerStep = DanceFigureStep().apply {
            id = UUID.randomUUID()
            stepNumber = 1
            timing = "1"
            role = "FOLLOWER"
            foot = "LF"
            action = "Back left foot"
            danceFigureStepSet = stepSet
        }
        stepSet.steps.addAll(listOf(leaderStep, followerStep))
        figure.stepSets.add(stepSet)

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        `when`(danceFigureService.findByDanceType(waltz.id!!)).thenReturn(listOf(figure))

        mockMvc.perform(
            get("/dance-figures/{id}/edit", figureId)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            // Polish section headings and jump nav links
            .andExpect(content().string(containsString("Podstawowe informacje")))
            .andExpect(content().string(containsString("Pozycje i praca stóp")))
            .andExpect(content().string(containsString("Rozbicie kroków")))
            .andExpect(content().string(containsString("Połączenia")))
            .andExpect(content().string(containsString("Filmy i materiały")))
            .andExpect(content().string(containsString("Notatki do figury")))
            // Polish feet table headers & row labels
            .andExpect(content().string(containsString("Pozycja")))
            .andExpect(content().string(containsString("Stopa partnera")))
            .andExpect(content().string(containsString("Stopa partnerki")))
            .andExpect(content().string(containsString("Początek")))
            .andExpect(content().string(containsString("Koniec")))
            // Polish step table headers & mobile labels
            .andExpect(content().string(containsString("Nr")))
            .andExpect(content().string(containsString("Rola")))
            .andExpect(content().string(containsString("Rytm *")))
            .andExpect(content().string(containsString("Stopa *")))
            .andExpect(content().string(containsString("Ruch *")))
            .andExpect(content().string(containsString("Praca stóp")))
            .andExpect(content().string(containsString("Ustawienie")))
            .andExpect(content().string(containsString("Obrót")))
            .andExpect(content().string(containsString("Uwagi techniczne")))
            // Polish roles & variants
            .andExpect(content().string(containsString("Partner")))
            .andExpect(content().string(containsString("Partnerka")))
            .andExpect(content().string(containsString("Obok siebie")))
            .andExpect(content().string(containsString("wariantów. Każdy ma własne kroki")))
            .andExpect(content().string(containsString("Usunąć ten wariant i jego kroki? Tej operacji nie można cofnąć.")))
            .andExpect(content().string(containsString("Zachowaj")))
            // Polish aria-labels & buttons
            .andExpect(content().string(containsString("aria-label=\"Tryb edycji\"")))
            .andExpect(content().string(containsString("aria-label=\"Odrzuć zmiany\"")))
            .andExpect(content().string(containsString("aria-label=\"Sekcje\"")))
            .andExpect(content().string(containsString("aria-label=\"Warianty kroków\"")))
            .andExpect(content().string(containsString("aria-label=\"Wybór roli\"")))
            .andExpect(content().string(containsString("aria-label=\"Potwierdź usunięcie\"")))
            .andExpect(content().string(containsString("aria-label=\"Usuń krok\"")))
            .andExpect(content().string(containsString("Zapisz figurę")))
            .andExpect(content().string(containsString("Odrzuć")))
            // Assert English section/header/button/aria strings do NOT appear
            .andExpect(content().string(not(containsString(">Positions and feet<"))))
            .andExpect(content().string(not(containsString(">Leader foot<"))))
            .andExpect(content().string(not(containsString(">Follower foot<"))))
            .andExpect(content().string(not(containsString(">Amount of turn<"))))
            .andExpect(content().string(not(containsString(">Keep it<"))))
            .andExpect(content().string(not(containsString("aria-label=\"Discard changes\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Edit mode\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Sections\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Step variants\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Which role to show\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Confirm delete\""))))
            .andExpect(content().string(not(containsString("aria-label=\"Remove step\""))))
    }
}
