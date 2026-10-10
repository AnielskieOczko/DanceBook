package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
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
@Import(DanceFigureMedalWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceFigureMedalWebTest {

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

    @Test
    fun `figure create form renders medal radio options with None checked by default`() {
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
        val radios = doc.select("input[name=medalLevel]")
        assertEquals(4, radios.size, "Must have 4 medal radio buttons (None, Bronze, Silver, Gold)")

        val noneRadio = doc.selectFirst("input[name=medalLevel][value='']")
        assertNotNull(noneRadio, "None radio button must exist")
        assertTrue(noneRadio!!.hasAttr("checked"), "None radio button must be checked by default")

        val bronzeRadio = doc.selectFirst("input[name=medalLevel][value=BRONZE]")
        val silverRadio = doc.selectFirst("input[name=medalLevel][value=SILVER]")
        val goldRadio = doc.selectFirst("input[name=medalLevel][value=GOLD]")
        assertNotNull(bronzeRadio, "Bronze radio must exist")
        assertNotNull(silverRadio, "Silver radio must exist")
        assertNotNull(goldRadio, "Gold radio must exist")
        assertFalse(bronzeRadio!!.hasAttr("checked"), "Bronze must not be checked")
        assertFalse(silverRadio!!.hasAttr("checked"), "Silver must not be checked")
        assertFalse(goldRadio!!.hasAttr("checked"), "Gold must not be checked")
    }

    @Test
    fun `figure edit form pre-selects medal level and displays medal badge in header`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
            danceClass = DanceClass.E
            medalLevel = MedalLevel.SILVER
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
        val silverRadio = doc.selectFirst("input[name=medalLevel][value=SILVER]")
        assertNotNull(silverRadio, "Silver radio option must exist")
        assertTrue(silverRadio!!.hasAttr("checked"), "Silver radio must be selected for figure with Silver medal")

        val noneRadio = doc.selectFirst("input[name=medalLevel][value='']")
        assertFalse(noneRadio!!.hasAttr("checked"), "None radio must not be selected")

        // Edit header preview badge
        val headerMedalBadge = doc.selectFirst(".medal-badge-silver")
        assertNotNull(headerMedalBadge, "Header should render silver medal badge preview")
    }

    @Test
    fun `submitting figure create form with medal level creates figure with medalLevel`() {
        val typeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = typeId
            name = "Waltz"
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        mockMvc.perform(
            post("/dance-figures")
                .param("name", "Natural Spin Turn")
                .param("danceTypeId", typeId.toString())
                .param("danceClass", "E")
                .param("medalLevel", "GOLD")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dance-figures"))

        val captor = ArgumentCaptor.forClass(DanceFigureRequest::class.java)
        verify(danceFigureService).create(capture(captor, DanceFigureRequest()))
        assertEquals("Natural Spin Turn", captor.value.name)
        assertEquals(MedalLevel.GOLD, captor.value.medalLevel)
    }

    @Test
    fun `submitting figure create form with None medal level creates figure with null medalLevel`() {
        val typeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = typeId
            name = "Waltz"
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))

        mockMvc.perform(
            post("/dance-figures")
                .param("name", "Natural Spin Turn")
                .param("danceTypeId", typeId.toString())
                .param("danceClass", "E")
                .param("medalLevel", "")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dance-figures"))

        val captor = ArgumentCaptor.forClass(DanceFigureRequest::class.java)
        verify(danceFigureService).create(capture(captor, DanceFigureRequest()))
        assertEquals("Natural Spin Turn", captor.value.name)
        assertNull(captor.value.medalLevel)
    }

    @Test
    fun `submitting figure edit form updates figure medalLevel`() {
        val figureId = UUID.randomUUID()
        val typeId = UUID.randomUUID()

        mockMvc.perform(
            post("/dance-figures/{id}", figureId)
                .param("name", "Natural Spin Turn")
                .param("danceTypeId", typeId.toString())
                .param("danceClass", "E")
                .param("medalLevel", "BRONZE")
                .param("version", "0")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dance-figures"))

        val captor = ArgumentCaptor.forClass(DanceFigureRequest::class.java)
        verify(danceFigureService).update(eq(figureId), capture(captor, DanceFigureRequest()))
        assertEquals("Natural Spin Turn", captor.value.name)
        assertEquals(MedalLevel.BRONZE, captor.value.medalLevel)
    }

    @Test
    fun `catalog list renders medal filter and passes selected medalLevel to service`() {
        `when`(danceFigureService.findAll(null, null, null, null, null, null, MedalLevel.SILVER)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/dance-figures").param("medalLevel", "SILVER").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        verify(danceFigureService).findAll(null, null, null, null, null, null, MedalLevel.SILVER)

        val doc = Jsoup.parse(html)
        val select = doc.selectFirst("select[name=medalLevel]")
        assertNotNull(select, "Medal level filter select must be present")
        val selectedOption = select!!.selectFirst("option[value=SILVER]")
        assertNotNull(selectedOption, "Silver option must exist")
        assertTrue(selectedOption!!.hasAttr("selected"), "Silver option must be selected")
    }

    @Test
    fun `catalog list renders medal badge next to class in table and cards`() {
        val typeId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = typeId
            name = "Waltz"
        }
        val goldFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Gold Figure"
            this.danceType = danceType
            danceClass = DanceClass.D
            medalLevel = MedalLevel.GOLD
        }
        val plainFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Plain Figure"
            this.danceType = danceType
            danceClass = DanceClass.E
            medalLevel = null
        }

        `when`(danceFigureService.findAll(null, null, null, null, null, null, null))
            .thenReturn(listOf(goldFigure, plainFigure))
        `when`(danceFigureService.findFigureIdsWithSteps(listOf(goldFigure.id!!, plainFigure.id!!)))
            .thenReturn(emptySet())
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/dance-figures").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val goldBadges = doc.select(".medal-badge-gold")
        assertTrue(goldBadges.isNotEmpty(), "Catalog must render gold medal badge for Gold Figure")

        // Gold badge contains 3 filled rects
        val goldSvg = goldBadges.first()!!.selectFirst("svg")
        assertNotNull(goldSvg, "Medal badge should contain an SVG rank bars icon")
        val filledBars = goldSvg!!.select("rect[fill=currentColor]")
        assertEquals(3, filledBars.size, "Gold badge must have 3 filled bars")
    }

    @Test
    fun `figure view page renders medal badge in header and core details when medal is present`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
            danceClass = DanceClass.E
            medalLevel = MedalLevel.GOLD
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))

        val html = mockMvc.perform(get("/dance-figures/{id}", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val goldBadges = doc.select(".medal-badge-gold")
        assertEquals(2, goldBadges.size, "Figure view must render gold badge in header and in core details")

        // Check header badge text includes "Gold"
        val headerBadge = goldBadges[0]
        assertTrue(headerBadge.text().contains("Gold"), "View header medal badge must include medal word")

        // Core details section shows medal badge
        val coreBadge = goldBadges[1]
        assertTrue(coreBadge.text().contains("Gold"), "Core details must render medal badge under Minimum Level")
    }

    @Test
    fun `figure view page omits medal badge when medal level is null`() {
        val figureId = UUID.randomUUID()
        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        val figure = DanceFigure().apply {
            id = figureId
            name = "Natural Spin Turn"
            this.danceType = danceType
            danceClass = DanceClass.E
            medalLevel = null
        }

        `when`(danceFigureService.findById(figureId)).thenReturn(figure)
        `when`(danceFigureService.findByDanceType(danceType.id!!)).thenReturn(listOf(figure))

        val html = mockMvc.perform(get("/dance-figures/{id}", figureId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val medalBadges = doc.select(".medal-badge-bronze, .medal-badge-silver, .medal-badge-gold")
        assertTrue(medalBadges.isEmpty(), "Figure view must not render any medal badge when medalLevel is null")
    }

    @Test
    fun `figures catalog list renders localized medal filter in Polish`() {
        `when`(danceFigureService.findAll(null, null, null, null, null, null, null)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/dance-figures").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val doc = Jsoup.parse(html)
        val select = doc.selectFirst("select[name=medalLevel]")
        assertNotNull(select, "Medal level filter select must be present")
        val options = select!!.select("option")
        val optionTexts = options.map { it.text().trim() }
        assertTrue(optionTexts.contains("Wszystkie medale"), "Should contain Polish 'Wszystkie medale' option: $optionTexts")
        assertTrue(optionTexts.contains("Brąz"), "Should contain Polish 'Brąz' option: $optionTexts")
        assertTrue(optionTexts.contains("Srebro"), "Should contain Polish 'Srebro' option: $optionTexts")
        assertTrue(optionTexts.contains("Złoto"), "Should contain Polish 'Złoto' option: $optionTexts")
    }
}
