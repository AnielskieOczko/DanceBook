package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
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
import jakarta.persistence.EntityNotFoundException
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

/**
 * "Write a note from this session" on the home page (#147): the note form opens prefilled from
 * the session, and saving links the new note back to it.
 */
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
@Import(MaterialFromSessionWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialFromSessionWebTest {

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
    // Global NavbarAdvice and interceptor dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val category = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Standard"
    }

    private val session = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Standard group class"
        startTime = LocalDateTime.of(2026, 9, 23, 19, 0)
        endTime = LocalDateTime.of(2026, 9, 23, 20, 30)
        materialsUrl = "https://example.com/handout"
        segments = mutableListOf(
            TrainingEventSegment().apply { danceCategory = null; durationMinutes = 15 },
            TrainingEventSegment().apply { danceCategory = category; durationMinutes = 45 }
        )
    }

    private val savedNote = Material().apply { id = UUID.randomUUID() }

    @BeforeEach
    fun setUp() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "dancer"
            displayName = "Dancer"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))
        `when`(danceTypeService.findByCategoryId(category.id!!)).thenReturn(emptyList())
        `when`(trainingEventService.findById(session.id!!)).thenReturn(session)
        `when`(materialService.create(anyRequest())).thenReturn(savedNote)
        `when`(
            trainingEventService.bulkUpdateMaterial(
                listOf(session.id!!), savedNote.id, session.materialsUrl, false
            )
        ).thenReturn(BulkEditResult(updatedCount = 1))
    }

    /** Mockito's `any()` hands Kotlin a null, which a non-null parameter refuses, so give it a real dummy. */
    private fun anyRequest(): MaterialRequest {
        org.mockito.Mockito.any<MaterialRequest>()
        return MaterialRequest(version = 0)
    }

    @Test
    fun `the form opens prefilled with the session's title and date, its style and a link back`() {
        val html = mockMvc.perform(get("/materials/new").param("fromSession", session.id.toString()).with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        assertEquals("Standard group class — 23 Sep 2026", doc.selectFirst("input[name=name]")?.attr("value"))
        assertEquals(session.id.toString(), doc.selectFirst("input[name=trainingEventId]")?.attr("value"))
        // The first segment with a style decides it; a leading unstyled segment is skipped.
        assertNotNull(doc.selectFirst("select[name=danceCategoryId] option[selected][value=${category.id}]"))
        verify(danceTypeService).findByCategoryId(category.id!!)
    }

    @Test
    fun `without a session the form opens blank`() {
        val html = mockMvc.perform(get("/materials/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        assertEquals("", doc.selectFirst("input[name=name]")?.attr("value") ?: "")
        assertEquals("", doc.selectFirst("input[name=trainingEventId]")?.attr("value") ?: "")
    }

    @Test
    fun `a session the user cannot see is ignored and the form opens blank`() {
        val hidden = UUID.randomUUID()
        `when`(trainingEventService.findById(hidden)).thenThrow(EntityNotFoundException("no such session"))

        val html = mockMvc.perform(get("/materials/new").param("fromSession", hidden.toString()).with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        assertEquals("", doc.selectFirst("input[name=name]")?.attr("value") ?: "")
        assertEquals("", doc.selectFirst("input[name=trainingEventId]")?.attr("value") ?: "")
    }

    @Test
    fun `saving a note written from a session links it to the session and lands on the note`() {
        mockMvc.perform(
            post("/materials")
                .param("name", "Standard group class — 23 Sep 2026")
                .param("version", "0")
                .param("trainingEventId", session.id.toString())
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))

        // The session's own external link is passed back, because the update replaces both.
        verify(trainingEventService).bulkUpdateMaterial(
            listOf(session.id!!), savedNote.id, session.materialsUrl, false
        )
    }

    @Test
    fun `a note saved without a session is not linked to anything`() {
        mockMvc.perform(
            post("/materials").param("name", "Just a note").param("version", "0").with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials"))

        verify(trainingEventService, never()).bulkUpdateMaterial(
            listOf(session.id!!), savedNote.id, session.materialsUrl, false
        )
    }

    @Test
    fun `a failed link keeps the saved note and still lands on it`() {
        doThrow(IllegalStateException("Calendar sync failed")).`when`(trainingEventService)
            .bulkUpdateMaterial(listOf(session.id!!), savedNote.id, session.materialsUrl, false)

        mockMvc.perform(
            post("/materials")
                .param("name", "Standard group class — 23 Sep 2026")
                .param("version", "0")
                .param("trainingEventId", session.id.toString())
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))
    }
}
