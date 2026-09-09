package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRow
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.dto.TrainingTimelineEntry
import com.jankowski.rafal.dancebook.dto.TrainingTimelineMonth
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renders the training timeline for real.
 *
 * TrainingTimelineServiceTest checks the ordering, the today boundary and the paging going into
 * the model; none of that notices a template that asks the model for something it does not have,
 * or a "load more" link that paginates to the wrong window. These do.
 *
 * Mirrors the TrainingStatsViewRenderingTest harness: same WebMvcTest slice shape, same
 * NavbarAdvice mocks, same style of asserting on rendered body text.
 */
@WebMvcTest(
    controllers = [TrainingTimelineWebController::class],
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
class TrainingTimelineViewRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingTimelineService: TrainingTimelineService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    @Test
    fun `renders the timeline with month headings, palette colours and the today marker`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(
            timeline(
                month(
                    "September 2026",
                    entry(daysAgo = -3, status = AttendanceStatus.PLANNED),
                    entry(daysAgo = 1, status = AttendanceStatus.SKIPPED, boundary = true)
                )
            )
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/timeline"))
            .andExpect(content().string(containsString("September 2026")))
            // The rail's stripe is the session's own status colour, resolved server-side.
            .andExpect(content().string(containsString("border-left-color:${TrainingEventPalette.SKIPPED.color}")))
            .andExpect(content().string(containsString("tc-timeline-today")))
            .andExpect(content().string(containsString("Skipped")))
    }

    @Test
    fun `renders only the fragment for an HTMX request`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(
            timeline(month("August 2026", entry(daysAgo = 40, status = AttendanceStatus.ATTENDED)))
        )

        mockMvc.perform(
            get("/training-events/timeline?page=1").header("HX-Request", "true").with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("August 2026")))
            // No page chrome: the fragment is an append, not a whole document.
            .andExpect(content().string(not(containsString("<nav"))))
    }

    @Test
    fun `suppresses the first month heading when it continues the month already on screen`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(
            timeline(month("August 2026", entry(daysAgo = 40, status = AttendanceStatus.ATTENDED)))
        )

        mockMvc.perform(
            get("/training-events/timeline")
                .param("page", "1")
                .param("lastMonth", "August 2026")
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("tc-month-heading"))))
    }

    @Test
    fun `offers the next window only when there is more history behind this one`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(
            timeline(
                month("September 2026", entry(daysAgo = 1, status = AttendanceStatus.ATTENDED)),
                hasMore = true,
                nextPage = 1,
                lastMonthLabel = "September 2026"
            )
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Load older sessions")))
            // The control carries the window to fetch and the month it continues.
            .andExpect(content().string(containsString("page=1")))
            .andExpect(content().string(containsString("lastMonth=September")))
    }

    @Test
    fun `hides the next-window control at the end of the history`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(
            timeline(month("September 2026", entry(daysAgo = 1, status = AttendanceStatus.ATTENDED)))
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Load older sessions"))))
    }

    @Test
    fun `shows the empty state when there is no training history at all`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt())).thenReturn(timeline())

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No training logged yet")))
    }

    private fun timeline(
        vararg months: TrainingTimelineMonth,
        hasMore: Boolean = false,
        nextPage: Int = 1,
        lastMonthLabel: String? = months.lastOrNull()?.label,
        isFirstPage: Boolean = true
    ) = TrainingTimeline(
        months = months.toList(),
        hasMore = hasMore,
        nextPage = nextPage,
        lastMonthLabel = lastMonthLabel,
        isFirstPage = isFirstPage
    )

    private fun month(label: String, vararg entries: TrainingTimelineEntry) = TrainingTimelineMonth(
        label = label,
        entries = entries.toList(),
        totalMinutes = entries.sumOf { it.event.durationMinutes }
    )

    private fun entry(
        daysAgo: Long,
        status: AttendanceStatus,
        boundary: Boolean = false
    ): TrainingTimelineEntry {
        val start = LocalDateTime.now().minusDays(daysAgo)
        val event = TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Evening practice"
            startTime = start
            endTime = start.plusMinutes(90)
            attendanceStatus = status
            eventType = TrainingEventType.TRAINING
        }
        return TrainingTimelineEntry(
            row = TrainingEventRow(event, TrainingEventPalette.swatchFor(event)),
            isFuture = daysAgo < 0,
            startsTodayBoundary = boundary
        )
    }
}
