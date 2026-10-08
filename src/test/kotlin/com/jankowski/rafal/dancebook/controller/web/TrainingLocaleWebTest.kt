package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * The training pages in Polish (#241): calendar, timeline, stats, the session form and the
 * agenda list. Each Polish assertion is paired with a check that the English text is gone,
 * because a template that still carries a hardcoded string renders both as soon as one
 * half of the page has been migrated. The English tests pin that the default language
 * reads exactly as it did before the sweep.
 */
@WebMvcTest(
    controllers = [
        TrainingEventWebController::class,
        TrainingStatsWebController::class,
        TrainingTimelineWebController::class
    ],
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
@Import(TrainingLocaleWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class TrainingLocaleWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockBean private lateinit var trainingStatsService: TrainingStatsService
    @MockBean private lateinit var trainingTimelineService: TrainingTimelineService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var assistantDrafts: AssistantDraftService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    private val polish = Locale.forLanguageTag("pl")
    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "testuser"
            displayName = "Test User"
            locale = null
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)
        val defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default Calendar"
            enabled = true
        }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(defaultCal))
        `when`(activeCalendarService.active()).thenReturn(null)
    }

    private fun stats(counts: SessionCounts, minutes: Long, rate: Int?, streak: Int) = TrainingStats(
        period = StatsPeriod.ALL_TIME,
        totalMinutesTrained = minutes,
        counts = counts,
        attendanceRatePercent = rate,
        currentStreak = streak,
        byCategory = emptyList(),
        byEventType = emptyList()
    )

    private fun emptyTimeline() = TrainingTimeline(
        months = emptyList(),
        hasMore = false,
        nextPage = 1,
        lastMonthLabel = null,
        isFirstPage = true
    )

    private fun awaitingSession() = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        startTime = LocalDateTime.now().minusDays(3)
        endTime = startTime.plusHours(2)
        setAttendance(testUser, AttendanceStatus.PLANNED)
        createdBy = testUser
    }

    @Test
    fun `stats page renders in Polish when locale is pl`() {
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(
            stats(SessionCounts(upcoming = 2, unconfirmed = 3, attended = 18, skipped = 2, cancelled = 0), 750, 90, 5)
        )

        mockMvc.perform(get("/training-events/stats").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Godziny treningu")))
            .andExpect(content().string(containsString("Frekwencja")))
            .andExpect(content().string(containsString("Seria")))
            .andExpect(content().string(containsString("z rzędu, w całym okresie")))
            .andExpect(content().string(containsString("pominięte: 2 · nadchodzące: 2")))
            .andExpect(content().string(containsString("Minione treningi do potwierdzenia: 3")))
            .andExpect(content().string(containsString("Czas według stylu")))
            .andExpect(content().string(containsString("Czas według typu treningu")))
            .andExpect(content().string(not(containsString("Hours trained"))))
            .andExpect(content().string(not(containsString("Time by style"))))
            .andExpect(content().string(not(containsString("past sessions need confirming"))))
    }

    @Test
    fun `stats empty state renders in Polish when locale is pl`() {
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(
            stats(SessionCounts(upcoming = 0, unconfirmed = 0, attended = 0, skipped = 0, cancelled = 0), 0, null, 0)
        )

        mockMvc.perform(get("/training-events/stats").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nie zapisano jeszcze żadnych treningów")))
            .andExpect(content().string(containsString("Dodaj trening")))
            .andExpect(content().string(not(containsString("No sessions logged yet"))))
            .andExpect(content().string(not(containsString("Add Session"))))
    }

    @Test
    fun `stats page keeps its English text when locale is en`() {
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)).thenReturn(
            stats(SessionCounts(upcoming = 2, unconfirmed = 3, attended = 18, skipped = 2, cancelled = 0), 750, 90, 5)
        )

        mockMvc.perform(get("/training-events/stats").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hours trained")))
            .andExpect(content().string(containsString("consecutive, all time")))
            .andExpect(content().string(containsString("2 skipped · 2 upcoming")))
            .andExpect(content().string(containsString("3 past sessions need confirming")))
            .andExpect(content().string(containsString("Time by session type")))
    }

    @Test
    fun `timeline empty state renders in Polish when locale is pl`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java)))
            .thenReturn(emptyTimeline())

        mockMvc.perform(get("/training-events/timeline").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nie zapisano jeszcze treningów")))
            .andExpect(content().string(containsString("Twój trening po kolei")))
            .andExpect(content().string(containsString("Oś czasu")))
            .andExpect(content().string(not(containsString("No training logged yet"))))
            .andExpect(content().string(not(containsString("Your training in order"))))
    }

    @Test
    fun `timeline empty state keeps its English text when locale is en`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java)))
            .thenReturn(emptyTimeline())

        mockMvc.perform(get("/training-events/timeline").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No training logged yet")))
            .andExpect(content().string(containsString("Once you log a session it will show up here, newest first.")))
            .andExpect(content().string(containsString("Your training in order — what is coming, and everything behind it.")))
    }

    @Test
    fun `calendar page and its quick-create card render in Polish when locale is pl`() {
        mockMvc.perform(get("/training-events/calendar").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Kliknij przedział w kalendarzu")))
            .andExpect(content().string(containsString("Dotknij dnia, aby go zobaczyć.")))
            .andExpect(content().string(containsString("Nowy trening")))
            .andExpect(content().string(not(containsString("Click a slot to add a session"))))
            .andExpect(content().string(not(containsString("Tap a day to read it"))))

        mockMvc.perform(
            get("/training-events/quick-create")
                .param("start", "2026-09-14T18:00:00")
                .param("end", "2026-09-14T19:00:00")
                .locale(polish)
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Dodaj tytuł")))
            .andExpect(content().string(containsString("Nie powtarza się")))
            .andExpect(content().string(containsString("Powtarzaj do")))
            .andExpect(content().string(containsString("Więcej opcji")))
            .andExpect(content().string(containsString("Zapisz")))
            .andExpect(content().string(not(containsString("Does not repeat"))))
            .andExpect(content().string(not(containsString("More options"))))
    }

    @Test
    fun `calendar page keeps its English text when locale is en`() {
        mockMvc.perform(get("/training-events/calendar").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Click a slot to add a session, or drag one to move it.")))
            .andExpect(content().string(containsString("Tap a day to read it. Tap + to add a session.")))
    }

    @Test
    fun `session form renders in Polish when locale is pl`() {
        mockMvc.perform(get("/training-events/new").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Dodaj trening")))
            .andExpect(content().string(containsString("Kończy się innego dnia")))
            .andExpect(content().string(containsString("Powtarzanie")))
            .andExpect(content().string(containsString("Podziel trening na style")))
            .andExpect(content().string(containsString("Dodaj styl")))
            .andExpect(content().string(containsString("Nad czym pracowałeś(-aś)?")))
            .andExpect(content().string(containsString("Zapisz trening")))
            .andExpect(content().string(containsString("Anuluj")))
            .andExpect(content().string(not(containsString("Add Training Session"))))
            .andExpect(content().string(not(containsString("Ends on a different day"))))
            .andExpect(content().string(not(containsString("Save Session"))))
            .andExpect(content().string(not(containsString("What did you work on?"))))
    }

    @Test
    fun `session form keeps its English text when locale is en`() {
        mockMvc.perform(get("/training-events/new").locale(Locale.ENGLISH).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Add Training Session")))
            .andExpect(content().string(containsString("Saved here and written through to your Google Calendar.")))
            .andExpect(content().string(containsString("Ends on a different day")))
            .andExpect(content().string(containsString("Save Session")))
            .andExpect(content().string(containsString("Cancel")))
    }

    @Test
    fun `agenda list and its attendance badge render in Polish when locale is pl`() {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null))
            .thenReturn(listOf(awaitingSession()))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Wszystkie typy")))
            .andExpect(content().string(containsString("Zaznacz wszystko")))
            .andExpect(content().string(containsString("Wymaga potwierdzenia")))
            .andExpect(content().string(containsString("Oznacz jako obecny")))
            .andExpect(content().string(containsString("Zapisane treningi: 1")))
            .andExpect(content().string(containsString("120 min")))
            .andExpect(content().string(not(containsString("Needs confirmation"))))
            .andExpect(content().string(not(containsString("Mark Attended"))))
            .andExpect(content().string(not(containsString("Select all"))))
    }
}
