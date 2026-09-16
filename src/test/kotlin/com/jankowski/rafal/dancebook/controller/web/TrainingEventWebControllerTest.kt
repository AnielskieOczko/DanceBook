package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.BulkAttendanceResult
import com.jankowski.rafal.dancebook.dto.BulkDeleteResult
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingMonthGroup
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.CalendarSyncException
import com.jankowski.rafal.dancebook.service.CalendarSyncOutcome
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.SyncReport
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpStatus
import org.springframework.ui.ConcurrentModel
import org.springframework.validation.BeanPropertyBindingResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class TrainingEventWebControllerTest {

    private lateinit var trainingEventService: TrainingEventService
    private lateinit var trainingSeriesService: TrainingSeriesService
    private lateinit var danceCategoryService: DanceCategoryService
    private lateinit var activeCalendarService: ActiveCalendarService
    private lateinit var trainingCalendarService: TrainingCalendarService
    private lateinit var calendarSyncService: CalendarSyncService
    private lateinit var materialService: com.jankowski.rafal.dancebook.service.MaterialService
    private lateinit var controller: TrainingEventWebController
    private lateinit var defaultCal: TrainingCalendar

    @BeforeEach
    fun setUp() {
        trainingEventService = mock(TrainingEventService::class.java)
        trainingSeriesService = mock(TrainingSeriesService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        activeCalendarService = mock(ActiveCalendarService::class.java)
        trainingCalendarService = mock(TrainingCalendarService::class.java)
        calendarSyncService = mock(CalendarSyncService::class.java)
        materialService = mock(com.jankowski.rafal.dancebook.service.MaterialService::class.java)
        defaultCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Default"
            isDefault = true
            enabled = true
        }
        `when`(trainingCalendarService.findDefault()).thenReturn(defaultCal)
        `when`(activeCalendarService.creationTarget()).thenReturn(defaultCal)
        `when`(activeCalendarService.validateCreationTarget(any())).thenReturn(defaultCal)
        controller = TrainingEventWebController(
            trainingEventService,
            trainingSeriesService,
            danceCategoryService,
            activeCalendarService,
            trainingCalendarService,
            calendarSyncService,
            materialService
        )
    }

    @Test
    fun `should list training events with filter data on a full page request`() {
        val model = ConcurrentModel()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.listTrainingEvents(model = model)

        assertEquals("training-events/list", viewName)
        assertEquals(emptyList<TrainingEvent>(), model["events"])
        assertEquals(emptyList<DanceCategory>(), model["danceCategories"])
    }

    @Test
    fun `should return only the list fragment and skip filter data on an HTMX request`() {
        val model = ConcurrentModel()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(emptyList())

        val viewName = controller.listTrainingEvents(isHtmxRequest = true, model = model)

        assertEquals("training-events/list :: eventsList", viewName)
        // Dropdown data is deliberately not loaded for a fragment swap.
        assertNull(model["danceCategories"])
        assertNull(model["eventTypeOptions"])
    }

    @Test
    fun `should pass filters through to the service`() {
        val model = ConcurrentModel()
        val categoryId = UUID.randomUUID()
        `when`(
            trainingEventService.findByCurrentUser(
                listOf(TrainingEventType.CAMP),
                listOf(categoryId),
                listOf(AttendanceStatus.ATTENDED),
                "camp"
            )
        ).thenReturn(emptyList())

        controller.listTrainingEvents(
            eventTypes = listOf(TrainingEventType.CAMP),
            categoryIds = listOf(categoryId),
            attendanceStatuses = listOf(AttendanceStatus.ATTENDED),
            search = "camp",
            model = model
        )

        verify(trainingEventService).findByCurrentUser(
            listOf(TrainingEventType.CAMP),
            listOf(categoryId),
            listOf(AttendanceStatus.ATTENDED),
            "camp"
        )
        assertEquals("camp", model["search"])
    }

    @Test
    fun `should map an existing event onto the edit form`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val category = DanceCategory().apply {
            this.id = UUID.randomUUID()
            name = "Standard"
        }
        val event = TrainingEvent().apply {
            this.id = id
            title = "Monday practice"
            startTime = LocalDateTime.of(2026, 9, 10, 18, 0)
            endTime = LocalDateTime.of(2026, 9, 10, 20, 0)
            eventType = TrainingEventType.CAMP
            attendanceStatus = AttendanceStatus.ATTENDED
        }
        val ownCal = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            displayName = "Own Calendar"
            enabled = true
        }
        event.calendar = ownCal
        event.segments.add(TrainingEventSegment().apply {
            trainingEvent = event
            danceCategory = category
            durationMinutes = 90
            sortOrder = 0
        })
        `when`(trainingEventService.findById(id)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))

        val viewName = controller.showEditForm(id, model)

        assertEquals("training-events/form", viewName)
        assertEquals(id, model["trainingEventId"])
        val request = model["trainingEvent"] as TrainingEventRequest
        assertEquals("Monday practice", request.title)
        assertEquals(LocalDate.of(2026, 9, 10), request.date)
        assertEquals(LocalTime.of(18, 0), request.startTime)
        assertEquals(LocalTime.of(20, 0), request.endTime)
        assertEquals("CAMP", request.eventType)
        // The edit form carries no calendar: a session cannot be moved between calendars,
        // so the request deliberately no longer prefills one.
        assertNull(request.calendarId)
        assertEquals(1, request.segments.size)
        assertEquals(category.id, request.segments[0].categoryId)
        assertEquals(90, request.segments[0].durationMinutes)
        assertEquals("ATTENDED", request.attendanceStatus)
    }

    @Test
    fun `showCreateForm populates form options and names target calendar on request`() {
        val model = ConcurrentModel()
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.showCreateForm(model)

        assertEquals("training-events/form", viewName)
        assertNull(model["calendars"])
        assertNull(model["defaultCalendarId"])
        assertEquals(defaultCal, model["targetCalendar"])
        val request = model["trainingEvent"] as TrainingEventRequest
        assertEquals(defaultCal.id, request.calendarId)
    }

    @Test
    fun `showCreateForm names active calendar when specific calendar is active`() {
        val model = ConcurrentModel()
        val club = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Club Training"
            enabled = true
        }
        `when`(activeCalendarService.creationTarget()).thenReturn(club)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.showCreateForm(model)

        assertEquals("training-events/form", viewName)
        assertEquals(club, model["targetCalendar"])
        val request = model["trainingEvent"] as TrainingEventRequest
        assertEquals(club.id, request.calendarId)
    }

    @Test
    fun `showCreateForm records error when active calendar is disabled`() {
        val model = ConcurrentModel()
        `when`(activeCalendarService.creationTarget()).thenThrow(
            CalendarSyncException("Retired is disabled — choose another calendar to create a session.")
        )
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.showCreateForm(model)

        assertEquals("training-events/form", viewName)
        assertEquals("Retired is disabled — choose another calendar to create a session.", model["calendarError"])
        assertNull(model["targetCalendar"])
        val request = model["trainingEvent"] as TrainingEventRequest
        assertNull(request.calendarId)
    }

    @Test
    fun `should redisplay the form with dropdown data when validation fails`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest()
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")
        bindingResult.rejectValue("title", "NotBlank")
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.createTrainingEvent(request, bindingResult, model)

        assertEquals("training-events/form", viewName)
        assertEquals(emptyList<DanceCategory>(), model["danceCategories"])
    }

    @Test
    fun `should surface a sync failure as a form error instead of a redirect`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest(
            title = "Monday practice",
            date = LocalDate.of(2026, 9, 10),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0)
        )
        val scopedRequest = request.copy(calendarId = defaultCal.id)
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(trainingEventService.create(scopedRequest)).thenThrow(RuntimeException("Google Calendar unavailable"))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.createTrainingEvent(request, bindingResult, model)

        assertEquals("training-events/form", viewName)
        assertEquals(1, bindingResult.fieldErrorCount)
        assertEquals("title", bindingResult.fieldErrors[0].field)
    }

    @Test
    fun `should redirect back to the detail page when attendance is posted without HTMX`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()

        val viewName = controller.updateAttendance(id, AttendanceStatus.ATTENDED, null, model)

        assertEquals("redirect:/training-events/$id", viewName)
        verify(trainingEventService).updateAttendance(id, AttendanceStatus.ATTENDED)
    }

    @Test
    fun `should swap the list fragment when attendance is posted over HTMX`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(emptyList())

        val viewName = controller.updateAttendance(id, AttendanceStatus.SKIPPED, true, model)

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(emptyList<TrainingEvent>(), model["events"])
    }

    @Test
    fun `should keep the active calendar when swapping the list after an attendance post`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = UUID.randomUUID()
            googleCalendarId = "club@group.calendar.google.com"
            displayName = "Club"
        }
        `when`(activeCalendarService.active()).thenReturn(calendar)
        `when`(trainingEventService.findByCurrentUser(calendarId = calendar.id)).thenReturn(emptyList())

        controller.updateAttendance(id, AttendanceStatus.ATTENDED, true, model)

        // The swap replaces the whole agenda fragment, so it must stay scoped to the
        // calendar the user is looking at rather than reverting to every calendar.
        verify(trainingEventService).findByCurrentUser(calendarId = calendar.id)
    }

    @Test
    fun `creating with a disabled calendar active redisplays the form with an error rather than failing`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest(
            title = "Evening practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(20, 30)
        )
        val binding = BeanPropertyBindingResult(request, "trainingEvent")
        doThrow(CalendarSyncException("Retired is disabled — choose another calendar to create a session."))
            .`when`(activeCalendarService).validateCreationTarget(any())
        doThrow(CalendarSyncException("Retired is disabled — choose another calendar to create a session."))
            .`when`(activeCalendarService).creationTarget()

        val view = controller.createTrainingEvent(request, binding, model)

        // The create controls are hidden in this state, but the URL is still reachable, so the
        // refusal has to surface as a form error rather than propagate out of the handler.
        assertEquals("training-events/form", view)
        assertTrue(binding.hasFieldErrors("title"))
        verifyNoInteractions(trainingEventService)
    }

    @Test
    fun `createTrainingEvent refuses create when no calendarId is supplied and All calendars is active`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest(
            title = "Practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            calendarId = null
        )
        val binding = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(activeCalendarService.validateCreationTarget(null)).thenThrow(
            CalendarSyncException("No target calendar specified — choose a calendar to create a session.")
        )

        val view = controller.createTrainingEvent(request, binding, model)

        assertEquals("training-events/form", view)
        assertTrue(binding.hasFieldErrors("title"))
        assertEquals("No target calendar specified — choose a calendar to create a session.", binding.getFieldError("title")?.defaultMessage)
        verifyNoInteractions(trainingEventService)
    }

    @Test
    fun `createTrainingEvent refuses create when calendarId does not exist`() {
        val model = ConcurrentModel()
        val missingId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            calendarId = missingId
        )
        val binding = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(activeCalendarService.validateCreationTarget(missingId)).thenThrow(
            IllegalArgumentException("Training calendar with id $missingId not found")
        )

        val view = controller.createTrainingEvent(request, binding, model)

        assertEquals("training-events/form", view)
        assertTrue(binding.hasFieldErrors("title"))
        verifyNoInteractions(trainingEventService)
    }

    @Test
    fun `createTrainingEvent refuses create when calendarId is disabled`() {
        val model = ConcurrentModel()
        val disabledId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            calendarId = disabledId
        )
        val binding = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(activeCalendarService.validateCreationTarget(disabledId)).thenThrow(
            CalendarSyncException("Retired is disabled — choose another calendar to create a session.")
        )

        val view = controller.createTrainingEvent(request, binding, model)

        assertEquals("training-events/form", view)
        assertTrue(binding.hasFieldErrors("title"))
        verifyNoInteractions(trainingEventService)
    }

    @Test
    fun `createTrainingEvent refuses create when specific calendar is active and submission names a different calendar`() {
        val model = ConcurrentModel()
        val otherId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            calendarId = otherId
        )
        val binding = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(activeCalendarService.validateCreationTarget(otherId)).thenThrow(
            IllegalArgumentException("Cannot create session in 'Other': active calendar is 'Default'.")
        )

        val view = controller.createTrainingEvent(request, binding, model)

        assertEquals("training-events/form", view)
        assertTrue(binding.hasFieldErrors("title"))
        verifyNoInteractions(trainingEventService)
    }

    @Test
    fun `should route a repeating request to the series service`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest(
            title = "Monday practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            repeat = "WEEKLY",
            repeatUntil = LocalDate.of(2026, 12, 14)
        )
        val scopedRequest = request.copy(calendarId = defaultCal.id)
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")

        val viewName = controller.createTrainingEvent(request, bindingResult, model)

        assertEquals("redirect:/training-events", viewName)
        verify(trainingSeriesService).create(scopedRequest)
        verify(trainingEventService, never()).create(scopedRequest)
    }

    @Test
    fun `should route a non-repeating request to the single event service`() {
        val model = ConcurrentModel()
        val request = TrainingEventRequest(
            title = "One-off workshop",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0)
        )
        val scopedRequest = request.copy(calendarId = defaultCal.id)
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")

        controller.createTrainingEvent(request, bindingResult, model)

        verify(trainingEventService).create(scopedRequest)
        verify(trainingSeriesService, never()).create(scopedRequest)
    }

    @Test
    fun `should apply an edit to following occurrences when that scope is chosen`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Monday practice",
            date = LocalDate.of(2026, 9, 14),
            startTime = LocalTime.of(19, 0),
            endTime = LocalTime.of(21, 0),
            editScope = "THIS_AND_FOLLOWING"
        )
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")

        controller.updateTrainingEvent(id, request, bindingResult, model)

        verify(trainingSeriesService).updateThisAndFollowing(id, request)
        verify(trainingEventService, never()).update(id, request)
    }

    @Test
    fun `should delete following occurrences when that scope is requested`() {
        val id = UUID.randomUUID()

        val viewName = controller.deleteTrainingEvent(id, "THIS_AND_FOLLOWING")

        assertEquals("redirect:/training-events", viewName)
        verify(trainingSeriesService).deleteThisAndFollowing(id)
        verify(trainingEventService, never()).delete(id)
    }

    @Test
    fun `should redirect to the list after deleting`() {
        val id = UUID.randomUUID()

        val viewName = controller.deleteTrainingEvent(id, null)

        assertEquals("redirect:/training-events", viewName)
        verify(trainingEventService).delete(id)
        verify(trainingSeriesService, never()).deleteThisAndFollowing(id)
    }

    @Test
    fun `should group the agenda by month with session counts and total hours`() {
        val model = ConcurrentModel()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(
            listOf(
                session(LocalDateTime.of(2026, 9, 14, 18, 0), 120),
                session(LocalDateTime.of(2026, 9, 17, 19, 0), 90),
                session(LocalDateTime.of(2026, 10, 1, 18, 0), 60)
            )
        )
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        controller.listTrainingEvents(model = model)

        @Suppress("UNCHECKED_CAST")
        val groups = model["monthGroups"] as List<TrainingMonthGroup>
        assertEquals(2, groups.size)
        assertEquals("September 2026", groups[0].label)
        assertEquals(2, groups[0].sessionCount)
        assertEquals("3h 30m", groups[0].totalLabel)
        assertEquals("1h", groups[1].totalLabel)
    }

    @Test
    fun `should carry a status swatch on every agenda row so the template never derives one`() {
        val model = ConcurrentModel()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(
            listOf(session(LocalDateTime.now().minusDays(2), 60))
        )
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        controller.listTrainingEvents(model = model)

        @Suppress("UNCHECKED_CAST")
        val groups = model["monthGroups"] as List<TrainingMonthGroup>
        // A past session still marked PLANNED reads as needing confirmation, not as planned.
        assertEquals("unconfirmed", groups[0].rows[0].swatch.key)
    }

    /**
     * The fragment is the HTMX swap target for both the filters and the attendance buttons,
     * so a path that only set `events` would swap in a list with no month headings at all.
     */
    @Test
    fun `should group the agenda on the HTMX fragment branch too`() {
        val model = ConcurrentModel()
        `when`(trainingEventService.findByCurrentUser(null, null, null, null)).thenReturn(
            listOf(session(LocalDateTime.of(2026, 9, 14, 18, 0), 120))
        )

        controller.listTrainingEvents(isHtmxRequest = true, model = model)

        @Suppress("UNCHECKED_CAST")
        val groups = model["monthGroups"] as List<TrainingMonthGroup>
        assertEquals("September 2026", groups[0].label)
    }

    @Test
    fun `should group the agenda after an attendance confirmation swaps the list back`() {
        val model = ConcurrentModel()
        val id = UUID.randomUUID()
        `when`(trainingEventService.findByCurrentUser()).thenReturn(
            listOf(session(LocalDateTime.of(2026, 9, 14, 18, 0), 120))
        )

        val viewName = controller.updateAttendance(id, AttendanceStatus.ATTENDED, true, model)

        assertEquals("training-events/list :: eventsList", viewName)
        @Suppress("UNCHECKED_CAST")
        val groups = model["monthGroups"] as List<TrainingMonthGroup>
        assertEquals(1, groups[0].sessionCount)
    }

    @Test
    fun `should give the calendar page the same palette the feed colours events with`() {
        val model = ConcurrentModel()

        val viewName = controller.showCalendar(model)

        assertEquals("training-events/calendar", viewName)
        @Suppress("UNCHECKED_CAST")
        val legend = model["statusLegend"] as List<TrainingEventPalette.Swatch>
        assertEquals(5, legend.size)
        assertEquals(legend.map { it.color }.distinct().size, legend.size)
    }

    private fun session(start: LocalDateTime, minutes: Long) = TrainingEvent().apply {
        id = UUID.randomUUID()
        title = "Monday practice"
        startTime = start
        endTime = start.plusMinutes(minutes)
    }

    /** One occurrence of a weekly series that runs until [endsOn]. */
    private fun seriesOccurrence(endsOn: LocalDate): TrainingEvent {
        val parent = TrainingSeries().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            dayOfWeek = java.time.DayOfWeek.MONDAY
            startTime = LocalTime.of(18, 0)
            endTime = LocalTime.of(20, 0)
            startsOn = LocalDate.of(2026, 3, 2)
            this.endsOn = endsOn
        }
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            startTime = LocalDateTime.of(2026, 3, 2, 18, 0)
            endTime = LocalDateTime.of(2026, 3, 2, 20, 0)
            series = parent
        }
    }

    @Test
    fun `should carry the series repeat end date onto the edit form`() {
        val model = ConcurrentModel()
        val endsOn = LocalDate.of(2026, 6, 29)
        val event = seriesOccurrence(endsOn)
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        controller.showEditForm(event.id!!, model)

        val request = model["trainingEvent"] as TrainingEventRequest
        assertEquals(
            endsOn, request.repeatUntil,
            "without the series' own horizon, applying an edit to every occurrence fails " +
            "with 'A repeat end date is required'"
        )
        assertEquals(true, model["isSeriesOccurrence"])
    }

    @Test
    fun `should keep the series scope selector when an update fails`() {
        val model = ConcurrentModel()
        val event = seriesOccurrence(LocalDate.of(2026, 6, 29))
        val request = TrainingEventRequest(
            title = "Renamed",
            date = LocalDate.of(2026, 3, 2),
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            editScope = "THIS_AND_FOLLOWING"
        )
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(trainingSeriesService.updateThisAndFollowing(event.id!!, request))
            .thenThrow(IllegalArgumentException("A repeat end date is required"))
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        val viewName = controller.updateTrainingEvent(event.id!!, request, bindingResult, model)

        assertEquals("training-events/form", viewName)
        assertEquals(
            true, model["isSeriesOccurrence"],
            "otherwise the 'apply changes to' selector disappears and the retry silently " +
            "edits a single occurrence instead of the series"
        )
        assertTrue(bindingResult.hasErrors())
    }

    @Test
    fun `should keep the series scope selector when validation fails`() {
        val model = ConcurrentModel()
        val event = seriesOccurrence(LocalDate.of(2026, 6, 29))
        val request = TrainingEventRequest(editScope = "THIS_AND_FOLLOWING")
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")
        bindingResult.rejectValue("title", "NotBlank")
        `when`(trainingEventService.findById(event.id!!)).thenReturn(event)
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        controller.updateTrainingEvent(event.id!!, request, bindingResult, model)

        assertEquals(true, model["isSeriesOccurrence"])
    }

    @Test
    fun `syncNow returns refresh header on success`() {
        `when`(calendarSyncService.syncAll()).thenReturn(SyncReport(emptyList()))

        val response = controller.syncNow(isHtmx = true)

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertEquals("true", response.headers.getFirst("HX-Refresh"))
    }

    @Test
    fun `syncNow reports failure with 502 when sync fails`() {
        val cal = TrainingCalendar().apply { displayName = "Club" }
        val outcome = CalendarSyncOutcome(calendar = cal, success = false, errorMessage = "Google API timeout")
        `when`(calendarSyncService.syncAll()).thenReturn(SyncReport(listOf(outcome)))

        val response = controller.syncNow(isHtmx = true)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        val body = response.body as? Map<*, *>
        assertEquals("Google API timeout", body?.get("error"))
    }

    @Test
    fun `bulkUpdateAttendance over HTMX refreshes list with filters intact and adds feedback message to model`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        val categoryId = UUID.randomUUID()
        val result = BulkAttendanceResult(updatedCount = 2, futureSkippedCount = 1, status = AttendanceStatus.ATTENDED)
        `when`(trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.ATTENDED)).thenReturn(result)
        `when`(
            trainingEventService.findByCurrentUser(
                eventTypes = listOf(TrainingEventType.TRAINING),
                categoryIds = listOf(categoryId),
                attendanceStatuses = listOf(AttendanceStatus.PLANNED),
                titleSearch = "practice",
                awaitingConfirmation = true,
                calendarId = defaultCal.id
            )
        ).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(defaultCal)

        val viewName = controller.bulkUpdateAttendance(
            sessionIds = ids,
            status = AttendanceStatus.ATTENDED,
            eventTypes = listOf(TrainingEventType.TRAINING),
            categoryIds = listOf(categoryId),
            attendanceStatuses = listOf(AttendanceStatus.PLANNED),
            search = "practice",
            awaitingConfirmation = true,
            isHtmxRequest = true,
            model = model
        )

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(result.message, model["bulkMessage"])
        assertEquals(emptyList<TrainingEvent>(), model["events"])
        verify(trainingEventService).bulkUpdateAttendance(ids, AttendanceStatus.ATTENDED)
        verify(trainingEventService).findByCurrentUser(
            eventTypes = listOf(TrainingEventType.TRAINING),
            categoryIds = listOf(categoryId),
            attendanceStatuses = listOf(AttendanceStatus.PLANNED),
            titleSearch = "practice",
            awaitingConfirmation = true,
            calendarId = defaultCal.id
        )
    }

    @Test
    fun `bulkUpdateAttendance without HTMX redirects to list`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val result = BulkAttendanceResult(updatedCount = 1, futureSkippedCount = 0, status = AttendanceStatus.SKIPPED)
        `when`(trainingEventService.bulkUpdateAttendance(ids, AttendanceStatus.SKIPPED)).thenReturn(result)

        val viewName = controller.bulkUpdateAttendance(
            sessionIds = ids,
            status = AttendanceStatus.SKIPPED,
            isHtmxRequest = false,
            model = model
        )

        assertEquals("redirect:/training-events", viewName)
        verify(trainingEventService).bulkUpdateAttendance(ids, AttendanceStatus.SKIPPED)
    }

    @Test
    fun `bulkDeleteDialog renders confirmModal with count and Google Calendar consequence message`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        val viewName = controller.bulkDeleteDialog(ids, model)

        assertEquals("fragments/confirm-dialog :: confirmModal", viewName)
        assertEquals("Delete Sessions", model["dialogTitle"])
        assertEquals("Delete 3 Sessions", model["confirmLabel"])
        assertEquals("/training-events/bulk-delete", model["confirmUrl"])
        assertEquals("#events-list", model["hxTarget"])
        assertEquals("outerHTML", model["hxSwap"])
        assertEquals("#filterForm", model["hxInclude"])
        assertEquals(3, model["selectedCount"])
        assertEquals(ids, model["sessionIds"])
        val message = model["dialogMessage"] as String
        assertTrue(message.contains("3 training sessions will be deleted"))
        assertTrue(message.contains("removed from Google Calendar"))
    }

    @Test
    fun `bulkDeleteDialog with count exceeding cap renders refusal modal without confirm action`() {
        val model = ConcurrentModel()
        val ids = (1..55).map { UUID.randomUUID() }

        val viewName = controller.bulkDeleteDialog(ids, model)

        assertEquals("fragments/confirm-dialog :: confirmModal", viewName)
        assertEquals("Cannot Delete Sessions", model["dialogTitle"])
        val message = model["dialogMessage"] as String
        assertTrue(message.contains("Cannot delete 55 sessions at once: maximum is 50."))
        assertNull(model["confirmUrl"])
        assertEquals("Close", model["cancelLabel"])
    }

    @Test
    fun `bulkDeleteDialog with empty list renders informative notice without confirm action`() {
        val model = ConcurrentModel()

        val viewName = controller.bulkDeleteDialog(emptyList(), model)

        assertEquals("fragments/confirm-dialog :: confirmModal", viewName)
        assertEquals("No Sessions Selected", model["dialogTitle"])
        assertNull(model["confirmUrl"])
        assertEquals("Close", model["cancelLabel"])
    }

    @Test
    fun `bulkDelete over HTMX refreshes list with filters intact and adds feedback message to model`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        val categoryId = UUID.randomUUID()
        val result = BulkDeleteResult(deletedCount = 2, failedCount = 0)
        `when`(trainingEventService.bulkDelete(ids)).thenReturn(result)
        `when`(
            trainingEventService.findByCurrentUser(
                eventTypes = listOf(TrainingEventType.TRAINING),
                categoryIds = listOf(categoryId),
                attendanceStatuses = listOf(AttendanceStatus.PLANNED),
                titleSearch = "practice",
                awaitingConfirmation = true,
                calendarId = defaultCal.id
            )
        ).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(defaultCal)

        val viewName = controller.bulkDelete(
            sessionIds = ids,
            eventTypes = listOf(TrainingEventType.TRAINING),
            categoryIds = listOf(categoryId),
            attendanceStatuses = listOf(AttendanceStatus.PLANNED),
            search = "practice",
            awaitingConfirmation = true,
            isHtmxRequest = true,
            model = model
        )

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(result.message, model["bulkMessage"])
        assertEquals(emptyList<TrainingEvent>(), model["events"])
        verify(trainingEventService).bulkDelete(ids)
        verify(trainingEventService).findByCurrentUser(
            eventTypes = listOf(TrainingEventType.TRAINING),
            categoryIds = listOf(categoryId),
            attendanceStatuses = listOf(AttendanceStatus.PLANNED),
            titleSearch = "practice",
            awaitingConfirmation = true,
            calendarId = defaultCal.id
        )
    }

    @Test
    fun `bulkDelete without HTMX redirects to list`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val result = BulkDeleteResult(deletedCount = 1, failedCount = 0)
        `when`(trainingEventService.bulkDelete(ids)).thenReturn(result)

        val viewName = controller.bulkDelete(
            sessionIds = ids,
            isHtmxRequest = false,
            model = model
        )

        assertEquals("redirect:/training-events", viewName)
        verify(trainingEventService).bulkDelete(ids)
    }

    // ── Bulk Edit Type Controller Tests ──────────────────────────────────────

    @Test
    fun `bulkEditTypeDialog renders editEventTypeModal with session ids and event types`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())

        val viewName = controller.bulkEditTypeDialog(ids, model)

        assertEquals("fragments/bulk-edit-dialog :: editEventTypeModal", viewName)
        assertEquals("Change Event Type", model["dialogTitle"])
        assertEquals(2, model["selectedCount"])
        assertEquals(ids, model["sessionIds"])
        assertNotNull(model["eventTypeOptions"])
    }

    @Test
    fun `bulkEditTypeDialog with count exceeding cap renders refusal modal`() {
        val model = ConcurrentModel()
        val ids = (1..55).map { UUID.randomUUID() }

        val viewName = controller.bulkEditTypeDialog(ids, model)

        assertEquals("fragments/confirm-dialog :: confirmModal", viewName)
        assertEquals("Cannot Update Sessions", model["dialogTitle"])
        val message = model["dialogMessage"] as String
        assertTrue(message.contains("Cannot update 55 sessions at once: maximum is 50."))
    }

    @Test
    fun `bulkEditType over HTMX refreshes list with filters intact`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val result = com.jankowski.rafal.dancebook.dto.BulkEditResult(updatedCount = 1, actionDescription = "Updated event type for")
        `when`(trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)).thenReturn(result)
        `when`(trainingEventService.findByCurrentUser(calendarId = defaultCal.id)).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(defaultCal)

        val viewName = controller.bulkEditType(
            sessionIds = ids,
            eventType = TrainingEventType.WORKSHOP,
            isHtmxRequest = true,
            model = model
        )

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(result.message, model["bulkMessage"])
        verify(trainingEventService).bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)
    }

    @Test
    fun `bulkEditType without HTMX redirects to list`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val result = com.jankowski.rafal.dancebook.dto.BulkEditResult(updatedCount = 1)
        `when`(trainingEventService.bulkUpdateEventType(ids, TrainingEventType.WORKSHOP)).thenReturn(result)

        val viewName = controller.bulkEditType(
            sessionIds = ids,
            eventType = TrainingEventType.WORKSHOP,
            isHtmxRequest = false,
            model = model
        )

        assertEquals("redirect:/training-events", viewName)
    }

    // ── Bulk Edit Styles Controller Tests ────────────────────────────────────

    @Test
    fun `bulkEditStylesDialog renders editStylesModal with dance categories`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val cat = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
        `when`(danceCategoryService.findAll()).thenReturn(listOf(cat))

        val viewName = controller.bulkEditStylesDialog(ids, model)

        assertEquals("fragments/bulk-edit-dialog :: editStylesModal", viewName)
        assertEquals("Replace Style Segments", model["dialogTitle"])
        assertEquals(1, model["selectedCount"])
        assertEquals(listOf(cat), model["danceCategories"])
    }

    @Test
    fun `bulkEditStyles over HTMX refreshes list with filters intact`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val segments = mutableListOf(com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest(UUID.randomUUID(), 45))
        val request = com.jankowski.rafal.dancebook.dto.BulkSegmentsRequest(ids, segments)
        val result = com.jankowski.rafal.dancebook.dto.BulkEditResult(updatedCount = 1, actionDescription = "Updated style breakdown for")
        `when`(trainingEventService.bulkUpdateSegments(ids, segments)).thenReturn(result)
        `when`(trainingEventService.findByCurrentUser(calendarId = defaultCal.id)).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(defaultCal)

        val viewName = controller.bulkEditStyles(
            request = request,
            isHtmxRequest = true,
            model = model
        )

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(result.message, model["bulkMessage"])
        verify(trainingEventService).bulkUpdateSegments(ids, segments)
    }

    // ── Bulk Edit Material Controller Tests ──────────────────────────────────

    @Test
    fun `bulkEditMaterialDialog renders editMaterialModal with materials`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val mat = com.jankowski.rafal.dancebook.model.Material().apply { id = UUID.randomUUID(); name = "Note 1" }
        `when`(materialService.findAll()).thenReturn(listOf(mat))

        val viewName = controller.bulkEditMaterialDialog(ids, model)

        assertEquals("fragments/bulk-edit-dialog :: editMaterialModal", viewName)
        assertEquals("Attach Note or Link", model["dialogTitle"])
        assertEquals(listOf(mat), model["materials"])
    }

    @Test
    fun `bulkEditMaterial over HTMX refreshes list with filters intact`() {
        val model = ConcurrentModel()
        val ids = listOf(UUID.randomUUID())
        val matId = UUID.randomUUID()
        val result = com.jankowski.rafal.dancebook.dto.BulkEditResult(updatedCount = 1, actionDescription = "Updated material for")
        `when`(trainingEventService.bulkUpdateMaterial(ids, matId, "https://link.com", false)).thenReturn(result)
        `when`(trainingEventService.findByCurrentUser(calendarId = defaultCal.id)).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(defaultCal)

        val viewName = controller.bulkEditMaterial(
            sessionIds = ids,
            materialId = matId,
            materialsUrl = "https://link.com",
            clearMaterial = false,
            isHtmxRequest = true,
            model = model
        )

        assertEquals("training-events/list :: eventsList", viewName)
        assertEquals(result.message, model["bulkMessage"])
        verify(trainingEventService).bulkUpdateMaterial(ids, matId, "https://link.com", false)
    }
}
