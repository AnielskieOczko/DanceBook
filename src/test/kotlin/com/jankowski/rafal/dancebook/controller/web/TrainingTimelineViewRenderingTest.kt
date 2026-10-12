package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRow
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.dto.TrainingTimelineEntry
import com.jankowski.rafal.dancebook.dto.TrainingTimelineMonth
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.io.File
import java.time.LocalDateTime
import java.util.Properties
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

    @MockitoBean private lateinit var trainingTimelineService: TrainingTimelineService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        val defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default Calendar"
            enabled = true
        }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(defaultCal))
        `when`(activeCalendarService.active()).thenReturn(null)
    }

    @Test
    fun `renders the timeline with month headings, palette colours and the today marker`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
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
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
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
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
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
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
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
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
            timeline(month("September 2026", entry(daysAgo = 1, status = AttendanceStatus.ATTENDED)))
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Load older sessions"))))
    }

    @Test
    fun `shows the empty state when there is no training history at all`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(timeline())

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No training logged yet")))
    }

    @Test
    fun `renders weekly rollup with week label, trained time, attendance ratio, and dots`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
            timeline(
                month(
                    "September 2026",
                    entry(daysAgo = 2, status = AttendanceStatus.ATTENDED),
                    entry(daysAgo = 4, status = AttendanceStatus.SKIPPED)
                )
            )
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Week ")))
            .andExpect(content().string(containsString("Sessions")))
            .andExpect(content().string(containsString("Trained")))
            .andExpect(content().string(containsString("Attended")))
            .andExpect(content().string(containsString("1/2")))
            .andExpect(content().string(containsString("tc-roll")))
    }

    @Test
    fun `renders streak milestone marker with badge slot and fire icon`() {
        val entryWithStreak = entry(daysAgo = 2, status = AttendanceStatus.ATTENDED).copy(
            streakMilestone = 10,
            streakReachedDate = "Sun 11 Oct",
            streakTier = "bronze"
        )
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
            timeline(month("October 2026", entryWithStreak))
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("10 sessions in a row")))
            .andExpect(content().string(containsString("Streak reached on Sun 11 Oct")))
            .andExpect(content().string(containsString("Badge slot")))
            .andExpect(content().string(containsString("tc-slot-bronze")))
            .andExpect(content().string(containsString("local_fire_department")))
    }

    @Test
    fun `renders session details with attendance actions for unconfirmed session`() {
        val start = LocalDateTime.now().minusDays(1)
        val unconfirmedEvent = TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Unconfirmed drill"
            startTime = start
            endTime = start.plusMinutes(90)
            setAttendance(testUser, AttendanceStatus.PLANNED)
            eventType = TrainingEventType.TRAINING
            createdBy = testUser
        }
        val unconfirmedEntry = TrainingTimelineEntry(
            row = TrainingEventRow(unconfirmedEvent, TrainingEventPalette.UNCONFIRMED),
            isFuture = false,
            startsTodayBoundary = false
        )

        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), nullable(UUID::class.java))).thenReturn(
            timeline(month("September 2026", unconfirmedEntry))
        )

        mockMvc.perform(get("/training-events/timeline").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Unconfirmed drill")))
            .andExpect(content().string(containsString("I attended")))
            .andExpect(content().string(containsString("I skipped")))
            .andExpect(content().string(containsString("action=\"/training-events/${unconfirmedEvent.id}/attendance\"")))
    }

    @Test
    fun `every message key used in timeline template exists in both English and Polish message bundles`() {
        val templateFile = File("src/main/resources/templates/training-events/timeline.html")
        assertTrue(templateFile.isFile, "timeline.html must exist")
        val content = templateFile.readText(Charsets.UTF_8)

        val regex = Regex("""#\{([a-zA-Z0-9_.-]+)""")
        val keysUsed = regex.findAll(content).map { it.groupValues[1] }.toSet()

        val enProps = Properties().apply {
            File("src/main/resources/messages.properties").reader(Charsets.UTF_8).use { load(it) }
        }
        val plProps = Properties().apply {
            File("src/main/resources/messages_pl.properties").reader(Charsets.UTF_8).use { load(it) }
        }

        val missingInEn = keysUsed.filterNot { enProps.containsKey(it) }.sorted()
        val missingInPl = keysUsed.filterNot { plProps.containsKey(it) }.sorted()

        assertTrue(
            missingInEn.isEmpty(),
            "Message keys in timeline.html missing from messages.properties:\n" + missingInEn.joinToString("\n")
        )
        assertTrue(
            missingInPl.isEmpty(),
            "Message keys in timeline.html missing from messages_pl.properties:\n" + missingInPl.joinToString("\n")
        )
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
            setAttendance(testUser, status)
            eventType = TrainingEventType.TRAINING
            createdBy = testUser
        }
        return TrainingTimelineEntry(
            row = TrainingEventRow(event, TrainingEventPalette.swatchFor(event, testUser)),
            isFuture = daysAgo < 0,
            startsTodayBoundary = boundary
        )
    }
}
