package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
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
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
@Import(DanceFigureViewRenderingTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceFigureViewRenderingTest {

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

    // Global NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    @Test
    fun `figure view with null notes renders fallback message and produces no rich-text element`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
            notes = null
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))

        mockMvc.perform(get("/dance-figures/{id}", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No notes available for this figure.")))
            .andExpect(content().string(not(containsString("rich-text text-on-surface"))))
    }

    @Test
    fun `figure view with rich text notes renders content and omits fallback message`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
            notes = "<div>Key note for <strong>Leader</strong></div>"
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))

        mockMvc.perform(get("/dance-figures/{id}", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Key note for <strong>Leader</strong>")))
            .andExpect(content().string(not(containsString("No notes available for this figure."))))
    }

    @Test
    fun `figure edit page displays figure name in page header`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        val html = mockMvc.perform(get("/dance-figures/{id}/edit", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val pageTitle = doc.selectFirst(".page-title")
        assertNotNull(pageTitle, "Page title element must exist")
        assertTrue(pageTitle!!.text().contains("Edit Natural Spin Turn"), "Page title must contain 'Edit Natural Spin Turn'")
    }

    @Test
    fun `figure edit page with blank name falls back to Edit Figure in page header`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = ""
            this.danceType = danceType
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        val html = mockMvc.perform(get("/dance-figures/{id}/edit", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val pageTitle = doc.selectFirst(".page-title")
        assertNotNull(pageTitle, "Page title element must exist")
        assertEquals("Edit Figure", pageTitle!!.text().trim(), "Blank name should fall back to 'Edit Figure'")
    }

    @Test
    fun `figure create page displays Create New Figure in page header`() {
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        val html = mockMvc.perform(get("/dance-figures/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val pageTitle = doc.selectFirst(".page-title")
        assertNotNull(pageTitle, "Page title element must exist")
        assertEquals("Create New Figure", pageTitle!!.text().trim(), "Create page title must be 'Create New Figure'")
    }

    @Test
    fun `figure edit page layout keeps steps breakdown full width outside top metadata grid`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
        }
        val stepSet = com.jankowski.rafal.dancebook.model.DanceFigureStepSet().apply {
            id = UUID.randomUUID()
            name = "Default"
            isDefault = true
            danceFigure = figure
        }
        val step1 = com.jankowski.rafal.dancebook.model.DanceFigureStep().apply {
            id = UUID.randomUUID()
            stepNumber = 1
            timing = "1"
            role = "LEADER"
            foot = "RF"
            action = "Forward right foot"
            danceFigureStepSet = stepSet
        }
        stepSet.steps.add(step1)
        figure.stepSets.add(stepSet)

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        val html = mockMvc.perform(get("/dance-figures/{id}/edit", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("#figureForm")
        assertNotNull(form, "Figure form must be present")

        // Top metadata grid: contains exactly 2 top-level children (lg:col-span-2 and lg:col-span-1)
        val topGrid = form!!.children().first { it.hasClass("grid") && it.hasClass("grid-cols-1") && it.classNames().contains("lg:grid-cols-3") }
        assertEquals(2, topGrid.childrenSize(), "Top metadata grid must have exactly 2 child columns (left 2/3 and right 1/3)")

        // Step sets card must NOT be nested inside the top metadata grid
        assertNull(topGrid.selectFirst("#step-sets-card"), "Step sets card must not be nested inside top metadata grid")

        // Step sets card must be inside a full-width container which is a direct child of the form
        val stepSetsCard = form.selectFirst("#step-sets-card")
        assertNotNull(stepSetsCard, "Step sets card must be present")
        val stepSetsWrapper = stepSetsCard!!.parent()
        assertNotNull(stepSetsWrapper)
        assertTrue(stepSetsWrapper!!.hasClass("w-full"), "Step sets wrapper must be full width (w-full)")
        assertEquals(form, stepSetsWrapper.parent(), "Step sets wrapper must be a direct child of #figureForm")
    }
}

