package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.MaterialService
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
    @MockBean private lateinit var materialService: MaterialService
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
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("bulk-delete-dialog")))
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
    fun `should render confirm dialog for bulk delete with count and Google Calendar warning`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()

        mockMvc.perform(
            post("/training-events/bulk-delete-dialog")
                .param("sessionIds", id1.toString(), id2.toString())
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete Sessions")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("2 training sessions will be deleted")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("removed from Google Calendar")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("2 selected")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete 2 Sessions")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"$id1\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"$id2\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-target=\"#events-list\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-include=\"#filterForm\"")))
    }

    @Test
    fun `should render bulk feedback banner following a bulk delete over HTMX`() {
        val session1 = attendedSession()
        `when`(trainingEventService.bulkDelete(listOf(session1.id!!)))
            .thenReturn(com.jankowski.rafal.dancebook.dto.BulkDeleteResult(deletedCount = 1, failedCount = 0))
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(emptyList())

        mockMvc.perform(
            post("/training-events/bulk-delete")
                .header("HX-Request", "true")
                .param("sessionIds", session1.id.toString())
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Deleted 1 session.")))
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
    fun `edit form for series occurrence renders all three scopes and detachment warning`() {
        val series = com.jankowski.rafal.dancebook.model.TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
        }
        val event = attendedSession().apply { this.series = series }
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events/${event.id}/edit").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        assertEquals(3, doc.select("input[name=editScope]").size, "Should render 3 edit scope radio buttons")
        assertEquals(1, doc.select("input[name=editScope][value=THIS_EVENT]").size)
        assertEquals(1, doc.select("input[name=editScope][value=THIS_AND_FOLLOWING]").size)
        assertEquals(1, doc.select("input[name=editScope][value=ALL_EVENTS]").size)
        assertTrue(html.contains("This event only"))
        assertTrue(html.contains("This and following events"))
        assertTrue(html.contains("All events"))
        assertTrue(html.contains("Detaches this session from the series"), "Should warn that 'this event' detaches it")
    }

    @Test
    fun `edit form for standalone session does not render series scope block`() {
        val event = attendedSession().apply { this.series = null }
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events/${event.id}/edit").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        assertEquals(0, doc.select("input[name=editScope]").size, "Should not render series scope radios for standalone session")
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

    @Test
    fun `bulk action bar displays change type, set styles, and set note buttons`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(attendedSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val bulkBar = doc.select("#bulkActionBar")
        assertEquals(1, bulkBar.size, "Bulk action bar should be present")
        assertTrue(bulkBar.text().contains("Change Type"), "Bulk action bar should contain Change Type")
        assertTrue(bulkBar.text().contains("Set Styles"), "Bulk action bar should contain Set Styles")
        assertTrue(bulkBar.text().contains("Set Note"), "Bulk action bar should contain Set Note")
    }

    @Test
    fun `bulk edit type dialog renders with event type options and csrf`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        val html = mockMvc.perform(
            post("/training-events/bulk-edit-type-dialog")
                .param("sessionIds", id1.toString(), id2.toString(), id3.toString())
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/bulk-edit-dialog :: editEventTypeModal"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val title = doc.select("#modalTitle").text()
        assertTrue(title.contains("Change Event Type"), "Dialog title should be Change Event Type")
        assertTrue(html.contains("Apply to 3 selected sessions"), "Subtitle should include session count")
        assertTrue(doc.select("select[name=eventType]").size > 0, "Should include eventType select")
        assertTrue(doc.select("input[name=_csrf]").size > 0, "Should include CSRF hidden field")
    }

    @Test
    fun `bulk edit styles dialog renders with categories and segment inputs`() {
        val cat1 = DanceCategory().apply { name = "Standard" }
        val cat2 = DanceCategory().apply { name = "Latin" }
        `when`(danceCategoryService.findAll()).thenReturn(listOf(cat1, cat2))

        val ids = (1..5).map { UUID.randomUUID().toString() }.toTypedArray()
        val html = mockMvc.perform(
            post("/training-events/bulk-edit-styles-dialog")
                .param("sessionIds", *ids)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/bulk-edit-dialog :: editStylesModal"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val title = doc.select("#modalTitle").text()
        assertTrue(title.contains("Replace Style Segments"), "Dialog title should be Replace Style Segments")
        assertTrue(html.contains("Apply to 5 selected sessions"), "Subtitle should include session count")
        assertTrue(doc.select(".js-bulk-add-segment").size > 0, "Should include Add Style button")
        assertTrue(doc.select("select[name='segments[0].categoryId']").size > 0, "Should include first segment category select")
        assertTrue(doc.select("input[name=_csrf]").size > 0, "Should include CSRF hidden field")
    }

    @Test
    fun `bulk edit material dialog renders with notes dropdown and clear checkbox`() {
        val note = Material().apply {
            id = UUID.randomUUID()
            name = "Choreo Notes 2026"
        }
        `when`(materialService.findAll()).thenReturn(listOf(note))

        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val html = mockMvc.perform(
            post("/training-events/bulk-edit-material-dialog")
                .param("sessionIds", id1.toString(), id2.toString())
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/bulk-edit-dialog :: editMaterialModal"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val title = doc.select("#modalTitle").text()
        assertTrue(title.contains("Attach Note or Link"), "Dialog title should be Attach Note or Link")
        assertTrue(html.contains("Apply to 2 selected sessions"), "Subtitle should include session count")
        assertTrue(doc.select("select[name=materialId]").size > 0, "Should include material select")
        assertTrue(doc.select("input[name=materialsUrl]").size > 0, "Should include materialsUrl input")
        assertTrue(doc.select("input[name=clearMaterial]").size > 0, "Should include clearMaterial checkbox")
        assertTrue(doc.select("input[name=_csrf]").size > 0, "Should include CSRF hidden field")
    }

    @Test
    fun `view page renders repeating series badge and htmx delete dialog button for series occurrence`() {
        val series = com.jankowski.rafal.dancebook.model.TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
        }
        val event = attendedSession().apply {
            this.series = series
        }
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)

        val html = mockMvc.perform(get("/training-events/${event.id}").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/view"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        assertTrue(doc.select(".badge-neutral:contains(Repeating series)").size > 0,
            "Should render Repeating series badge")
        val deleteBtn = doc.select("button[hx-get='/training-events/${event.id}/delete-dialog']")
        assertEquals(1, deleteBtn.size, "Should render HTMX delete button targeting confirm modal")
        assertEquals("#confirmModalContainer", deleteBtn.attr("hx-target"))
    }

    @Test
    fun `view page renders standard data-confirm delete form for standalone event`() {
        val event = attendedSession()
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)

        val html = mockMvc.perform(get("/training-events/${event.id}").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/view"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        assertEquals(0, doc.select(".badge-neutral:contains(Repeating series)").size,
            "Should not render Repeating series badge for standalone session")
        assertEquals(0, doc.select("button[hx-get='/training-events/${event.id}/delete-dialog']").size,
            "Should not render HTMX delete button")
        val form = doc.select("form[action='/training-events/${event.id}/delete']")
        assertEquals(1, form.size, "Should render standard delete form")
        assertTrue(form.attr("data-confirm").contains("Delete this training session"),
            "Should have data-confirm on delete form")
    }

    @Test
    fun `agenda renders htmx delete dialog button for series occurrence and data-confirm for standalone`() {
        val series = com.jankowski.rafal.dancebook.model.TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
        }
        val seriesOcc = attendedSession().apply { this.series = series }
        val standalone = attendedSession()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(seriesOcc, standalone))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val html = mockMvc.perform(get("/training-events").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        val seriesDeleteBtns = doc.select("button[hx-get='/training-events/${seriesOcc.id}/delete-dialog']")
        assertTrue(seriesDeleteBtns.size > 0, "Series occurrence should have HTMX delete button")
        val standaloneDeleteForms = doc.select("form[action='/training-events/${standalone.id}/delete']")
        assertTrue(standaloneDeleteForms.size > 0, "Standalone event should have standard delete form")
    }

    @Test
    fun `deleteDialog endpoint returns confirm modal fragment with scope options for series occurrence`() {
        val series = com.jankowski.rafal.dancebook.model.TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
        }
        val event = attendedSession().apply { this.series = series }
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        val mockOptions = listOf(
            com.jankowski.rafal.dancebook.dto.ScopeOption(
                scope = com.jankowski.rafal.dancebook.model.SeriesScope.THIS_EVENT,
                label = "This session only",
                count = 1,
                outcomeCount = 0,
                description = "Delete 1 session"
            )
        )
        `when`(trainingSeriesService.calculateDeleteScopeOptions(event.id!!)).thenReturn(mockOptions)

        val html = mockMvc.perform(get("/training-events/${event.id}/delete-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/confirm-dialog :: confirmModal"))
            .andReturn().response.contentAsString

        val doc = Jsoup.parse(html)
        assertTrue(doc.select("input[name=scope]").size > 0, "Should include radio scope options")
        assertEquals("/training-events/${event.id}/delete", doc.select("form").attr("action"))
    }
}

