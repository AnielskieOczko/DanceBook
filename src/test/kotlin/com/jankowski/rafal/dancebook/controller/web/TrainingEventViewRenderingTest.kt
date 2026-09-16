package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDateTime
import java.util.UUID
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.jsoup.Jsoup

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
    @MockBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService

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
    fun `should render checkboxes, select-all control, and bulk action bar when sessions exist`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("selectAllSessions")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("js-session-checkbox")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("bulkActionBar")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Mark Attended")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Mark Skipped")))
    }

    @Test
    fun `should render bulk feedback banner following a bulk attendance update over HTMX`() {
        val session1 = attendedSession()
        `when`(trainingEventService.bulkUpdateAttendance(listOf(session1.id!!), AttendanceStatus.ATTENDED))
            .thenReturn(com.jankowski.rafal.dancebook.dto.BulkAttendanceResult(1, 0, AttendanceStatus.ATTENDED))
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(session1))

        mockMvc.perform(
            post("/training-events/bulk-attendance")
                .header("HX-Request", "true")
                .param("sessionIds", session1.id.toString())
                .param("status", "ATTENDED")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Marked 1 session as attended.")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("js-dismiss-banner")))
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

    @Test
    fun `the create form no longer offers a calendar picker`() {
        val html = mockMvc.perform(get("/training-events/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertFalse(html.contains("id=\"calendarId\""))
    }

    @Test
    fun `create form names the target calendar when a specific calendar is active`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club Training"; enabled = true }
        `when`(activeCalendarService.creationTarget()).thenReturn(club)

        val html = mockMvc.perform(get("/training-events/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(html.contains("Target calendar"))
        assertTrue(html.contains("Club Training"))
        assertTrue(html.contains("name=\"calendarId\""))
    }

    @Test
    fun `create form names default calendar as target when All calendars is active`() {
        val defaultCal = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Primary Calendar"; enabled = true }
        `when`(activeCalendarService.creationTarget()).thenReturn(defaultCal)

        val html = mockMvc.perform(get("/training-events/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(html.contains("Target calendar"))
        assertTrue(html.contains("Primary Calendar"))
        assertTrue(html.contains("name=\"calendarId\""))
    }

    @Test
    fun `edit form names the owning calendar in read-only mode`() {
        val ownCal = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club Training"; enabled = true }
        val event = attendedSession().apply { calendar = ownCal }
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events/${event.id}/edit").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(html.contains("Club Training"))
        assertTrue(html.contains("Cannot be moved between calendars"))
    }

    @Test
    fun `the calendar selector is hidden with one calendar and shown with two`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club))
        `when`(activeCalendarService.active()).thenReturn(club)

        var html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertFalse(html.contains("id=\"activeCalendar\""))

        val home = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Home"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club, home))

        html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(html.contains("id=\"activeCalendar\""))
        assertTrue(html.contains("All calendars"))
    }

    @Test
    fun `calendar selector marks a specific calendar as selected when active`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club"; enabled = true }
        val home = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Home"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club, home))
        `when`(activeCalendarService.active()).thenReturn(club)

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val selected = doc.select("#activeCalendar option[selected]")
        assertEquals(1, selected.size)
        assertEquals(club.id.toString(), selected.attr("value"))
        assertEquals("Club", selected.text())
    }

    @Test
    fun `calendar selector marks All calendars as selected when no calendar is active`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club"; enabled = true }
        val home = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Home"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club, home))
        `when`(activeCalendarService.active()).thenReturn(null)

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val selected = doc.select("#activeCalendar option[selected]")
        assertEquals(1, selected.size)
        assertEquals("ALL", selected.attr("value"))
        assertEquals("All calendars", selected.text())
    }

    @Test
    fun `calendar selector marks a disabled calendar as selected with disabled label when active`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club"; enabled = true }
        val retired = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Retired"; enabled = false }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club, retired))
        `when`(activeCalendarService.active()).thenReturn(retired)

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val selected = doc.select("#activeCalendar option[selected]")
        assertEquals(1, selected.size)
        assertEquals(retired.id.toString(), selected.attr("value"))
        assertEquals("Retired (disabled)", selected.text())
    }

    @Test
    fun `should render the Sync now button as pure HTMX button without a native form wrapper`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val syncButton = doc.select("button[hx-post='/training-events/sync']")
        assertEquals(1, syncButton.size, "Sync now button should be present with hx-post")
        assertEquals("button", syncButton.attr("type"), "Button should have type=button")
        assertTrue(syncButton.text().contains("Sync now"), "Button should display Sync now")

        val syncForm = doc.select("form[action*='/training-events/sync']")
        assertTrue(syncForm.isEmpty(), "Sync now must not be wrapped in a native form")
    }

    @Test
    fun `visiting training events triggers syncIfDue through interceptor`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)

        org.mockito.Mockito.verify(calendarSyncService).syncIfDue()
    }

    @Test
    fun `sync failure during page view still renders the page with 200 OK`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
        `when`(calendarSyncService.syncIfDue()).thenThrow(com.jankowski.rafal.dancebook.service.CalendarSyncException("Google API 500 error"))

        mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/list"))
    }

    @Test
    fun `calendar selector surfaces lastSyncedAt timestamp`() {
        val calWithSync = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Synced Calendar"
            isDefault = true
            enabled = true
            lastSyncedAt = LocalDateTime.of(2026, 9, 15, 14, 45)
        }
        `when`(activeCalendarService.active()).thenReturn(calWithSync)
        `when`(activeCalendarService.selectable()).thenReturn(listOf(calWithSync))
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val syncText = doc.select(".text-text-secondary:contains(Synced 14:45)")
        assertEquals(1, syncText.size, "Should display formatted lastSyncedAt time")
    }

    @Test
    fun `calendar selector surfaces Never synced when lastSyncedAt is null`() {
        val calWithoutSync = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Unsynced Calendar"
            isDefault = true
            enabled = true
            lastSyncedAt = null
        }
        `when`(activeCalendarService.active()).thenReturn(calWithoutSync)
        `when`(activeCalendarService.selectable()).thenReturn(listOf(calWithoutSync))
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val syncText = doc.select(".text-text-secondary:contains(Never synced)")
        assertEquals(1, syncText.size, "Should display Never synced when null")
    }
}

