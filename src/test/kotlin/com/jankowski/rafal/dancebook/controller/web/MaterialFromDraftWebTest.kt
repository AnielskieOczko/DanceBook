package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantDraftService
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
import jakarta.persistence.EntityNotFoundException
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

/** "Edit in form" for a note the assistant drafted (#149): the form opens prefilled, and saving it applies the drafted pins and attendance. */
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
@Import(MaterialFromDraftWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialFromDraftWebTest {

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
    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var assistantDrafts: AssistantDraftService
    // Global NavbarAdvice and interceptor dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val draftId = UUID.randomUUID()
    private val category = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val figure = DanceFigure().apply { id = UUID.randomUUID(); name = "Feather Step"; alternativeTiming = "S Q Q" }
    private val session = TrainingEvent().apply {
        id = UUID.randomUUID(); title = "Standard group class"
        startTime = LocalDateTime.of(2026, 9, 23, 19, 0); endTime = LocalDateTime.of(2026, 9, 23, 20, 30)
    }
    private val savedNote = Material().apply { id = UUID.randomUUID() }

    @BeforeEach
    fun setUp() {
        val user = AppUser().apply { id = UUID.randomUUID(); username = "dancer"; displayName = "Dancer" }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))
        `when`(danceTypeService.findByCategoryId(category.id!!)).thenReturn(emptyList())
        `when`(danceFigureService.findById(figure.id!!)).thenReturn(figure)
        `when`(trainingEventService.findById(session.id!!)).thenReturn(session)
        `when`(materialService.create(anyRequest())).thenReturn(savedNote)
        `when`(trainingEventService.bulkUpdateMaterial(listOf(session.id!!), savedNote.id, null, false))
            .thenReturn(BulkEditResult(updatedCount = 1))
    }

    private fun anyRequest(): MaterialRequest {
        org.mockito.Mockito.any<MaterialRequest>()
        return MaterialRequest(version = 0)
    }

    private fun draftRequest() = MaterialRequest(
        name = "Tuesday class", description = "<div>Head drops.</div>", danceCategoryId = category.id, version = 0,
        trainingEventId = session.id, figureIds = listOf(figure.id!!), markAttended = true
    )

    @Test
    fun `the note form opens prefilled from the draft, with the figures and attendance shown and carried`() {
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(draftRequest())

        val html = mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf()))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        assertEquals("Tuesday class", doc.selectFirst("input[name=name]")?.attr("value"))
        assertEquals(session.id.toString(), doc.selectFirst("input[name=trainingEventId]")?.attr("value"))
        assertNotNull(doc.selectFirst("select[name=danceCategoryId] option[selected][value=${category.id}]"))
        // The form has no figure picker, so the pins show as chips and ride along in one hidden field.
        assertTrue(doc.select("[data-draft-figures]").text().contains("Feather Step"))
        assertTrue(doc.select("[data-draft-figures]").text().contains("S Q Q"))
        assertEquals(figure.id.toString(), doc.selectFirst("input[name=figureIds]")?.attr("value"))
        assertEquals("true", doc.selectFirst("input[name=markAttended]")?.attr("value"))
        assertTrue(doc.select("[data-draft-session]").text().contains("Standard group class"))
        assertEquals(1, doc.select("input[name=figureIds]").size, "one field, so no form posts a name twice")
    }

    @Test
    fun `a figure that can no longer be read is left out of the chips and of the hidden field`() {
        val gone = UUID.randomUUID()
        `when`(danceFigureService.findById(gone)).thenThrow(EntityNotFoundException("gone"))
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(draftRequest().copy(figureIds = listOf(gone, figure.id!!)))

        val doc = Jsoup.parse(
            mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf())).andReturn().response.contentAsString
        )

        assertEquals(figure.id.toString(), doc.selectFirst("input[name=figureIds]")?.attr("value"))
    }

    @Test
    fun `a draft that is missing, foreign or saved opens a blank form`() {
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(null)

        val doc = Jsoup.parse(
            mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf())).andReturn().response.contentAsString
        )

        assertEquals("", doc.selectFirst("input[name=name]")?.attr("value") ?: "")
        assertNull(doc.selectFirst("[data-draft-figures]"))
        assertNull(doc.selectFirst("input[name=markAttended]"))
    }

    @Test
    fun `a blank new note form carries no draft fields`() {
        val doc = Jsoup.parse(mockMvc.perform(get("/materials/new").with(csrf())).andReturn().response.contentAsString)

        assertNull(doc.selectFirst("input[name=figureIds]"))
        assertNull(doc.selectFirst("input[name=markAttended]"))
    }

    @Test
    fun `saving the form pins the carried figures, records attendance and links the session`() {
        mockMvc.perform(
            post("/materials").param("name", "Tuesday class").param("version", "0")
                .param("trainingEventId", session.id.toString())
                .param("figureIds", figure.id.toString()).param("markAttended", "true").with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))

        verify(materialService).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        verify(trainingEventService).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)
        verify(trainingEventService).bulkUpdateMaterial(listOf(session.id!!), savedNote.id, null, false)
    }

    @Test
    fun `a pin or attendance that fails does not lose the saved note`() {
        doThrow(IllegalStateException("pin failed")).`when`(materialService).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        doThrow(IllegalStateException("attendance failed")).`when`(trainingEventService).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)

        mockMvc.perform(
            post("/materials").param("name", "Tuesday class").param("version", "0")
                .param("trainingEventId", session.id.toString())
                .param("figureIds", figure.id.toString()).param("markAttended", "true").with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))
    }

    @Test
    fun `a form without draft fields behaves exactly as before`() {
        mockMvc.perform(post("/materials").param("name", "Just a note").param("version", "0").with(csrf()))
            .andExpect(redirectedUrl("/materials"))

        verify(materialService, never()).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        verify(trainingEventService, never()).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)
    }
}
