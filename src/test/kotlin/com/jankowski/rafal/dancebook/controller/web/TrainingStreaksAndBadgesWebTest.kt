package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.AssistantPreferences
import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRow
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.dto.TrainingTimelineEntry
import com.jankowski.rafal.dancebook.dto.TrainingTimelineMonth
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantPreferencesService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [TrainingTimelineWebController::class, TrainingStatsWebController::class],
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
class TrainingStreaksAndBadgesWebTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var trainingTimelineService: TrainingTimelineService
    @MockitoBean private lateinit var trainingStatsService: TrainingStatsService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var assistantPreferencesService: AssistantPreferencesService

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService

    private val testUser = AppUser().apply {
        id = UUID.randomUUID()
        username = "alex"
        displayName = "Alex"
        role = Role.USER
    }

    private val sampleStats = TrainingStats(
        period = StatsPeriod.ALL_TIME,
        totalMinutesTrained = 360L,
        counts = SessionCounts(upcoming = 2, unconfirmed = 1, attended = 4, skipped = 1, cancelled = 0),
        attendanceRatePercent = 80,
        currentStreak = 4,
        byCategory = listOf(BreakdownSlice("Standard", 360L, 4, "#123456")),
        byEventType = emptyList()
    )

    @BeforeEach
    fun setUp() {
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        val defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default Calendar"
            enabled = true
        }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(defaultCal))
        `when`(activeCalendarService.active()).thenReturn(null)
        `when`(trainingStatsService.statsForCurrentUser(org.mockito.ArgumentMatchers.any(StatsPeriod::class.java) ?: StatsPeriod.ALL_TIME, nullable(UUID::class.java)))
            .thenReturn(sampleStats)
    }

    private fun populatedTimeline(): TrainingTimeline {
        val event = TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Practice Session"
            startTime = LocalDateTime.now().minusDays(1)
            endTime = startTime.plusMinutes(90)
            setAttendance(testUser, AttendanceStatus.ATTENDED)
            eventType = TrainingEventType.TRAINING
            createdBy = testUser
        }
        val entry = TrainingTimelineEntry(
            row = TrainingEventRow(event, TrainingEventPalette.ATTENDED),
            isFuture = false,
            startsTodayBoundary = false,
            activeStreak = 4,
            streakReachedDate = "Sun 11 Oct",
            streakTier = "bronze"
        )
        val month = TrainingTimelineMonth(
            label = "October 2026",
            entries = listOf(entry),
            totalMinutes = 90L
        )
        return TrainingTimeline(
            months = listOf(month),
            hasMore = false,
            nextPage = 1,
            lastMonthLabel = "October 2026",
            isFirstPage = true,
            stats = sampleStats
        )
    }

    @Test
    fun `timeline with showStreaksAndBadges ON renders streak tile, badge slots, and journey streak`() {
        `when`(assistantPreferencesService.forUser(testUser))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = true))
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java)))
            .thenReturn(populatedTimeline())

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hours trained")))
            .andExpect(content().string(containsString("Attendance")))
            .andExpect(content().string(containsString("Streak")))
            .andExpect(content().string(containsString("consecutive, all time")))
            .andExpect(content().string(containsString("Badge slot · streak 10")))
            .andExpect(content().string(containsString("sessions in a row")))
    }

    @Test
    fun `timeline with showStreaksAndBadges OFF hides streak tile, badge slots, and journey streak`() {
        `when`(assistantPreferencesService.forUser(testUser))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = false))
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java)))
            .thenReturn(populatedTimeline())

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hours trained")))
            .andExpect(content().string(containsString("Attendance")))
            .andExpect(content().string(containsString("To confirm")))
            .andExpect(content().string(not(containsString("consecutive, all time"))))
            .andExpect(content().string(not(containsString("Badge slot · streak 10"))))
            .andExpect(content().string(not(containsString("sessions in a row"))))
    }

    @Test
    fun `training stats with showStreaksAndBadges ON renders streak card`() {
        `when`(assistantPreferencesService.forUser(testUser))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = true))

        mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hours trained")))
            .andExpect(content().string(containsString("Attendance")))
            .andExpect(content().string(containsString("Streak")))
            .andExpect(content().string(containsString("consecutive, all time")))
            .andExpect(content().string(containsString("Attended")))
    }

    @Test
    fun `training stats with showStreaksAndBadges OFF hides streak card while keeping plain statistics`() {
        `when`(assistantPreferencesService.forUser(testUser))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = false))

        mockMvc.perform(get("/training-events/stats").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hours trained")))
            .andExpect(content().string(containsString("Attendance")))
            .andExpect(content().string(containsString("Attended")))
            .andExpect(content().string(not(containsString("consecutive, all time"))))
    }

    @Test
    fun `training stats and timeline in Polish with showStreaksAndBadges OFF hide streak cleanly`() {
        `when`(assistantPreferencesService.forUser(testUser))
            .thenReturn(AssistantPreferences(showStreaksAndBadges = false))
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java)))
            .thenReturn(populatedTimeline())

        mockMvc.perform(get("/training-events/stats").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Godziny treningu")))
            .andExpect(content().string(containsString("Frekwencja")))
            .andExpect(content().string(not(containsString("z rzędu, w całym okresie"))))

        mockMvc.perform(get("/training-events/timeline").locale(Locale.forLanguageTag("pl")).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Godziny treningu")))
            .andExpect(content().string(containsString("Frekwencja")))
            .andExpect(content().string(not(containsString("z rzędu, w całym okresie"))))
            .andExpect(content().string(not(containsString("Miejsce na odznakę"))))
    }
}
