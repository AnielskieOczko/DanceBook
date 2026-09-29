package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.HomeController
import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.ContinueItem
import com.jankowski.rafal.dancebook.dto.ContinueKind
import com.jankowski.rafal.dancebook.dto.DashboardView
import com.jankowski.rafal.dancebook.dto.MonthSummary
import com.jankowski.rafal.dancebook.dto.PinnedFigure
import com.jankowski.rafal.dancebook.dto.RecentNote
import com.jankowski.rafal.dancebook.dto.SessionCard
import com.jankowski.rafal.dancebook.dto.SessionSegment
import com.jankowski.rafal.dancebook.dto.WeekDay
import com.jankowski.rafal.dancebook.dto.WeekDayState
import com.jankowski.rafal.dancebook.dto.WeekStrip
import com.jankowski.rafal.dancebook.dto.WrapUp
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DashboardService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDate
import java.util.UUID

/**
 * Renders the home dashboard for real (#147).
 *
 * `DashboardServiceTest` checks the numbers going into the view; nothing there notices a
 * template that reads a property the DTO does not have, or a `th:if` that never fires. A bad
 * expression fails the render, which is how a page reaches production as a 500.
 */
@WebMvcTest(
    controllers = [HomeController::class],
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
class HomeDashboardRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var dashboardService: DashboardService
    @MockBean private lateinit var trainingEventService: TrainingEventService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val heroId = UUID.randomUUID()
    private val olderId = UUID.randomUUID()
    private val noteId = UUID.randomUUID()

    private fun week(state: WeekDayState = WeekDayState.NONE, streak: Int = 0, toConfirm: Int = 0) = WeekStrip(
        days = (0L..6L).map {
            val date = LocalDate.of(2026, 9, 21).plusDays(it)
            WeekDay(
                date = date,
                weekdayLabel = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it.toInt()],
                dayOfMonth = date.dayOfMonth,
                today = it == 4L,
                state = if (it == 1L) state else WeekDayState.NONE
            )
        },
        streak = streak,
        toConfirmCount = toConfirm
    )

    private fun emptyView() = DashboardView(
        greeting = "Good morning, Rafał.",
        dateLabel = "Friday 25 September",
        week = week(),
        wrapUp = WrapUp(waiting = emptyList(), next = null),
        recentNotes = emptyList(),
        month = MonthSummary(trainedLabel = "0m", attended = 0, attendanceRatePercent = null, byCategory = emptyList()),
        continueItems = emptyList()
    )

    private fun card(id: UUID, title: String, note: UUID? = null) = SessionCard(
        id = id,
        title = title,
        dateLabel = "Tuesday 23 Sep",
        timeLabel = "19:00–20:30",
        segments = listOf(SessionSegment("Waltz", "45m"), SessionSegment("Foxtrot", "45m")),
        noteId = note
    )

    private fun populatedView(heroNote: UUID? = null) = emptyView().copy(
        week = week(WeekDayState.UNCONFIRMED, streak = 4, toConfirm = 2),
        wrapUp = WrapUp(
            waiting = listOf(card(heroId, "Standard group class", heroNote), card(olderId, "Private lesson")),
            next = null
        ),
        recentNotes = listOf(
            RecentNote(
                id = noteId,
                title = "Feather Step — keeping the head left",
                danceLabel = "Foxtrot",
                whenLabel = "2 days ago",
                excerpt = "Head drops on step 2.",
                figures = listOf(PinnedFigure("Feather Step", "S Q Q"), PinnedFigure("Reverse Turn", null))
            )
        ),
        month = MonthSummary(
            trainedLabel = "11h 30m",
            attended = 9,
            attendanceRatePercent = 90,
            byCategory = listOf(BreakdownSlice("Standard", 300, 5, "var(--color-chart-1)"))
        ),
        continueItems = listOf(
            ContinueItem(ContinueKind.CHOREOGRAPHY, UUID.randomUUID(), "Waltz routine", "/choreographies/x", "14 figures · edited 2 days ago")
        )
    )

    @Test
    fun `renders for a brand-new user with an empty state for the hero and for notes`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(emptyView())

        mockMvc.perform(get("/").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("index"))
            .andExpect(content().string(containsString("Good morning, Rafał.")))
            .andExpect(content().string(containsString("Friday 25 September")))
            .andExpect(content().string(containsString("Schedule your first session")))
            .andExpect(content().string(containsString("Write your first note")))
            // No streak, nothing to confirm, no continue block, and an unknown rate is a dash.
            .andExpect(content().string(not(containsString("-session streak"))))
            .andExpect(content().string(not(containsString("to confirm"))))
            .andExpect(content().string(not(containsString("id=\"home-continue\""))))
            .andExpect(content().string(containsString("–")))
    }

    @Test
    fun `renders the hero, older rows, week strip, notes with figure timing, month and continue`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())

        mockMvc.perform(get("/").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("To wrap up")))
            .andExpect(content().string(containsString("Standard group class")))
            .andExpect(content().string(containsString("Tuesday 23 Sep · 19:00–20:30")))
            .andExpect(content().string(containsString("Waltz · 45m")))
            .andExpect(content().string(containsString("Write a note from this session")))
            .andExpect(content().string(containsString("/materials/new?fromSession=$heroId")))
            .andExpect(content().string(containsString("Private lesson")))
            // Week strip: streak, the count waiting, and the day's state.
            .andExpect(content().string(containsString("4-session streak")))
            .andExpect(content().string(containsString("2 to confirm")))
            .andExpect(content().string(containsString("Tue 22, needs confirmation")))
            .andExpect(content().string(containsString("Fri 25, today")))
            // Recent notes: the timing is shown, and a figure without one is left bare, not "[]".
            .andExpect(content().string(containsString("Feather Step")))
            .andExpect(content().string(containsString("S Q Q")))
            .andExpect(content().string(containsString("Reverse Turn")))
            .andExpect(content().string(not(containsString("[]"))))
            .andExpect(content().string(not(containsString("null"))))
            // This month, linked to the stats page, with the desktop category bar.
            .andExpect(content().string(containsString("11h 30m")))
            .andExpect(content().string(containsString("90%")))
            .andExpect(content().string(containsString("/training-events/stats?period=THIS_MONTH")))
            .andExpect(content().string(containsString("Standard 5h")))
            .andExpect(content().string(containsString("Waltz routine")))
    }

    @Test
    fun `the hero offers Open note when a note is already linked`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView(heroNote = noteId))

        mockMvc.perform(get("/").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Open note")))
            .andExpect(content().string(containsString("/materials/$noteId")))
            // The older row has no note, so that one still offers to write it.
            .andExpect(content().string(containsString("/materials/new?fromSession=$olderId")))
            .andExpect(content().string(not(containsString("/materials/new?fromSession=$heroId"))))
    }

    @Test
    fun `with nothing waiting the slot shows the next session`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(
            emptyView().copy(wrapUp = WrapUp(waiting = emptyList(), next = card(heroId, "Saturday practice")))
        )

        mockMvc.perform(get("/").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Next session")))
            .andExpect(content().string(containsString("Saturday practice")))
            .andExpect(content().string(containsString("You are all caught up.")))
            .andExpect(content().string(not(containsString("Attended</button>"))))
    }

    @Test
    fun `the old count tiles, activity timeline and quick actions are gone`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())

        mockMvc.perform(get("/").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Recent Activity"))))
            .andExpect(content().string(not(containsString("Create Collection"))))
            .andExpect(content().string(not(containsString("Feedback"))))
            // The single "+ New" menu replaces them.
            .andExpect(content().string(containsString("id=\"home-new-menu\"")))
            .andExpect(content().string(containsString("/training-events/new")))
    }

    @Test
    fun `attending over HTMX writes through the attendance service and swaps the section`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())

        mockMvc.perform(
            post("/home/sessions/$heroId/attendance").param("status", "ATTENDED").header("HX-Request", "true").with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"home-wrap-up\"")))
            // The week strip and month card follow out of band, since the answer changes both.
            .andExpect(content().string(containsString("id=\"home-week\"")))
            .andExpect(content().string(containsString("id=\"home-month\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
            .andExpect(content().string(not(containsString("Could not save"))))

        verify(trainingEventService).updateAttendance(heroId, AttendanceStatus.ATTENDED)
    }

    @Test
    fun `skipping goes through the same call`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())

        mockMvc.perform(
            post("/home/sessions/$heroId/attendance").param("status", "SKIPPED").header("HX-Request", "true").with(csrf())
        ).andExpect(status().isOk)

        verify(trainingEventService).updateAttendance(heroId, AttendanceStatus.SKIPPED)
    }

    @Test
    fun `a failed update re-renders the section with the error and leaves the session waiting`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())
        doThrow(IllegalStateException("Calendar sync failed")).`when`(trainingEventService)
            .updateAttendance(heroId, AttendanceStatus.ATTENDED)

        mockMvc.perform(
            post("/home/sessions/$heroId/attendance").param("status", "ATTENDED").header("HX-Request", "true").with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Could not save")))
            .andExpect(content().string(containsString("Calendar sync failed")))
            // The session is still the hero, with its buttons.
            .andExpect(content().string(containsString("Standard group class")))
    }

    @Test
    fun `a plain form post redirects home instead of rendering a fragment`() {
        mockMvc.perform(post("/home/sessions/$heroId/attendance").param("status", "ATTENDED").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/"))

        verify(trainingEventService).updateAttendance(heroId, AttendanceStatus.ATTENDED)
    }

    @Test
    fun `only attended and skipped are accepted from the hero`() {
        mockMvc.perform(
            post("/home/sessions/$heroId/attendance").param("status", "CANCELLED").header("HX-Request", "true").with(csrf())
        ).andExpect(status().isBadRequest)

        verify(trainingEventService, never()).updateAttendance(heroId, AttendanceStatus.CANCELLED)
    }
}
