package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.dto.WeekDayState
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.Choreography
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.DanceFigureStepSet
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import com.jankowski.rafal.dancebook.repository.CustomListRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Friday 25 September 2026, 14:30. The week strip therefore runs Monday 21st to Sunday 27th.
 */
class DashboardServiceTest {

    private val now = LocalDateTime.of(2026, 9, 25, 14, 30)
    private val clock: Clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private lateinit var trainingEventService: TrainingEventService
    private lateinit var trainingStatsService: TrainingStatsService
    private lateinit var activeCalendarService: ActiveCalendarService
    private lateinit var appUserService: AppUserService
    private lateinit var materialRepository: MaterialRepository
    private lateinit var choreographyRepository: ChoreographyRepository
    private lateinit var customListRepository: CustomListRepository
    private lateinit var user: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventService = mock(TrainingEventService::class.java)
        trainingStatsService = mock(TrainingStatsService::class.java)
        activeCalendarService = mock(ActiveCalendarService::class.java)
        appUserService = mock(AppUserService::class.java)
        materialRepository = mock(MaterialRepository::class.java)
        choreographyRepository = mock(ChoreographyRepository::class.java)
        customListRepository = mock(CustomListRepository::class.java)

        user = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Rafał"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(activeCalendarService.active()).thenReturn(null)
        `when`(trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_MONTH)).thenReturn(
            TrainingStats(
                period = StatsPeriod.THIS_MONTH,
                totalMinutesTrained = 690,
                counts = SessionCounts(upcoming = 1, unconfirmed = 2, attended = 9, skipped = 1, cancelled = 0),
                attendanceRatePercent = 90,
                currentStreak = 4,
                byCategory = emptyList(),
                byEventType = emptyList()
            )
        )
        `when`(materialRepository.findRecentByOwner(user, PageRequest.of(0, 3))).thenReturn(emptyList())
        `when`(choreographyRepository.findAllByOwner(user)).thenReturn(emptyList())
        `when`(customListRepository.findAllByOwner(user)).thenReturn(emptyList())
        stubWaiting(emptyList())
        stubRange(monday, emptyList())
        stubRange(now, emptyList(), plusDays = 365)
    }

    private fun service(at: Clock = clock) = DashboardServiceImpl(
        trainingEventService, trainingStatsService, activeCalendarService, appUserService,
        materialRepository, choreographyRepository, customListRepository, at
    )

    private fun stubWaiting(events: List<TrainingEvent>) {
        `when`(trainingEventService.findByCurrentUser(null, null, null, null, true, null)).thenReturn(events)
    }

    /** The week strip asks for Monday 00:00 to next Monday 00:00; "next session" asks now to now+365d. */
    private fun stubRange(from: LocalDateTime, events: List<TrainingEvent>, plusDays: Long = 7) {
        `when`(trainingEventService.findInRange(from, from.plusDays(plusDays), null)).thenReturn(events)
    }

    private val monday: LocalDateTime = LocalDate.of(2026, 9, 21).atStartOfDay()

    private fun session(
        title: String,
        start: LocalDateTime,
        status: AttendanceStatus = AttendanceStatus.PLANNED,
        minutes: Long = 60
    ) = TrainingEvent().apply {
        id = UUID.randomUUID()
        this.title = title
        startTime = start
        endTime = start.plusMinutes(minutes)
        if (status != AttendanceStatus.PLANNED) setAttendance(user, status)
    }

    // ---- greeting -------------------------------------------------------------------------

    @Test
    fun `greeting follows the hour with morning from 05 and evening from 18`() {
        assertEquals("Good evening", DashboardServiceImpl.greetingFor(4))
        assertEquals("Good morning", DashboardServiceImpl.greetingFor(5))
        assertEquals("Good morning", DashboardServiceImpl.greetingFor(11))
        assertEquals("Good afternoon", DashboardServiceImpl.greetingFor(12))
        assertEquals("Good afternoon", DashboardServiceImpl.greetingFor(17))
        assertEquals("Good evening", DashboardServiceImpl.greetingFor(18))
        assertEquals("Good evening", DashboardServiceImpl.greetingFor(23))
    }

    @Test
    fun `greeting and date come from the injected clock and name the user`() {
        val view = service().dashboardForCurrentUser()

        assertEquals("Good afternoon, Rafał.", view.greeting)
        assertEquals("Friday 25 September", view.dateLabel)
    }

    @Test
    fun `greeting falls back to the username when there is no display name`() {
        user.displayName = " "

        assertEquals("Good afternoon, tester.", service().dashboardForCurrentUser().greeting)
    }

    // ---- wrap-up --------------------------------------------------------------------------

    @Test
    fun `wrap-up lists unconfirmed sessions newest first, with the newest as the hero`() {
        val older = session("Older class", LocalDateTime.of(2026, 9, 22, 19, 0))
        val newest = session("Newest class", LocalDateTime.of(2026, 9, 24, 18, 0))
        val oldest = session("Oldest class", LocalDateTime.of(2026, 9, 20, 10, 0))
        stubWaiting(listOf(older, newest, oldest))

        val wrapUp = service().dashboardForCurrentUser().wrapUp

        assertEquals(listOf("Newest class", "Older class", "Oldest class"), wrapUp.waiting.map { it.title })
        assertEquals("Newest class", wrapUp.hero?.title)
        assertEquals(listOf("Older class", "Oldest class"), wrapUp.older.map { it.title })
        assertNull(wrapUp.next, "the next session only fills the slot when nothing is waiting")
    }

    @Test
    fun `wrap-up ignores a session the service returned that has not actually ended`() {
        stubWaiting(listOf(session("Still on", now.minusMinutes(10), minutes = 90)))

        assertTrue(service().dashboardForCurrentUser().wrapUp.waiting.isEmpty())
    }

    @Test
    fun `wrap-up falls back to the next planned session when nothing is waiting`() {
        val later = session("Later", now.plusDays(3))
        val sooner = session("Sooner", now.plusDays(1))
        val alreadyAttended = session("Attended", now.plusHours(1), AttendanceStatus.ATTENDED)
        stubRange(now, listOf(later, alreadyAttended, sooner), plusDays = 365)

        val wrapUp = service().dashboardForCurrentUser().wrapUp

        assertNull(wrapUp.hero)
        assertEquals("Sooner", wrapUp.next?.title)
    }

    @Test
    fun `wrap-up is empty for a user with no sessions at all`() {
        val wrapUp = service().dashboardForCurrentUser().wrapUp

        assertNull(wrapUp.hero)
        assertNull(wrapUp.next)
    }

    @Test
    fun `a session card carries date, time, segments and its linked note`() {
        val note = Material().apply { id = UUID.randomUUID() }
        val event = session("Standard group class", LocalDateTime.of(2026, 9, 23, 19, 0), minutes = 90).apply {
            segments = mutableListOf(
                TrainingEventSegment().apply {
                    danceCategory = DanceCategory().apply { name = "Waltz" }
                    durationMinutes = 45
                },
                TrainingEventSegment().apply {
                    danceCategory = DanceCategory().apply { name = "Foxtrot" }
                    durationMinutes = 45
                }
            )
            material = note
        }
        stubWaiting(listOf(event))

        val card = service().dashboardForCurrentUser().wrapUp.hero!!

        assertEquals("Wednesday 23 Sep", card.dateLabel)
        assertEquals("19:00–20:30", card.timeLabel)
        assertEquals(listOf("Waltz", "Foxtrot"), card.segments.map { it.label })
        assertEquals(listOf("45m", "45m"), card.segments.map { it.durationLabel })
        assertEquals(note.id, card.noteId)
    }

    // ---- week strip -----------------------------------------------------------------------

    @Test
    fun `week strip runs Monday to Sunday and marks today`() {
        val week = service().dashboardForCurrentUser().week

        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), week.days.map { it.weekdayLabel })
        assertEquals(listOf(21, 22, 23, 24, 25, 26, 27), week.days.map { it.dayOfMonth })
        assertEquals(listOf(false, false, false, false, true, false, false), week.days.map { it.today })
        assertTrue(week.days.all { it.state == WeekDayState.NONE })
    }

    @Test
    fun `week strip maps each day's state, with unconfirmed taking precedence`() {
        stubRange(
            monday,
            listOf(
                session("Mon attended", LocalDateTime.of(2026, 9, 21, 19, 0), AttendanceStatus.ATTENDED),
                session("Tue unconfirmed", LocalDateTime.of(2026, 9, 22, 19, 0)),
                // Wednesday holds an attended session and an unconfirmed one: unconfirmed wins.
                session("Wed attended", LocalDateTime.of(2026, 9, 23, 10, 0), AttendanceStatus.ATTENDED),
                session("Wed unconfirmed", LocalDateTime.of(2026, 9, 23, 19, 0)),
                session("Sat planned", LocalDateTime.of(2026, 9, 26, 10, 0)),
                session("Sun skipped", LocalDateTime.of(2026, 9, 27, 10, 0), AttendanceStatus.SKIPPED)
            )
        )

        val states = service().dashboardForCurrentUser().week.days.map { it.state }

        assertEquals(
            listOf(
                WeekDayState.ATTENDED, WeekDayState.UNCONFIRMED, WeekDayState.UNCONFIRMED,
                WeekDayState.NONE, WeekDayState.NONE, WeekDayState.PLANNED, WeekDayState.NONE
            ),
            states
        )
    }

    @Test
    fun `a session later today is planned, not unconfirmed`() {
        stubRange(monday, listOf(session("Tonight", LocalDateTime.of(2026, 9, 25, 19, 0))))

        val friday = service().dashboardForCurrentUser().week.days[4]

        assertEquals(WeekDayState.PLANNED, friday.state)
        assertTrue(friday.today)
    }

    @Test
    fun `week strip reports the streak and how many sessions are waiting`() {
        stubWaiting(listOf(session("A", now.minusDays(2)), session("B", now.minusDays(3))))

        val week = service().dashboardForCurrentUser().week

        assertEquals(4, week.streak)
        assertEquals(2, week.toConfirmCount)
    }

    // ---- month ----------------------------------------------------------------------------

    @Test
    fun `month summary comes from this month's stats`() {
        val month = service().dashboardForCurrentUser().month

        assertEquals("11h 30m", month.trainedLabel)
        assertEquals(9, month.attended)
        assertEquals(90, month.attendanceRatePercent)
    }

    // ---- recent notes ---------------------------------------------------------------------

    private fun figure(name: String, vararg timings: String) = DanceFigure().apply {
        this.name = name
        stepSets = mutableListOf(
            DanceFigureStepSet().apply {
                isDefault = true
                steps = timings.mapIndexed { index, timing ->
                    DanceFigureStep().apply {
                        stepNumber = index + 1
                        this.timing = timing
                        role = "LEADER"
                    }
                }.toMutableList()
            }
        )
    }

    private fun note(vararg pins: DanceFigure) = Material().apply {
        id = UUID.randomUUID()
        name = "Feather Step — keeping the head left"
        description = "<div>Head drops on step 2. <strong>Keep</strong> the left side long.</div>"
        createdAt = now.minusDays(2)
        updatedAt = now.minusDays(2)
        figures = pins.map { pinned -> Figure().apply { danceFigure = pinned } }.toMutableList()
    }

    @Test
    fun `a recent note shows each pinned figure with its timing`() {
        `when`(materialRepository.findRecentByOwner(user, PageRequest.of(0, 3)))
            .thenReturn(listOf(note(figure("Feather Step", "S", "Q", "Q"))))

        val card = service().dashboardForCurrentUser().recentNotes.single()

        assertEquals("2 days ago", card.whenLabel)
        assertEquals("Head drops on step 2. Keep the left side long.", card.excerpt)
        assertEquals("Feather Step", card.figures.single().name)
        assertEquals("S Q Q", card.figures.single().timing)
    }

    @Test
    fun `a pinned figure with no timing is listed without a value, never as an empty list`() {
        `when`(materialRepository.findRecentByOwner(user, PageRequest.of(0, 3))).thenReturn(
            listOf(note(figure("No Steps"), figure("Blank Steps", " ", "")))
        )

        val figures = service().dashboardForCurrentUser().recentNotes.single().figures

        assertEquals(listOf("No Steps", "Blank Steps"), figures.map { it.name })
        assertTrue(figures.all { it.timing == null })
    }

    @Test
    fun `a long note is cut to an excerpt on a word boundary`() {
        val long = Material().apply {
            id = UUID.randomUUID()
            name = "Long"
            description = "<p>" + "word ".repeat(80) + "</p>"
            createdAt = now
        }
        `when`(materialRepository.findRecentByOwner(user, PageRequest.of(0, 3))).thenReturn(listOf(long))

        val excerpt = service().dashboardForCurrentUser().recentNotes.single().excerpt

        assertTrue(excerpt.endsWith("…"))
        assertTrue(excerpt.length <= 141)
        assertTrue(excerpt.removeSuffix("…").split(" ").all { it == "word" })
    }

    // ---- continue -------------------------------------------------------------------------

    @Test
    fun `continue mixes choreographies and collections, newest first, at most three`() {
        val newest = Choreography().apply {
            id = UUID.randomUUID(); name = "Waltz routine"; updatedAt = now.minusDays(2)
        }
        val oldChoreo = Choreography().apply {
            id = UUID.randomUUID(); name = "Old routine"; updatedAt = now.minusDays(40)
        }
        val list = CustomList().apply {
            id = UUID.randomUUID(); name = "Silver Foxtrot"; createdAt = now.minusDays(10)
        }
        val oldList = CustomList().apply {
            id = UUID.randomUUID(); name = "Old list"; createdAt = now.minusDays(90)
        }
        `when`(choreographyRepository.findAllByOwner(user)).thenReturn(listOf(oldChoreo, newest))
        `when`(customListRepository.findAllByOwner(user)).thenReturn(listOf(oldList, list))

        val items = service().dashboardForCurrentUser().continueItems

        assertEquals(listOf("Waltz routine", "Silver Foxtrot", "Old routine"), items.map { it.name })
        assertEquals("/choreographies/${newest.id}", items[0].href)
        assertEquals("/lists/${list.id}", items[1].href)
        assertEquals("0 figures · edited 2 days ago", items[0].detail)
    }
}
