package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.ui.Model
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.support.RequestDataValueProcessor

data class SampleTestForm(
    var name: String = "John Doe",
    var category: String = "STANDARD",
    var description: String = "Sample description",
    var active: Boolean = true,
    var enabled: Boolean = true,
    var notes: String = "Some notes"
)

data class SampleStepComment(
    val commentText: String
)

data class SampleStep(
    val stepNumber: Int = 1,
    val timing: String = "S",
    val foot: String = "LF",
    val action: String = "Step forward",
    val footwork: String = "HT",
    val alignment: String = "LOD",
    val amountOfTurn: String = "1/4",
    val comments: List<SampleStepComment> = emptyList()
)

data class SampleEvent(
    val attendanceStatus: AttendanceStatus = AttendanceStatus.ATTENDED,
    val isAwaitingConfirmation: Boolean = false
)

@Controller
class CatalogHarnessController {

    @GetMapping("/test/catalog/{fragmentName}")
    fun renderCatalogFragment(
        @PathVariable fragmentName: String,
        model: Model
    ): String {
        val form = SampleTestForm()
        model.addAttribute("testForm", form)
        model.addAttribute("categoryOptions", listOf("STANDARD", "LATIN"))

        val step = SampleStep(
            stepNumber = 1,
            timing = "S",
            foot = "LF",
            action = "Step forward",
            footwork = "HT",
            alignment = "LOD",
            amountOfTurn = "1/4",
            comments = listOf(SampleStepComment("Keep frame wide"))
        )
        model.addAttribute("steps", listOf(step))

        val event = SampleEvent(
            attendanceStatus = AttendanceStatus.ATTENDED,
            isAwaitingConfirmation = false
        )
        model.addAttribute("event", event)

        model.addAttribute(
            "sampleCards",
            listOf(
                com.jankowski.rafal.dancebook.dto.ResultCard(
                    kind = "note", id = "n1", title = "Rise and fall on the Natural Turn",
                    subtitle = "Waltz · 11 Sep 2026", snippet = "sway to the right on two",
                    url = "/materials/n1", chips = listOf("Natural Turn · 1 2 3")
                )
            )
        )

        val draftId = java.util.UUID.fromString("00000000-0000-0000-0000-00000000d001")
        fun draft(
            status: com.jankowski.rafal.dancebook.model.DraftStatus,
            notice: String? = null,
            full: Boolean = false,
            saved: Boolean = false
        ) = com.jankowski.rafal.dancebook.dto.DraftView(
            id = draftId,
            kind = com.jankowski.rafal.dancebook.model.DraftKind.NOTE,
            status = status,
            heading = "Tuesday class",
            fields = if (full) listOf(
                com.jankowski.rafal.dancebook.dto.DraftField("Text", "Head drops on step two."),
                com.jankowski.rafal.dancebook.dto.DraftField("Session", "Standard group class")
            ) else emptyList(),
            figures = if (full) listOf(com.jankowski.rafal.dancebook.dto.DraftFigureLine("Feather Step", "S Q Q", "/dance-figures/f1")) else emptyList(),
            savedUrl = if (saved) "/materials/m1" else null,
            notice = notice
        )
        model.addAttribute("sampleDraft", draft(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING))
        model.addAttribute("sampleDraftFull", draft(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING, notice = "Name is too short", full = true))
        model.addAttribute("sampleDraftSaved", draft(com.jankowski.rafal.dancebook.model.DraftStatus.SAVED, notice = "Saved, but not linked", full = true, saved = true))
        model.addAttribute("sampleDraftDiscarded", draft(com.jankowski.rafal.dancebook.model.DraftStatus.DISCARDED, full = true))

        return "test/catalog-harness :: $fragmentName"
    }

