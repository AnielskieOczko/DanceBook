package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import org.junit.jupiter.api.Test
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

/**
 * Renders the training statistics page for real.
 *
 * TrainingStatsServiceTest below checks the numbers going into the model; nothing there
 * notices a template that asks the model for something it does not have, or a StatsPeriod
 * request param that fails to bind, or an ObjectMapper serialisation that blows up. These
 * do -- a bad expression or a bad request param fails the render, which is how a page like
 * this makes it to production as a 500 nobody sees until a user hits it.
 *
 * Mirrors TrainingEventViewRenderingTest's harness: same WebMvcTest slice shape, same
 * NavbarAdvice mocks, same style of asserting on rendered body text.
 */
@WebMvcTest(
    controllers = [TrainingStatsWebController::class],
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
class TrainingStatsViewRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingStatsService: TrainingStatsService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    private fun statsWith(
        period: StatsPeriod,
        counts: SessionCounts,
        totalMinutesTrained: Long,
        attendanceRatePercent: Int?,
        currentStreak: Int,
        byCategory: List<BreakdownSlice> = emptyList(),
        byEventType: List<BreakdownSlice> = emptyList()
    ) = TrainingStats(
        period = period,
        totalMinutesTrained = totalMinutesTrained,
        counts = counts,
        attendanceRatePercent = attendanceRatePercent,
        currentStreak = currentStreak,
        byCategory = byCategory,
        byEventType = byEventType
    )

    @Test
    fun `should render the populated page with KPI cards and both chart legends`() {
        val stats = statsWith(
            period = StatsPeriod.ALL_TIME,
            counts = SessionCounts(upcoming = 2, unconfirmed = 0, attended = 18, skipped = 2, cancelled = 1),
            totalMinutesTrained = 750,
            attendanceRatePercent = 90,
            currentStreak = 4,
            byCategory = listOf(BreakdownSlice("Standard", 360, 6, "#2e5d51")),
            byEventType = listOf(BreakdownSlice("TRAINING", 750, 10, "#695d46"))
        )
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(stats)

        mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/stats"))
            // KPI cards, formatted via TrainingStats' own labels.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("12h 30m")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("90%")))
            // The style legend and the session-type legend, straight from the slices.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Standard")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("TRAINING")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"category-chart\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"event-type-chart\"")))
            // The slice actually made it through ObjectMapper onto the canvas's data attribute.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("&quot;color&quot;:&quot;#2e5d51&quot;")))
    }

    @Test
    fun `should render the empty state when the user has no sessions at all`() {
        val stats = statsWith(
            period = StatsPeriod.ALL_TIME,
            counts = SessionCounts(upcoming = 0, unconfirmed = 0, attended = 0, skipped = 0, cancelled = 0),
            totalMinutesTrained = 0,
            attendanceRatePercent = null,
            currentStreak = 0
        )
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(stats)

        mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No sessions logged yet")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Log a training session and this page will start")))
            // Neither chart canvas draws when there is nothing to summarise.
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"category-chart\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"event-type-chart\""))))
    }

    @Test
    fun `should render the unconfirmed nudge when past sessions await confirmation`() {
        val stats = statsWith(
            period = StatsPeriod.ALL_TIME,
            counts = SessionCounts(upcoming = 0, unconfirmed = 3, attended = 5, skipped = 0, cancelled = 0),
            totalMinutesTrained = 300,
            attendanceRatePercent = 100,
            currentStreak = 5
        )
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(stats)

        mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("3 past sessions need confirming")))
            // Links back into the list, pre-filtered -- the wiring finding 3 fixes on the other end.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("awaitingConfirmation=true")))
    }

    @Test
    fun `should bind the narrowed period request param and show its own empty copy`() {
        val stats = statsWith(
            period = StatsPeriod.LAST_30_DAYS,
            counts = SessionCounts(upcoming = 0, unconfirmed = 0, attended = 0, skipped = 0, cancelled = 0),
            totalMinutesTrained = 0,
            attendanceRatePercent = null,
            currentStreak = 2
        )
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)).thenReturn(stats)

        mockMvc.perform(get("/training-events/stats").param("period", "LAST_30_DAYS").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Last 30 days")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No sessions in this period")))
            // Distinct from the all-time empty copy: a narrowed period is not "you have nothing yet".
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Nothing falls in this window")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("this page will start"))))
    }
}
