package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
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
@Import(MaterialFigurePinningWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialFigurePinningWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var trainingEventService: com.jankowski.rafal.dancebook.service.TrainingEventService
    // Global NavbarAdvice and interceptor dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var ownerUser: AppUser
    private lateinit var otherUser: AppUser
    private lateinit var danceTypeWaltz: DanceType
    private lateinit var material: Material
    private lateinit var danceFigure1: DanceFigure
    private lateinit var danceFigure2: DanceFigure
    private lateinit var figure1: Figure

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    private fun <T> eq(value: T): T = org.mockito.Mockito.eq(value) ?: value

    private fun <T> capture(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
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
            username = "viewer"
            displayName = "Viewer User"
            role = Role.USER
        }

        val auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(ownerUser, null, emptyList())
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication = auth

        danceTypeWaltz = DanceType().apply {
            id = UUID.randomUUID()
            name = "English Waltz"
        }

        material = Material().apply {
            id = UUID.randomUUID()
            name = "Waltz Workshop Notes"
            owner = ownerUser
            visibility = Visibility.PUBLIC
            danceType = danceTypeWaltz
        }

        danceFigure1 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Turn"
            danceType = danceTypeWaltz
            danceClass = DanceClass.H
            alternativeTiming = "12&3"
        }

        danceFigure2 = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Reverse Turn"
            danceType = danceTypeWaltz
            danceClass = null
            alternativeTiming = null
        }

        figure1 = Figure().apply {
            id = UUID.randomUUID()
            material = this@MaterialFigurePinningWebTest.material
            danceFigure = danceFigure1
            startTime = 0
            endTime = 0
        }
        material.figures.add(figure1)

        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        `when`(materialService.findById(material.id!!)).thenReturn(material)
        `when`(materialService.findFiguresByMaterial(material.id!!)).thenReturn(listOf(figure1))
        `when`(commentService.getCommentsForMaterial(material.id!!)).thenReturn(emptyList())
    }

    @Test
    fun `viewMaterial renders pinned figure with name, style, class, timing, link and no video artifacts`() {
        val result = mockMvc.perform(get("/materials/{id}", material.id))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/view"))
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        val pinnedSection = doc.selectFirst("#pinnedFiguresSection")
        assertNotNull(pinnedSection, "Pinned figures section must be present")

        // Contains figure name and links to /dance-figures/{id}
        val figureLink = pinnedSection!!.selectFirst("a[href='/dance-figures/${danceFigure1.id}']")
        assertNotNull(figureLink, "Must link to dance figure page")
        assertEquals("Natural Turn", figureLink!!.text().trim())

        // Detail elements: Style, Class, Timing
        assertTrue(pinnedSection.text().contains("English Waltz"))
        assertTrue(pinnedSection.text().contains("Class H"))
        assertTrue(pinnedSection.text().contains("12&3"))

        // Video artifacts must be absent: no "0 – 0", no play overlay
        assertFalse(pinnedSection.text().contains("0 – 0"), "Must not display 0 - 0 timing")
        assertNull(pinnedSection.selectFirst(".material-symbols-outlined:contains(play_arrow)"), "No play arrow icon")
        assertNull(pinnedSection.selectFirst(".js-edit-figure-btn"), "No edit timing button")
    }

    @Test
    fun `viewMaterial omits missing timing without rendering empty brackets`() {
        val figureWithoutTiming = Figure().apply {
            id = UUID.randomUUID()
            this.material = this@MaterialFigurePinningWebTest.material
            this.danceFigure = danceFigure2
            startTime = 0
            endTime = 0
        }
        `when`(materialService.findFiguresByMaterial(material.id!!)).thenReturn(listOf(figureWithoutTiming))

        val result = mockMvc.perform(get("/materials/{id}", material.id))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)
        val pinnedSection = doc.selectFirst("#pinnedFiguresSection")
        assertNotNull(pinnedSection)

        assertEquals("Reverse Turn", pinnedSection!!.selectFirst("a[href='/dance-figures/${danceFigure2.id}']")?.text()?.trim())
        // Must not contain "[]" anywhere
        assertFalse(pinnedSection.html().contains("[]"), "Missing timing must not render empty brackets []")
    }

    @Test
    fun `owner sees pin and unpin actions, other user does not`() {
        // Owner request
        val ownerHtml = mockMvc.perform(get("/materials/{id}", material.id))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val ownerDoc = Jsoup.parse(ownerHtml)
        assertNotNull(ownerDoc.selectFirst("button[aria-label='Pin figure']"), "Owner sees Pin figure button")
        val unpinBtn = ownerDoc.selectFirst("button[aria-label='Unpin figure']")
        assertNotNull(unpinBtn, "Owner sees Unpin figure button")
        assertTrue(unpinBtn!!.hasClass("min-w-[44px]"), "Unpin button must have min 44px width touch target")
        assertTrue(unpinBtn.hasClass("min-h-[44px]"), "Unpin button must have min 44px height touch target")

        // Other user request
        val auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(otherUser, null, emptyList())
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication = auth
        `when`(appUserService.getCurrentUser()).thenReturn(otherUser)
        val otherHtml = mockMvc.perform(get("/materials/{id}", material.id))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val otherDoc = Jsoup.parse(otherHtml)
        assertNull(otherDoc.selectFirst("button[aria-label='Pin figure']"), "Non-owner cannot see Pin figure button")
        assertNull(otherDoc.selectFirst("button[aria-label='Unpin figure']"), "Non-owner cannot see Unpin figure button")
    }

    @Test
    fun `showFigurePicker returns dialog narrowing to note style by default`() {
        `when`(danceFigureService.findAll(typeIds = listOf(danceTypeWaltz.id!!), sortBy = "name_asc"))
            .thenReturn(listOf(danceFigure1, danceFigure2))

        val result = mockMvc.perform(
            get("/materials/{id}/figures/picker", material.id)
                .header("HX-Request", "true")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-picker :: figurePickerDialog"))
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        val dialog = doc.selectFirst("dialog#figurePickerDialog")
        assertNotNull(dialog, "Must return native dialog with id figurePickerDialog")

        // Search input present with htmx attributes
        val searchInput = dialog!!.selectFirst("input#figureSearchQuery")
        assertNotNull(searchInput)

        // All styles checkbox present and unchecked by default for styled note
        val allStylesCheckbox = dialog.selectFirst("input#filterAllStyles[type=checkbox]")
        assertNotNull(allStylesCheckbox)
        assertFalse(allStylesCheckbox!!.hasAttr("checked"), "Checkbox should be unchecked by default")

        // Figure 1 is already pinned, Figure 2 is unpinned
        assertTrue(html.contains("Natural Turn"))
        assertTrue(html.contains("Reverse Turn"))
        assertTrue(html.contains("Pinned"), "Already pinned figure must show Pinned badge")
        assertNotNull(dialog.selectFirst("form[action*='/figures'] input[value='${danceFigure2.id}']"), "Unpinned figure must have pin form")
    }

    @Test
    fun `searchFigures filters by name and narrows to note style or widens to all styles`() {
        `when`(danceFigureService.findAll(typeIds = listOf(danceTypeWaltz.id!!), nameSearch = "Nat", sortBy = "name_asc"))
            .thenReturn(listOf(danceFigure1))
        `when`(danceFigureService.findAll(typeIds = null, nameSearch = "Turn", sortBy = "name_asc"))
            .thenReturn(listOf(danceFigure1, danceFigure2))

        // Search narrowed to note's style
        mockMvc.perform(
            get("/materials/{id}/figures/search", material.id)
                .param("query", "Nat")
                .param("allStyles", "false")
                .header("HX-Request", "true")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-picker :: figurePickerResults"))
            .andExpect(content().string(containsString("Natural Turn")))

        // Search widened to all styles
        mockMvc.perform(
            get("/materials/{id}/figures/search", material.id)
                .param("query", "Turn")
                .param("allStyles", "true")
                .header("HX-Request", "true")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-picker :: figurePickerResults"))
            .andExpect(content().string(containsString("Natural Turn")))
            .andExpect(content().string(containsString("Reverse Turn")))
    }

    @Test
    fun `addFigure pins figure with only danceFigureId and returns HTMX response with oob swap`() {
        val newFigure = Figure().apply {
            id = UUID.randomUUID()
            this.material = this@MaterialFigurePinningWebTest.material
            this.danceFigure = danceFigure2
            startTime = 0
            endTime = 0
        }
        `when`(materialService.addFigure(anyNonNull(UUID.randomUUID()), anyNonNull(FigureRequest()))).thenReturn(newFigure)

        mockMvc.perform(
            post("/materials/{id}/figures", material.id)
                .param("danceFigureId", danceFigure2.id.toString())
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/fragments/figure-picker :: figurePinResponse"))
            .andExpect(content().string(containsString("Pinned")))
            .andExpect(content().string(containsString("hx-swap-oob=\"outerHTML\"")))

        val captor = ArgumentCaptor.forClass(FigureRequest::class.java)
        verify(materialService).addFigure(eq(material.id!!), capture(captor, FigureRequest()))
        assertNull(captor.value.id, "Path variable materialId must not bind into FigureRequest.id")
        assertEquals(danceFigure2.id, captor.value.danceFigureId)
        assertEquals(0, captor.value.startTime)
        assertEquals(0, captor.value.endTime)
        org.mockito.Mockito.verify(materialService, org.mockito.Mockito.never()).updateFigure(
            anyNonNull(UUID.randomUUID()),
            anyNonNull(UUID.randomUUID()),
            anyNonNull(FigureRequest())
        )
    }

    @Test
    fun `addFigure through real binding path without id calls addFigure and never updateFigure`() {
        val newFigure = Figure().apply {
            id = UUID.randomUUID()
            this.material = this@MaterialFigurePinningWebTest.material
            this.danceFigure = danceFigure2
            startTime = 0
            endTime = 0
        }
        `when`(materialService.addFigure(anyNonNull(UUID.randomUUID()), anyNonNull(FigureRequest()))).thenReturn(newFigure)

        mockMvc.perform(
            post("/materials/{materialId}/figures", material.id)
                .param("danceFigureId", danceFigure2.id.toString())
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${material.id}"))

        val captor = ArgumentCaptor.forClass(FigureRequest::class.java)
        verify(materialService).addFigure(eq(material.id!!), capture(captor, FigureRequest()))
        assertNull(captor.value.id, "FigureRequest.id must remain null when client sends no id")
        assertEquals(danceFigure2.id, captor.value.danceFigureId)
        org.mockito.Mockito.verify(materialService, org.mockito.Mockito.never()).updateFigure(
            anyNonNull(UUID.randomUUID()),
            anyNonNull(UUID.randomUUID()),
            anyNonNull(FigureRequest())
        )
    }

    @Test
    fun `viewMaterial does not load availableFigures into model`() {
        val result = mockMvc.perform(get("/materials/{id}", material.id))
            .andExpect(status().isOk)
            .andReturn()

        assertNull(result.modelAndView?.model?.get("availableFigures"), "availableFigures must not be loaded for note view")
    }

    @Test
    fun `addFigure redirects on standard non-HTMX post`() {
        val newFigure = Figure().apply {
            id = UUID.randomUUID()
            this.material = this@MaterialFigurePinningWebTest.material
            this.danceFigure = danceFigure2
            startTime = 0
            endTime = 0
        }
        `when`(materialService.addFigure(anyNonNull(UUID.randomUUID()), anyNonNull(FigureRequest()))).thenReturn(newFigure)

        mockMvc.perform(
            post("/materials/{id}/figures", material.id)
                .param("danceFigureId", danceFigure2.id.toString())
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${material.id}"))
    }

    @Test
    fun `deleteFigure unpins figure and returns updated pinnedFiguresSection over HTMX`() {
        mockMvc.perform(
            post("/materials/{mId}/figures/{fId}/delete", material.id, figure1.id)
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("materials/view :: pinnedFiguresSection"))

        verify(materialService).removeFigure(eq(material.id!!), eq(figure1.id!!))
    }

    @Test
    fun `deleteFigure redirects on non-HTMX post`() {
        mockMvc.perform(
            post("/materials/{mId}/figures/{fId}/delete", material.id, figure1.id)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${material.id}"))

        verify(materialService).removeFigure(eq(material.id!!), eq(figure1.id!!))
    }
}
