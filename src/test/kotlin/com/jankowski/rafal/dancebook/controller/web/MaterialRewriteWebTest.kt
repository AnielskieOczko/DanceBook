package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
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
@Import(MaterialRewriteWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class, GlobalNotFoundExceptionHandler::class)
class MaterialRewriteWebTest {

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
    private val dummyMaterialRequest = com.jankowski.rafal.dancebook.dto.MaterialRequest(version = 1L)

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

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

        material = Material().apply {
            id = materialId
            name = "Waltz Workshop"
            description = "<p>Rough class notes with <strong>bold</strong> and <a href=\"https://example.com\">link</a>.</p>"
            owner = ownerUser
        }

        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        `when`(materialService.findById(materialId)).thenReturn(material)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
    }

    // ── POST /materials/{id}/rewrite ─────────────────────────────────────────

    @Test
    fun `POST rewrite returns proposal panel on success and never calls materialService update`() {
        val proposalHtml = "<p>Refined class notes with <strong>bold</strong> and <a href=\"https://example.com\">link</a>.</p>"
        `when`(noteRewriteService.isAvailable()).thenReturn(true)
        `when`(noteRewriteService.rewrite(material.description!!)).thenReturn(proposalHtml)

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewritePanel"))
            .andExpect(model().attribute("proposalHtml", proposalHtml))
            .andExpect(model().attribute("proposalRaw", proposalHtml))
            .andExpect(content().string(containsString("Proposed rewrite of the note text")))
            .andExpect(content().string(containsString("Refined class notes")))
            .andExpect(content().string(containsString("Accept rewrite")))
            .andExpect(content().string(containsString("Decline")))

        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite uses currentText param when provided`() {
        val customText = "<p>Unsaved editor text</p>"
        val proposalHtml = "<p>Cleaned unsaved text</p>"
        `when`(noteRewriteService.isAvailable()).thenReturn(true)
        `when`(noteRewriteService.rewrite(customText)).thenReturn(proposalHtml)

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .param("currentText", customText)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewritePanel"))
            .andExpect(model().attribute("proposalHtml", proposalHtml))

        verify(noteRewriteService).rewrite(customText)
        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite with provider failure returns safe error message and never calls update`() {
        `when`(noteRewriteService.isAvailable()).thenReturn(true)
        `when`(noteRewriteService.rewrite(anyNonNull(""))).thenThrow(
            RuntimeException("OpenRouter API returned error status 500: Internal Server Error")
        )

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewriteError"))
            .andExpect(model().attribute("rewriteError", "The AI rewrite didn't work this time. Your text hasn't changed."))
            .andExpect(content().string(containsString("The AI rewrite didn&#39;t work this time. Your text hasn&#39;t changed.")))
            .andExpect(content().string(not(containsString("OpenRouter"))))
            .andExpect(content().string(not(containsString("500"))))

        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite refuses non-owner with 404 and never calls rewrite or update`() {
        `when`(appUserService.getCurrentUser()).thenReturn(otherUser)

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isNotFound)

        verify(noteRewriteService, never()).rewrite(anyNonNull(""))
        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite allows admin even if not owner`() {
        val proposalHtml = "<p>Admin rewrite proposal</p>"
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(noteRewriteService.isAvailable()).thenReturn(true)
        `when`(noteRewriteService.rewrite(material.description!!)).thenReturn(proposalHtml)

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewritePanel"))

        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite returns error fragment when note has no text`() {
        material.description = null

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewriteError"))
            .andExpect(model().attribute("rewriteError", "The note has no text to rewrite."))

        verify(noteRewriteService, never()).rewrite(anyNonNull(""))
        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    @Test
    fun `POST rewrite returns error fragment when no LLM provider is configured`() {
        `when`(noteRewriteService.isAvailable()).thenReturn(false)

        mockMvc.perform(
            post("/materials/$materialId/rewrite")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/ai-rewrite-panel :: rewriteError"))
            .andExpect(model().attribute("rewriteError", "No LLM provider is configured."))

        verify(noteRewriteService, never()).rewrite(anyNonNull(""))
        verify(materialService, never()).update(anyNonNull(materialId), anyNonNull(dummyMaterialRequest))
    }

    // ── GET /materials/{id}/edit button visibility ───────────────────────────

    @Test
    fun `GET edit form displays Rewrite with AI button when provider is configured and note has text`() {
        `when`(noteRewriteService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId/edit"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("llmAvailable", true))
            .andExpect(content().string(containsString("id=\"ai-rewrite-btn\"")))
            .andExpect(content().string(containsString("Rewrite with AI")))
    }

    @Test
    fun `GET edit form hides Rewrite with AI button when no provider is configured`() {
        `when`(noteRewriteService.isAvailable()).thenReturn(false)

        mockMvc.perform(get("/materials/$materialId/edit"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("llmAvailable", false))
            .andExpect(content().string(not(containsString("id=\"ai-rewrite-btn\""))))
            .andExpect(content().string(not(containsString("Rewrite with AI"))))
    }

    @Test
    fun `GET edit form hides Rewrite with AI button when note has no text`() {
        material.description = null
        `when`(noteRewriteService.isAvailable()).thenReturn(true)

        mockMvc.perform(get("/materials/$materialId/edit"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("llmAvailable", false))
            .andExpect(content().string(not(containsString("id=\"ai-rewrite-btn\""))))
            .andExpect(content().string(not(containsString("Rewrite with AI"))))
    }
}
