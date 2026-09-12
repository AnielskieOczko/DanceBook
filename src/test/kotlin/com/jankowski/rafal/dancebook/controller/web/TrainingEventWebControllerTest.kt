package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingMonthGroup
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
    private lateinit var controller: TrainingEventWebController

    @BeforeEach
    fun setUp() {
        trainingEventService = mock(TrainingEventService::class.java)
        trainingSeriesService = mock(TrainingSeriesService::class.java)
        danceCategoryService = mock(DanceCategoryService::class.java)
        controller = TrainingEventWebController(trainingEventService, trainingSeriesService, danceCategoryService)
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
        assertEquals(1, request.segments.size)
        assertEquals(category.id, request.segments[0].categoryId)
        assertEquals(90, request.segments[0].durationMinutes)
        assertEquals("ATTENDED", request.attendanceStatus)
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
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")
        `when`(trainingEventService.create(request)).thenThrow(RuntimeException("Google Calendar unavailable"))
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
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")

        val viewName = controller.createTrainingEvent(request, bindingResult, model)

        assertEquals("redirect:/training-events", viewName)
        verify(trainingSeriesService).create(request)
        verify(trainingEventService, never()).create(request)
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
        val bindingResult = BeanPropertyBindingResult(request, "trainingEvent")

        controller.createTrainingEvent(request, bindingResult, model)

        verify(trainingEventService).create(request)
        verify(trainingSeriesService, never()).create(request)
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
}
