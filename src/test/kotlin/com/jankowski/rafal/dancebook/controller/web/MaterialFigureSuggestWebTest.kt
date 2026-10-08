package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.FigureSuggestion
import com.jankowski.rafal.dancebook.service.FigureSuggestionService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.web.servlet.support.RequestDataValueProcessor
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
@Import(MaterialFigureSuggestWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class, GlobalNotFoundExceptionHandler::class)
class MaterialFigureSuggestWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() =
            org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
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
    @MockBean private lateinit var trainingEventService: com.jankowski.rafal.dancebook.service.TrainingEventService

    // NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var ownerUser: AppUser
    private lateinit var otherUser: AppUser
    private lateinit var adminUser: AppUser
    private lateinit var material: Material
    private val materialId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        ownerUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "owner"
            displayName = "Owner User"
            role = Role.USER
        }

        otherUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "other"
            displayName = "Other User"
            role = Role.USER
        }

        adminUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Admin User"
            role = Role.ADMIN
        }

        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Samba"
        }

        material = Material().apply {
            id = materialId
            name = "Samba walks class"
            description = "<p>Working on samba walks and stationary samba walk.</p>"
            owner = ownerUser
            this.danceType = danceType
        }

        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        `when`(materialService.findById(materialId)).thenReturn(material)
        `when`(materialService.findFiguresByMaterial(materialId)).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(emptyList())
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
    }

    // ── GET /materials/{id}/figures/suggest ───────────────────────────────────

    @Test
    fun `GET suggest returns suggestionsPanel with matched figures on success`() {
        val figureId = UUID.randomUUID()
        val figure = DanceFigure().apply {
            id = figureId
            name = "Samba Walk"
        }
        val suggestions = listOf(FigureSuggestion(figure = figure, reason = "samba walks"))

        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenReturn(suggestions)

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsPanel"))
            .andExpect(model().attributeExists("suggestions"))
            .andExpect(content().string(containsString("Samba Walk")))
            .andExpect(content().string(containsString("samba walks")))
            .andExpect(content().string(containsString("Suggested from this note")))
    }

    @Test
    fun `a suggestion without a reason renders without an empty quote`() {
        val figure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Hockey Stick"
        }
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenReturn(listOf(FigureSuggestion(figure = figure, reason = null)))

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsPanel"))
            .andExpect(content().string(containsString("Hockey Stick")))
            .andExpect(content().string(not(containsString("Note says"))))
    }

    @Test
    fun `GET suggest returns suggestionsEmpty when LLM finds no matches`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenReturn(emptyList())

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsEmpty"))
            .andExpect(content().string(containsString("No figures in the catalog clearly match this note")))
    }

    @Test
    fun `GET suggest returns suggestionsError with message when provider fails`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenThrow(RuntimeException("OpenRouter returned 500"))

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsError"))
            .andExpect(model().attributeExists("suggestError"))
            .andExpect(content().string(containsString("Try again")))
            .andExpect(content().string(not(containsString("OpenRouter"))))
            .andExpect(content().string(not(containsString("500"))))
    }

    @Test
    fun `provider failure offers a Try again button that re-runs the suggestion`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenThrow(RuntimeException("OpenRouter returned 500"))

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"figure-suggest-retry-btn\"")))
            .andExpect(content().string(containsString("hx-get=\"/materials/$materialId/figures/suggest\"")))
            .andExpect(content().string(containsString("hx-target=\"#figureSuggestions\"")))
    }

    @Test
    fun `errors that a retry cannot fix offer no Try again button`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(false)

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("figure-suggest-retry-btn"))))
    }

    @Test
    fun `GET suggest returns suggestionsError when no LLM provider is configured`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(false)

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsError"))
            .andExpect(model().attribute("suggestError", "No LLM provider is configured."))

        verify(figureSuggestionService, never()).suggestForMaterial(material)
    }

    @Test
    fun `GET suggest returns suggestionsError when note has no text`() {
        material.description = null
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsError"))
            .andExpect(model().attribute("suggestError", "The note has no text to search for figures."))

        verify(figureSuggestionService, never()).suggestForMaterial(material)
    }

    @Test
    fun `GET suggest returns 404 when user is not the owner`() {
        `when`(appUserService.getCurrentUser()).thenReturn(otherUser)

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isNotFound)

        verify(figureSuggestionService, never()).suggestForMaterial(material)
    }

    @Test
    fun `GET suggest succeeds for admin even when not owner`() {
        val figureId = UUID.randomUUID()
        val figure = DanceFigure().apply {
            id = figureId
            name = "Samba Walk"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)
        `when`(figureSuggestionService.suggestForMaterial(material))
            .thenReturn(listOf(FigureSuggestion(figure = figure, reason = "samba walks")))

        mockMvc.perform(get("/materials/$materialId/figures/suggest").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-suggestions :: suggestionsPanel"))
    }

    // ── GET /materials/{id}/figures/picker — Suggest figures in the pin sheet ──

    @Test
    fun `pin sheet shows Suggest figures when LLM is available and note has text`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId/figures/picker").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(model().attribute("suggestionsAvailable", true))
            .andExpect(content().string(containsString("Suggest figures")))
            .andExpect(content().string(containsString("id=\"figureSuggestions\"")))
    }

    @Test
    fun `pin sheet hides Suggest figures when no LLM provider configured`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(false)

        mockMvc.perform(get("/materials/$materialId/figures/picker").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(model().attribute("suggestionsAvailable", false))
            .andExpect(content().string(not(containsString("figures/suggest"))))
    }

    @Test
    fun `pin sheet hides Suggest figures when note has no text`() {
        material.description = null
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId/figures/picker").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(model().attribute("suggestionsAvailable", false))
            .andExpect(content().string(not(containsString("figures/suggest"))))
    }

    @Test
    fun `note page has no Suggest figures action outside the pin sheet`() {
        `when`(figureSuggestionService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("figures/suggest"))))
    }

    @Test
    fun `pinning a suggestion answers with Pinned and leaves the other suggestions in place`() {
        val figureId = UUID.randomUUID()

        // The pin response swaps the clicked row to "Pinned" and replaces the page's
        // pinned-figures section out of band. The suggestions live in the pin sheet,
        // outside that section, so the other suggestions stay on screen and pinnable.
        mockMvc.perform(
            post("/materials/$materialId/figures")
                .param("danceFigureId", figureId.toString())
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-picker :: figurePinResponse"))
            .andExpect(content().string(containsString("Pinned")))
            .andExpect(content().string(containsString("id=\"pinnedFiguresSection\"")))
            .andExpect(content().string(not(containsString("figureSuggestions"))))
    }
}
