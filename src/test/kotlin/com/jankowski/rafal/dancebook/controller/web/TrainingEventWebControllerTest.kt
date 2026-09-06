package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
