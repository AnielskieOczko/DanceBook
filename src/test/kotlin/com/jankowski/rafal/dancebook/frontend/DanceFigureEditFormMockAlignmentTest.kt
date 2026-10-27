package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.DanceFigureWebController
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceClass
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
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Guards that the dance figure edit/create form strictly aligns with the approved mock (Issue #300).
 *
 * Verifies:
 * 1. Zero horizontal scroll on steps table and resources sections (no overflow-x-auto containers).
 * 2. Proper assistant bar clearance padding (`pb-28 sm:pb-32`).
 * 3. Modern filled pill and dense styling for form controls without old rectangular focus rings.
 * 4. Responsive table structure reflowing smoothly on mobile (< 1024px) and forming budgeted grid on desktop.
 * 5. Locale parity across English and Polish with no literal checkmarks or broken translation keys.
 */
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
@Import(DanceFigureEditFormMockAlignmentTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceFigureEditFormMockAlignmentTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired private lateinit var mockMvc: MockMvc

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
    private lateinit var defaultDanceType: DanceType
    private val formTemplateFile = File("src/main/resources/templates/dance-figures/form.html")
    private val inputCssFile = File("src/main/resources/frontend/input.css")

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

        defaultDanceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Waltz"
        }
        `when`(danceTypeService.findAll()).thenReturn(listOf(defaultDanceType))
    }

    private fun sampleFigure(): DanceFigure {
        val fig = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Turn"
            danceType = defaultDanceType
            danceClass = DanceClass.D
            startingPosition = "Closed hold"
            endingPosition = "Closed hold"
            startingFootLeader = "RF"
            startingFootFollower = "LF"
            endingFootLeader = "LF"
            endingFootFollower = "RF"
        }

        val stepSet = DanceFigureStepSet().apply {
            id = UUID.randomUUID()
            name = "Standard"
            isDefault = true
            danceFigure = fig
        }

        val stepL = DanceFigureStep().apply {
            id = UUID.randomUUID()
            stepNumber = 1
            role = "LEADER"
            timing = "1"
            foot = "RF"
            action = "Forward"
            footwork = "HT"
            alignment = "Facing DC"
            amountOfTurn = "None"
            danceFigureStepSet = stepSet
        }

        val stepF = DanceFigureStep().apply {
            id = UUID.randomUUID()
            stepNumber = 1
            role = "FOLLOWER"
            timing = "1"
            foot = "LF"
            action = "Back"
            footwork = "T"
            alignment = "Backing DC"
            amountOfTurn = "None"
            danceFigureStepSet = stepSet
        }

        stepSet.steps.addAll(listOf(stepL, stepF))
        fig.stepSets.add(stepSet)

        val link = com.jankowski.rafal.dancebook.model.DanceFigureLink().apply {
            id = UUID.randomUUID()
            title = "Natural Turn Video"
            url = "https://example.org/waltz/natural-turn"
            type = "video"
            danceFigure = fig
        }
        fig.links.add(link)

        return fig
    }

    @Test
    fun `form template has no horizontal scrolling wrappers around steps, links, or jump nav`() {
        assertTrue(formTemplateFile.exists(), "Form template must exist")
        val content = formTemplateFile.readText()
        val doc = Jsoup.parse(content)

        val stepsWrapper = doc.select(".js-steps-table-wrapper")
        assertTrue(stepsWrapper.isNotEmpty(), "Steps wrapper must exist")
        for (wrapper in stepsWrapper) {
            assertFalse(wrapper.hasClass("overflow-x-auto"), "Steps wrapper must NOT have overflow-x-auto")
            assertTrue(wrapper.hasClass("steps-table"), "Steps wrapper must have steps-table class")
        }

        val linksSec = doc.select("#sec-links")
        assertTrue(linksSec.isNotEmpty(), "Links section must exist")
        assertFalse(linksSec.html().contains("w-full overflow-x-auto"), "Links table must NOT have overflow-x-auto")

        val jumpNav = doc.select("nav[aria-label='Sections'], nav[data-sec]")
        for (nav in jumpNav) {
            assertFalse(nav.hasClass("overflow-x-auto"), "Section jump nav must NOT have overflow-x-auto")
        }
    }

    @Test
    fun `links table row structure aligns columns with desktop header and orders children correctly`() {
        val content = formTemplateFile.readText()
        val doc = Jsoup.parse(content)

        val header = doc.select("#sec-links .grid").first()
        org.junit.jupiter.api.Assertions.assertNotNull(header)
        assertTrue(header!!.hasClass("lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_150px_36px]"))

        val linkRow = doc.select(".link-row").first()
        org.junit.jupiter.api.Assertions.assertNotNull(linkRow)
        assertTrue(linkRow!!.hasClass("lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_150px_36px]"))

        // Verify child DOM order: 1=title, 2=url, 3=type, 4=remove
        val children = linkRow.children()
        org.junit.jupiter.api.Assertions.assertEquals(4, children.size)
        assertTrue(children[0].select("input[th:name*='title']").isNotEmpty(), "Child 0 must be Title")
        assertTrue(children[1].select("input[th:name*='url']").isNotEmpty(), "Child 1 must be Address/URL")
        assertTrue(children[2].select("select[th:name*='type']").isNotEmpty(), "Child 2 must be Type")
        assertTrue(children[3].select(".js-remove-link-btn").isNotEmpty(), "Child 3 must be Remove Button")
    }

    @Test
    fun `dense textarea matches mock with vertical resize, relaxed line height, and no overflow clipping`() {
        val css = inputCssFile.readText()
        val denseTextareaBlock = css.substringAfter("@utility dense-textarea").substringBefore("@utility")
        assertTrue(denseTextareaBlock.contains("resize-y"), "dense-textarea must have vertical resize handle matching mock")
        assertTrue(denseTextareaBlock.contains("line-height: 1.4"), "dense-textarea must have line-height 1.4 matching mock")
        assertFalse(denseTextareaBlock.contains("overflow-y-hidden"), "dense-textarea must not clip content with overflow-y-hidden")
    }

    @Test
    fun `form template incorporates assistant bar clearance, feet-matrix, and set-tabs`() {
        val content = formTemplateFile.readText()
        assertTrue(content.contains("pb-48") && content.contains("sm:pb-32"),
            "Form container must have bottom padding pb-48 sm:pb-32 for phone sticky bar and bottom nav clearance")
        assertTrue(content.contains("feet-matrix"), "Positions and feet must use feet-matrix layout")
        assertTrue(content.contains("set-tabs"), "Variant switcher must use set-tabs")
        assertTrue(content.contains("set-tabs-btn"), "Variant switcher buttons must use set-tabs-btn")
        assertTrue(content.contains("ed-name"), "Figure name input must use ed-name pill styling")
        assertFalse(content.contains(">✓<"), "Variant tabs must not contain literal checkmark character")
    }

    @Test
    fun `sticky-bottom-bar utility clears mobile bottom nav and safe area inset`() {
        val css = inputCssFile.readText()
        assertTrue(css.contains("@utility sticky-bottom-bar"), "input.css must declare @utility sticky-bottom-bar")
        assertTrue(css.contains("env(safe-area-inset-bottom"), "sticky-bottom-bar must account for safe-area-inset-bottom")
        assertTrue(
            css.contains("--bottom-nav-height") || css.contains("69px"),
            "sticky-bottom-bar must clear bottom nav height (69px or --bottom-nav-height)"
        )
        assertTrue(
            css.contains("@media (min-width: 1280px)") || css.contains("@variant xl"),
            "sticky-bottom-bar must clear bottom offset at xl breakpoint where bottom nav is hidden"
        )
    }

    @Test
    fun `input css excludes dense and pill inputs from default rectangular focus ring`() {
        assertTrue(inputCssFile.exists(), "input.css must exist")
        val css = inputCssFile.readText()

        assertTrue(css.contains("dense-input"), "input.css must define dense-input")
        assertTrue(css.contains("pill-input"), "input.css must define pill-input")
        assertTrue(css.contains("ed-name"), "input.css must define ed-name")
        assertTrue(css.contains("set-tabs"), "input.css must define set-tabs")
        assertTrue(css.contains("steps-table"), "input.css must define steps-table")

        // Check rounded focus styling
        assertTrue(
            css.contains("focus:border-primary focus:ring-3 focus:ring-primary/22") &&
            !css.contains("focus-visible:ring-offset-0!") &&
            css.contains(".dense-input, .dense-select, .dense-textarea, .pill-input, .ed-name"),
            "Dense and pill inputs use the single #214 focus (border plus 3px ring) and are exempt from the global focus-visible ring"
        )
    }

    @Test
    fun `renders edit figure form in English with correct column labels and controls`() {
        val fig = sampleFigure()
        `when`(danceFigureService.findById(fig.id!!)).thenReturn(fig)
        `when`(danceFigureService.findByDanceType(fig.danceType!!.id!!)).thenReturn(listOf(fig))

        mockMvc.perform(get("/dance-figures/{id}/edit", fig.id).locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Natural Turn")))
            .andExpect(content().string(containsString("feet-matrix")))
            .andExpect(content().string(containsString("set-tabs")))
            .andExpect(content().string(containsString("Timing *")))
            .andExpect(content().string(containsString("Foot *")))
            .andExpect(content().string(containsString("Action *")))
            .andExpect(content().string(containsString("Technical Comments")))
            .andExpect(content().string(containsString("Step 1")))
            .andExpect(content().string(containsString("Natural Turn Video")))
            .andExpect(content().string(containsString("https://example.org/waltz/natural-turn")))
            .andExpect(content().string(containsString("lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_150px_36px]")))
            .andExpect(content().string(not(containsString("figures.form.steps.step_number"))))
            .andExpect(content().string(not(containsString(">✓<"))))
    }

    @Test
    fun `renders edit figure form in Polish with translation parity and no English leakage`() {
        val fig = sampleFigure()
        `when`(danceFigureService.findById(fig.id!!)).thenReturn(fig)
        `when`(danceFigureService.findByDanceType(fig.danceType!!.id!!)).thenReturn(listOf(fig))

        mockMvc.perform(get("/dance-figures/{id}/edit", fig.id).locale(polishLocale).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Natural Turn")))
            .andExpect(content().string(containsString("Rytm *")))
            .andExpect(content().string(containsString("Stopa *")))
            .andExpect(content().string(containsString("Ruch *")))
            .andExpect(content().string(containsString("Uwagi techniczne")))
            .andExpect(content().string(containsString("Krok 1")))
            .andExpect(content().string(containsString("Natural Turn Video")))
            .andExpect(content().string(containsString("https://example.org/waltz/natural-turn")))
            .andExpect(content().string(containsString("lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_150px_36px]")))
            .andExpect(content().string(not(containsString("figures.form.steps.step_number"))))
            .andExpect(content().string(not(containsString(">✓<"))))
    }

    @Test
    fun `renders create figure form in English without layout clipping`() {
        mockMvc.perform(get("/dance-figures/new").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("feet-matrix")))
            .andExpect(content().string(containsString("step-sets-panels-container")))
            .andExpect(content().string(containsString("add-step-set-btn")))
    }

    @Test
    fun `figure form header implements Direction A with labelled name, import disclosure row, Save and Cancel`() {
        val fig = sampleFigure()
        `when`(danceFigureService.findById(fig.id!!)).thenReturn(fig)
        `when`(danceFigureService.findByDanceType(fig.danceType!!.id!!)).thenReturn(listOf(fig))

        // 1. Edit view in English
        val editHtml = mockMvc.perform(get("/dance-figures/{id}/edit", fig.id).locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val editDoc = Jsoup.parse(editHtml)

        // Mode switch retired
        assertNull(editDoc.selectFirst("#manual-edit-tab-btn"), "Manual edit tab button must be retired")
        assertNull(editDoc.selectFirst("#guided-edit-tab-btn"), "Guided edit tab button must be retired")
        assertFalse(editHtml.contains("Manual Edit"), "Manual Edit text must be retired")
        assertFalse(editHtml.contains("Guided Figure Import"), "Guided Figure Import text must be retired")

        // Name field has visible label with required marker
        val nameLabel = editDoc.selectFirst("label[for='name']")
        assertNotNull(nameLabel, "Figure name field must have a label")
        assertTrue(nameLabel!!.text().contains("Figure Name"), "Name label must say Figure Name")
        assertTrue(nameLabel.select(".text-error").isNotEmpty(), "Name label must carry required asterisk")

        // Import disclosure row on Edit
        val editDisclosure = editDoc.selectFirst("#import-disclosure")
        assertNotNull(editDisclosure, "Import disclosure row must exist")
        val editSummary = editDisclosure!!.selectFirst("summary")
        assertNotNull(editSummary, "Import disclosure must have summary")
        assertTrue(editSummary!!.text().contains("Re-import from URL or JSON"), "Edit view summary must say Re-import from URL or JSON")
        val editPanel = editDisclosure.selectFirst("#guided-edit-panel")
        assertNotNull(editPanel, "Guided edit panel must be inside import disclosure row")

        // Medal badge on Edit only
        val editMedal = editDoc.selectFirst("#gradeMedalBadge")
        assertNotNull(editMedal, "Medal badge container must exist on Edit")

        // Desktop actions: Cancel and Save Figure
        val desktopActions = editDoc.selectFirst("div[th\\:fragment='desktopActions'], .hidden.sm\\:flex.items-center.gap-2")
        assertNotNull(desktopActions, "Desktop actions row must exist")
        val desktopCancel = desktopActions!!.selectFirst("a[href*='/dance-figures']")
        assertNotNull(desktopCancel, "Desktop Cancel button must exist")
        assertTrue(desktopCancel!!.text().contains("Cancel"), "Desktop Cancel button must say Cancel")
        val desktopSave = desktopActions.selectFirst("button[type='submit']")
        assertNotNull(desktopSave, "Desktop Save Figure button must exist")
        assertTrue(desktopSave!!.text().contains("Save Figure"), "Desktop Save button must say Save Figure")

        // Phone sticky bottom bar: equal width Cancel and Save Figure, positioned above bottom nav
        val phoneStickyBar = editDoc.selectFirst(".sticky-bottom-bar")
        assertNotNull(phoneStickyBar, "Phone sticky bottom bar must exist and have .sticky-bottom-bar class")
        assertTrue(phoneStickyBar!!.hasClass("sm:hidden"), "Phone bar must be hidden on desktop/tablet (sm:hidden)")
        assertTrue(phoneStickyBar.hasClass("sticky-bottom-bar"), "Phone bar must use sticky-bottom-bar utility to sit above bottom nav")
        val phoneButtons = phoneStickyBar.select("a, button")
        assertEquals(2, phoneButtons.size, "Phone sticky bar must contain exactly Cancel and Save buttons")
        assertTrue(phoneButtons[0].hasClass("flex-1"), "Cancel button must have flex-1 for equal width")
        assertTrue(phoneButtons[1].hasClass("flex-1"), "Save button must have flex-1 for equal width")
        assertTrue(phoneButtons[0].text().contains("Cancel"), "Phone Cancel button must say Cancel")
        assertTrue(phoneButtons[1].text().contains("Save Figure"), "Phone Save button must say Save Figure")

        // 2. Create view in English
        val createHtml = mockMvc.perform(get("/dance-figures/new").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val createDoc = Jsoup.parse(createHtml)

        // Import disclosure on Create
        val createDisclosure = createDoc.selectFirst("#import-disclosure")
        assertNotNull(createDisclosure, "Import disclosure row must exist on create")
        val createSummary = createDisclosure!!.selectFirst("summary")
        assertNotNull(createSummary, "Create disclosure must have summary")
        assertTrue(createSummary!!.text().contains("Import from URL or JSON"), "Create view summary must say Import from URL or JSON")

        // Shared figures notice on Create only
        assertTrue(createHtml.contains("Figures are shared with every DanceBook user"), "Create view must have shared figures notice")
        assertFalse(editHtml.contains("Figures are shared with every DanceBook user"), "Edit view must NOT have shared figures notice")

        // 3. Polish locale parity
        val plHtml = mockMvc.perform(get("/dance-figures/{id}/edit", fig.id).locale(polishLocale).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val plDoc = Jsoup.parse(plHtml)
        val plSummary = plDoc.selectFirst("#import-disclosure summary")
        assertNotNull(plSummary, "Polish import summary must exist")
        assertTrue(plSummary!!.text().contains("Zaimportuj ponownie z adresu URL lub JSON"), "Polish edit summary must be translated")
        val plCancel = plDoc.selectFirst(".sticky-bottom-bar a")
        assertNotNull(plCancel, "Polish Cancel button must exist")
        assertTrue(plCancel!!.text().contains("Anuluj"), "Polish Cancel button must say Anuluj")
        val plSave = plDoc.selectFirst(".sticky-bottom-bar button")
        assertNotNull(plSave, "Polish Save button must exist")
        assertTrue(plSave!!.text().contains("Zapisz figurę"), "Polish Save button must say Zapisz figurę")
    }
}
