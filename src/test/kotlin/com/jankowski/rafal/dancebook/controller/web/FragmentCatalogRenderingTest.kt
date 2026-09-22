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
import org.springframework.boot.test.mock.mockito.MockBean
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

    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

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
            "statCardRequired",
            "dataTableRequired",
            "stepTableRequired",
            "badgeRequired",
            "attendanceBadgeRequired",
            "emptyStateRequired",
            "alertRequired",
            "modalRequired",
            "richTextContentRequired",
            "richTextExcerptRequired",

            // All parameters populated
            "iconAll",
            "linkAll",
            "buttonAll",
            "iconButtonAll",
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
            "statCardAll",
            "dataTableAll",
            "stepTableAll",
            "badgeAll",
            "attendanceBadgeAll",
            "emptyStateAll",
            "alertAll",
            "modalAll",
            "richTextContentAll",
            "richTextExcerptAll",

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
            .andExpect(content().string(containsString("border-error")))
            .andExpect(content().string(containsString("Name must not be blank")))
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
}
