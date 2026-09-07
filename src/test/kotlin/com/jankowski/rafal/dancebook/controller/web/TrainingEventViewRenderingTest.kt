package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.web.servlet.support.RequestDataValueProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renders the training templates for real.
 *
 * The unit tests above check what goes into the model; nothing there notices a template that
 * asks the model for something it does not have. These do -- a bad expression fails the
 * render, so every page and fragment the calendar and agenda rely on is exercised here.
 */
@WebMvcTest(
    controllers = [TrainingEventWebController::class],
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
@Import(TrainingEventViewRenderingTest.CsrfProcessorConfig::class)
class TrainingEventViewRenderingTest {

    /**
     * Security's own filters are off here, but the processor that puts the CSRF hidden field
     * into a `th:action` form is what the real app relies on, so it has to be present or a
     * form missing its token would render identically to one that has it.
     */
    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    @Test
    fun `should render the agenda with month headings and a status rail per session`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession(), awaitingSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/list"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("September 2026")))
            // The rail's stripe is the session's own status colour, straight from the palette.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("border-left-color:#2e5d51")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("border-left-color:#695d46")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Needs confirmation")))
            // Edit and delete fold behind an overflow menu on a phone.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("js-menu-dropdown")))
    }

    @Test
    fun `should render the agenda fragment on its own for an HTMX swap`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))

        mockMvc.perform(get("/training-events").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("tc-month-heading")))
    }

    @Test
    fun `should render the empty agenda when nothing is logged`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No training sessions yet")))
    }

    @Test
    fun `should render the calendar page with the legend drawn from the palette`() {
        mockMvc.perform(get("/training-events/calendar").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/calendar"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Needs confirmation")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("background-color:#ba1a1a")))
            // The mobile half of the page: the day agenda, its row template and the create button.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("dayAgendaItemTemplate")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("quickCreateFab")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("quickCreateSheet")))
    }

    @Test
    fun `should render the quick-create fragment the calendar fetches into its sheet`() {
        mockMvc.perform(
            get("/training-events/quick-create")
                .param("start", "2026-09-14T18:00:00")
                .param("end", "2026-09-14T19:00:00")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("quickCreateForm")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Weekly on Monday")))
            // The script wires the repeat control by these two hooks.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("quickRepeatUntilRow")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"repeat\"")))
    }

    private fun attendedSession() = session(LocalDateTime.of(2026, 9, 14, 18, 0), AttendanceStatus.ATTENDED)
        .apply {
            segments.add(TrainingEventSegment().apply {
                danceCategory = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
                durationMinutes = 60
                sortOrder = 0
            })
        }

    /** Past and still PLANNED, so the agenda has to offer the one-tap confirmation buttons. */
    private fun awaitingSession() = session(LocalDateTime.now().minusDays(3), AttendanceStatus.PLANNED)

    private fun session(start: LocalDateTime, status: AttendanceStatus) = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        startTime = start
        endTime = start.plusHours(2)
        attendanceStatus = status
    }

    /**
     * The quick-create card posts a normal HTML form, so Spring Security wants a CSRF token in
     * the body. Thymeleaf only adds that hidden field for `th:action`; with a plain `action`
     * attribute the POST is rejected with a 403 that Spring Security does not log, which looks
     * like a blank error page and a session that silently never got created.
     */
    @Test
    fun `should carry a CSRF token in the quick-create form`() {
        val html = mockMvc.perform(
            get("/training-events/quick-create").with(csrf())
                .param("start", "2026-09-14T18:00:00")
                .param("end", "2026-09-14T19:00:00")
        ).andReturn().response.contentAsString

        assertTrue(html.contains("_csrf"), "quick-create form posts without a CSRF token: $html")
    }
}