    @GetMapping("/test/catalog-error")
    fun renderFieldWithError(model: Model): String {
        val form = SampleTestForm(name = "")
        val bindingResult = BeanPropertyBindingResult(form, "testForm")
        bindingResult.rejectValue("name", "NotBlank", "Name must not be blank")
        model.addAttribute("testForm", form)
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "testForm", bindingResult)
        return "test/catalog-harness :: fieldAll"
    }

    @GetMapping("/test/catalog-error-summary")
    fun renderErrorSummary(model: Model): String {
        val form = SampleTestForm()
        val bindingResult = BeanPropertyBindingResult(form, "testForm")
        bindingResult.rejectValue("name", "NotBlank", "Name is required")
        bindingResult.rejectValue("category", "NotNull", "Category must be chosen")
        model.addAttribute("testForm", form)
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "testForm", bindingResult)
        return "test/catalog-harness :: errorSummaryAll"
    }

    @GetMapping("/test/stray-element/{fragmentName}")
    fun renderStrayElementFragment(
        @PathVariable fragmentName: String
    ): String {
        return "test/stray-element-harness :: $fragmentName"
    }
}

@WebMvcTest(
    controllers = [CatalogHarnessController::class],
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
@Import(FragmentCatalogRenderingTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class FragmentCatalogRenderingTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    @ParameterizedTest(name = "Fragment {0} renders cleanly without null")
    @ValueSource(
        strings = [
            // Required parameters only
            "iconRequired",
            "iconSm",
            "iconMd",
            "iconWithRotation",
            "linkRequired",
            "buttonRequired",
            "iconButtonRequired",
            "submitRowRequired",
            "fieldRequired",
            "selectRequired",
            "textareaRequired",
            "checkboxRequired",
            "toggleRequired",
            "richTextRequired",
            "errorSummaryRequired",
            "rawFieldRequired",
            "headerRequired",
            "sectionTitleRequired",
            "viewSwitcherRequired",
            "statCardRequired",
            "dataTableRequired",
            "stepTableRequired",
            "badgeRequired",
            "attendanceBadgeRequired",
            "medalBadgeRequired",
            "medalBadgeNone",
            "emptyStateRequired",
            "alertRequired",
            "modalRequired",
            "richTextContentRequired",
            "richTextExcerptRequired",
            "assistantCardRequired",
            "assistantMessageRequired",
            "assistantDraftRequired",

            // All parameters populated
            "iconAll",
            "linkAll",
            "buttonAll",
            "iconButtonAll",
            "iconButtonDanger",
            "submitRowAll",
            "fieldAll",
            "selectAll",
            "textareaAll",
            "checkboxAll",
            "toggleAll",
            "richTextAll",
            "errorSummaryAll",
            "rawFieldAll",
            "headerAll",
            "sectionTitleAll",
            "viewSwitcherAll",
            "statCardAll",
            "statCardTrendNoIcon",
            "dataTableAll",
            "stepTableAll",
            "badgeAll",
            "attendanceBadgeAll",
            "medalBadgeAll",
            "emptyStateAll",
            "alertAll",
            "modalAll",
            "richTextContentAll",
            "richTextExcerptAll",
            "assistantCardAll",
            "assistantMessageAll",
            "assistantMessageError",
            "assistantDraftAll",
            "assistantDraftSaved",
            "assistantDraftDiscarded",

            // Standalone field without backing form object
            "fieldStandalone",
            "richTextStandalone"
        ]
    )
    fun `every fragment is exercised and renders cleanly without null`(fragmentName: String) {
        val result = mockMvc.perform(get("/test/catalog/$fragmentName").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertFalse(html.contains("null"), "Fragment $fragmentName rendered 'null':\n$html")
        assertFalse(html.contains("th:"), "Fragment $fragmentName has unparsed th: attributes:\n$html")
    }

    @Test
    fun `field fragment renders validation errors and error styling when form has binding error`() {
        mockMvc.perform(get("/test/catalog-error").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("form-input-invalid")))
            .andExpect(content().string(containsString("Name must not be blank")))
    }

    @Test
    fun `field fragment without an error does not carry the invalid state`() {
        val html = mockMvc.perform(get("/test/catalog/fieldAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(html.contains("form-input"), "The field should still render as a form-input:\n$html")
        assertFalse(html.contains("form-input-invalid"), "A valid field must not render the invalid state:\n$html")
    }

    @Test
    fun `modal fragment takes its corners from the card radius token`() {
        val html = mockMvc.perform(get("/test/catalog/modalAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(html.contains("rounded-card"), "The dialog must name the card radius token:\n$html")
        assertFalse(html.contains("rounded-lg"), "The dialog must not carry a per-template radius:\n$html")
    }

    @Test
    fun `form and button fragments carry no hand-written radius`() {
        listOf("fieldAll", "selectAll", "textareaAll", "buttonAll", "linkAll", "iconButtonAll", "submitRowAll").forEach { name ->
            val html = mockMvc.perform(get("/test/catalog/$name").with(csrf()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
            assertFalse(
                Regex("""\brounded(-[a-z0-9\[\]]+)?\b""").containsMatchIn(html),
                "Fragment $name must take its radius from the shared utility, not a rounded-* class:\n$html"
            )
        }
    }

    @Test
    fun `error summary fragment lists all field errors`() {
        mockMvc.perform(get("/test/catalog-error-summary").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Errors found")))
            .andExpect(content().string(containsString("Name is required")))
            .andExpect(content().string(containsString("Category must be chosen")))
    }

    @Test
    fun `assistant message passes its card through as a linked card and passes a class through`() {
        mockMvc.perform(get("/test/catalog/assistantMessageAll").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/materials/n1\"")))
            .andExpect(content().string(containsString("Rise and fall on the Natural Turn")))
            .andExpect(content().string(containsString("extra-cls")))
    }

    @Test
    fun `assistant error bubble renders through the alert fragment, not as a plain answer`() {
        mockMvc.perform(get("/test/catalog/assistantMessageError").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("role=\"alert\"")))
    }

    @Test
    fun `a user bubble is escaped text`() {
        mockMvc.perform(get("/test/catalog/assistantMessageRequired").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Which notes mention sway?")))
            .andExpect(content().string(not(containsString("role=\"alert\""))))
    }

    @Test
    fun `a pending draft card shows its fields, each figure with its timing, Save and Edit in form`() {
        mockMvc.perform(get("/test/catalog/assistantDraftAll").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-draft-00000000-0000-0000-0000-00000000d001\"")))
            .andExpect(content().string(containsString("Tuesday class")))
            .andExpect(content().string(containsString("Head drops on step two.")))
            .andExpect(content().string(containsString("Standard group class")))
            .andExpect(content().string(containsString("Feather Step")))
            .andExpect(content().string(containsString("S Q Q")))
            .andExpect(content().string(containsString("/assistant/drafts/00000000-0000-0000-0000-00000000d001/save")))
            .andExpect(content().string(containsString("/assistant/drafts/00000000-0000-0000-0000-00000000d001/edit")))
            .andExpect(content().string(containsString("Name is too short")))
            .andExpect(content().string(containsString("extra-cls")))
    }

    @Test
    fun `a saved draft card is a link to what was created and has no Save button`() {
        mockMvc.perform(get("/test/catalog/assistantDraftSaved").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/materials/m1\"")))
            .andExpect(content().string(containsString("Saved, but not linked")))
            .andExpect(content().string(not(containsString("/save"))))
    }

    @Test
    fun `a discarded draft card is greyed out and has no buttons`() {
        mockMvc.perform(get("/test/catalog/assistantDraftDiscarded").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("opacity-60")))
            .andExpect(content().string(not(containsString("/save"))))
            .andExpect(content().string(not(containsString("/edit"))))
    }

    @Test
    fun `icon fragment renders default size md, aria-hidden true and unlabelled by default`() {
        val result = mockMvc.perform(get("/test/catalog/iconRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("material-symbols-outlined"))
        assertTrue(html.contains("text-[20px]"))
        assertTrue(html.contains("aria-hidden=\"true\""))
        assertFalse(html.contains("aria-label"))
        assertFalse(html.contains("icon-filled"))
        assertTrue(html.contains(">star</span>"))
    }

    @Test
    fun `icon fragment renders size sm text-16px`() {
        val result = mockMvc.perform(get("/test/catalog/iconSm").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("text-[16px]"))
    }

    @Test
    fun `icon fragment renders with all parameters populated including size lg, filled, id, title and aria-label`() {
        val result = mockMvc.perform(get("/test/catalog/iconAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("text-[24px]"))
        assertTrue(html.contains("icon-filled"))
        assertTrue(html.contains("id=\"test-icon-id\""))
        assertTrue(html.contains("title=\"Favorite icon\""))
        assertTrue(html.contains("aria-label=\"Favorite\""))
        assertTrue(html.contains("aria-hidden=\"false\""))
        assertTrue(html.contains("custom-icon"))
        assertTrue(html.contains(">star</span>"))
    }

    @Test
    fun `icon fragment renders passed extra classes into class attribute`() {
        val result = mockMvc.perform(get("/test/catalog/iconWithRotation").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("transform transition-transform group-open:rotate-90"))
    }

    @Test
    fun `rich text content fragment with null value produces no rich-text element`() {
        val result = mockMvc.perform(get("/test/catalog/richTextContentNull").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertFalse(html.contains("rich-text"), "Null value should produce no rich-text element, but got:\n$html")
    }

    @Test
    fun `page header without icon produces no icon element`() {
        val result = mockMvc.perform(get("/test/catalog/headerRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertFalse(
            html.contains("material-symbols-outlined"),
            "Header rendered without icon must not emit any icon element, but found:\n$html"
        )
    }

    @Test
    fun `section title without icon produces no icon element`() {
        val result = mockMvc.perform(get("/test/catalog/sectionTitleRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertFalse(
            html.contains("material-symbols-outlined"),
            "Section title rendered without icon must not emit any icon element, but found:\n$html"
        )
    }

    @Test
    fun `badge without icon produces no icon element`() {
        val result = mockMvc.perform(get("/test/catalog/badgeRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertFalse(
            html.contains("material-symbols-outlined"),
            "Badge rendered without icon must not emit any icon element, but found:\n$html"
        )
    }

    @Test
    fun `stat card without icon produces no icon element`() {
        val resultRequired = mockMvc.perform(get("/test/catalog/statCardRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        assertFalse(
            resultRequired.response.contentAsString.contains("material-symbols-outlined"),
            "Stat card without icon must not emit any icon element, but found:\n${resultRequired.response.contentAsString}"
        )

        val resultTrend = mockMvc.perform(get("/test/catalog/statCardTrendNoIcon").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        assertFalse(
            resultTrend.response.contentAsString.contains("material-symbols-outlined"),
            "Stat card with trend but no trendUp must not emit any icon element, but found:\n${resultTrend.response.contentAsString}"
        )
    }

    @Test
    fun `stat card renders caption when present and omits it when absent`() {
        val resultAll = mockMvc.perform(get("/test/catalog/statCardAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val allHtml = resultAll.response.contentAsString
        assertTrue(allHtml.contains("Active accounts"), "Expected caption 'Active accounts' in statCardAll:\n$allHtml")
        assertTrue(allHtml.contains("text-sm text-on-surface-variant"), "Expected caption styling in statCardAll:\n$allHtml")

        val resultRequired = mockMvc.perform(get("/test/catalog/statCardRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val reqHtml = resultRequired.response.contentAsString
        assertFalse(reqHtml.contains("text-sm text-on-surface-variant"), "statCardRequired must not render caption element:\n$reqHtml")
    }

    @Test
    fun `attendance badge renders sentence-case labels and component classes`() {
        val resultRequired = mockMvc.perform(get("/test/catalog/attendanceBadgeRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val reqHtml = resultRequired.response.contentAsString
        assertTrue(reqHtml.contains("Attended"), "Expected sentence-case 'Attended' in attendanceBadgeRequired:\n$reqHtml")
        assertTrue(reqHtml.contains("badge-success"), "Expected 'badge-success' in attendanceBadgeRequired:\n$reqHtml")

        val resultAll = mockMvc.perform(get("/test/catalog/attendanceBadgeAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val allHtml = resultAll.response.contentAsString
        assertTrue(allHtml.contains("Needs confirmation"), "Expected 'Needs confirmation' in attendanceBadgeAll:\n$allHtml")
        assertTrue(allHtml.contains("badge-warning"), "Expected 'badge-warning' in attendanceBadgeAll:\n$allHtml")
        assertTrue(allHtml.contains("extra-cls"), "Expected 'extra-cls' in attendanceBadgeAll:\n$allHtml")
    }

    @Test
    fun `medal badge renders rank bars and metal colors`() {
        val resultRequired = mockMvc.perform(get("/test/catalog/medalBadgeRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val reqHtml = resultRequired.response.contentAsString
        assertTrue(reqHtml.contains("text-bronze"), "Expected 'text-bronze' in medalBadgeRequired:\n$reqHtml")
        assertTrue(reqHtml.contains("stroke-medal-empty"), "Expected 'stroke-medal-empty' for unfilled bars:\n$reqHtml")
        assertFalse(org.jsoup.Jsoup.parse(reqHtml).text().contains("Bronze"), "Medal word should not render as text when word=false:\n$reqHtml")

        val resultAll = mockMvc.perform(get("/test/catalog/medalBadgeAll").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val allHtml = resultAll.response.contentAsString
        assertTrue(allHtml.contains("text-gold"), "Expected 'text-gold' in medalBadgeAll:\n$allHtml")
        assertTrue(allHtml.contains("extra-cls"), "Expected 'extra-cls' in medalBadgeAll:\n$allHtml")
        assertTrue(org.jsoup.Jsoup.parse(allHtml).text().contains("Gold"), "Expected 'Gold' word in medalBadgeAll:\n$allHtml")
        assertTrue(allHtml.contains("w-[18px]"), "Expected lg size 'w-[18px]' in medalBadgeAll:\n$allHtml")

        val resultNone = mockMvc.perform(get("/test/catalog/medalBadgeNone").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()
        val noneHtml = resultNone.response.contentAsString
        assertFalse(noneHtml.contains("<svg"), "Level null / none should render nothing:\n$noneHtml")
    }


    @Test
    fun `reference to an unambiguous fragment name resolves only to that fragment and ignores stray elements of the same tag name`() {
        val result = mockMvc.perform(get("/test/stray-element/renderSafeTextarea").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        assertTrue(html.contains("intended-textarea"), "Expected intended-textarea in rendered output:\n$html")
        assertFalse(html.contains("stray-textarea"), "Stray textarea outside fragment was matched:\n$html")
        assertFalse(html.contains("stray-in-other"), "Stray textarea in other fragment was matched:\n$html")

        val textareaCount = Regex("<textarea\\b").findAll(html).count()
        assertEquals(1, textareaCount, "Expected exactly 1 textarea element, got $textareaCount:\n$html")
    }

    @Test
    fun `tag-name selector matches all stray elements demonstrating the collision defect`() {
        val result = mockMvc.perform(get("/test/stray-element/renderAmbiguousTextarea").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val textareaCount = Regex("<textarea\\b").findAll(html).count()
        assertTrue(textareaCount > 1, "Expected ambiguous selector to match multiple stray elements, got $textareaCount:\n$html")
    }

    @Test
    fun `actionButton fragment resolves to single button despite stray button elements in fragments button html`() {
        val result = mockMvc.perform(get("/test/catalog/buttonRequired").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val buttonCount = Regex("<button\\b").findAll(html).count()
        assertEquals(1, buttonCount, "Expected exactly 1 button rendered, got $buttonCount:\n$html")
    }
}
