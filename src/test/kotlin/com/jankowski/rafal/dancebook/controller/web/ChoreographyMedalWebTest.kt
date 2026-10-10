package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.Choreography
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.KnowledgeRetrievalService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [ChoreographyWebController::class],
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
@Import(ChoreographyMedalWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class ChoreographyMedalWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var choreographyService: ChoreographyService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService

    // NavbarAdvice & controller optional dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var knowledgeRetrievalService: KnowledgeRetrievalService

    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }

    private fun choreo(level: MedalLevel?) = Choreography().apply {
        id = UUID.randomUUID()
        name = "Routine"
        medalLevel = level
        danceType = DanceType().apply { id = UUID.randomUUID(); name = "Waltz" }
        description = "d"
        isPublic = false
        createdAt = java.time.LocalDateTime.now()
        updatedAt = java.time.LocalDateTime.now()
    }

    @Test
    fun `create form renders medal radios with None checked`() {
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val doc = Jsoup.parse(mockMvc.perform(get("/choreographies/new").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(4, doc.select("input[name=medalLevel]").size)
        assertTrue(doc.selectFirst("input[name=medalLevel][value='']")!!.hasAttr("checked"))
        assertFalse(doc.selectFirst("input[name=medalLevel][value=GOLD]")!!.hasAttr("checked"))
        assertNotNull(doc.selectFirst("label svg"), "medal badge glyph in picker")
    }

    @Test
    fun `list renders filter with selection and badge only for medalled choreographies`() {
        `when`(choreographyService.findByCurrentUser(null)).thenReturn(listOf(choreo(MedalLevel.SILVER), choreo(null)))
        val doc = Jsoup.parse(mockMvc.perform(get("/choreographies").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)
        assertNotNull(doc.selectFirst("select[name=medalLevel]"))
        assertEquals(1, doc.select("#choreographies-list [role=img][aria-label='Silver medal']").size)
        assertEquals(1, doc.select("#choreographies-list [role=img]").size)
    }

    @Test
    fun `list passes selected medal to service and marks option selected`() {
        `when`(choreographyService.findByCurrentUser(MedalLevel.GOLD)).thenReturn(emptyList())
        val doc = Jsoup.parse(mockMvc.perform(get("/choreographies").param("medalLevel", "GOLD").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)
        verify(choreographyService).findByCurrentUser(MedalLevel.GOLD)
        assertTrue(doc.selectFirst("select[name=medalLevel] option[value=GOLD]")!!.hasAttr("selected"))
    }

    @Test
    fun `view page shows medal badge with word and none draws nothing`() {
        val c = choreo(MedalLevel.GOLD)
        `when`(choreographyService.findById(c.id!!)).thenReturn(c)
        val html = mockMvc.perform(get("/choreographies/${c.id}").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString
        assertNotNull(Jsoup.parse(html).selectFirst("[role=img][aria-label='Gold medal']"))
        val n = choreo(null)
        `when`(choreographyService.findById(n.id!!)).thenReturn(n)
        val html2 = mockMvc.perform(get("/choreographies/${n.id}").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString
        assertNull(Jsoup.parse(html2).selectFirst("[aria-label$=' medal']"))
    }
}
