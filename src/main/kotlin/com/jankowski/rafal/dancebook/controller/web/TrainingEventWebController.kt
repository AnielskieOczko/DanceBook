package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
@RequestMapping("/training-events")
class TrainingEventWebController(
    private val trainingEventService: TrainingEventService,
    private val trainingSeriesService: TrainingSeriesService,
    private val danceCategoryService: DanceCategoryService
) {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingEventWebController::class.java)
    }

    @GetMapping
    fun listTrainingEvents(
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val events = trainingEventService.findByCurrentUser(eventTypes, categoryIds, attendanceStatuses, search)
        model.addAttribute("events", events)
        // ArrayList, not emptyList(): Kotlin's EmptyList is an internal object whose
        // members SpEL cannot reflect on, so contains(...) fails at template render time.
        model.addAttribute("selectedEventTypes", ArrayList(eventTypes ?: emptyList()))
        model.addAttribute("selectedCategoryIds", ArrayList(categoryIds ?: emptyList()))
        model.addAttribute("selectedStatuses", ArrayList(attendanceStatuses ?: emptyList()))
        model.addAttribute("search", search)

        // Filter dropdown data is only needed for the full page, never for a fragment swap.
        if (isHtmxRequest != true) {
            model.addAttribute("pageTitle", "Training")
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            model.addAttribute("eventTypeOptions", TrainingEventType.entries.toTypedArray())
            model.addAttribute("attendanceStatusOptions", AttendanceStatus.entries.toTypedArray())
        }

        return if (isHtmxRequest == true) {
            "training-events/list :: eventsList"
        } else {
            "training-events/list"
        }
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("trainingEvent", TrainingEventRequest())
        populateFormOptions(model)
        return "training-events/form"
    }

    @PostMapping
    fun createTrainingEvent(
        @Valid @ModelAttribute("trainingEvent") request: TrainingEventRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            populateFormOptions(model)
            return "training-events/form"
        }

        try {
            // The Repeats control decides whether this is one session or a whole series,
            // the way Google folds recurrence into the event editor.
            if (request.isRepeating) {
                trainingSeriesService.create(request)
            } else {
                trainingEventService.create(request)
            }
        } catch (e: Exception) {
            log.error("Failed to create training event '{}'", request.title, e)
            bindingResult.rejectValue("title", "error.trainingEvent", e.message ?: "Failed to create training event")
            populateFormOptions(model)
            return "training-events/form"
        }

        return "redirect:/training-events"
    }

    @GetMapping("/{id}")
    fun showDetails(@PathVariable id: UUID, model: Model): String {
        val event = trainingEventService.findById(id)
        model.addAttribute("event", event)
        model.addAttribute("pageTitle", event.title)
        return "training-events/view"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val event = trainingEventService.findById(id)
        model.addAttribute(
            "trainingEvent",
            TrainingEventRequest(
                title = event.title,
                date = event.startTime.toLocalDate(),
                startTime = event.startTime.toLocalTime(),
                endTime = event.endTime.toLocalTime(),
                endDate = event.endTime.toLocalDate(),
                eventType = event.eventType.name,
                segments = event.segments.map {
                    TrainingEventSegmentRequest(
                        categoryId = it.danceCategory?.id,
                        durationMinutes = it.durationMinutes
                    )
                }.toMutableList(),
                description = event.description,
                materialId = event.material?.id,
                materialsUrl = event.materialsUrl,
                attendanceStatus = event.attendanceStatus.name
            )
        )
        model.addAttribute("trainingEventId", id)
        model.addAttribute("isSeriesOccurrence", event.series != null)
        populateFormOptions(model)
        return "training-events/form"
    }

    @PostMapping("/{id}")
    fun updateTrainingEvent(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("trainingEvent") request: TrainingEventRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("trainingEventId", id)
            populateFormOptions(model)
            return "training-events/form"
        }

        try {
            // "This and following" regenerates the rest of the series; "this event"
            // detaches the occurrence and updates it alone.
            if (request.editScope.equals("THIS_AND_FOLLOWING", ignoreCase = true)) {
                trainingSeriesService.updateThisAndFollowing(id, request)
            } else {
                trainingEventService.update(id, request)
            }
        } catch (e: Exception) {
            log.error("Failed to update training event {}", id, e)
            bindingResult.rejectValue("title", "error.trainingEvent", e.message ?: "Failed to update training event")
            model.addAttribute("trainingEventId", id)
            populateFormOptions(model)
            return "training-events/form"
        }

        return "redirect:/training-events"
    }

    /**
     * One-tap attendance confirmation. From the agenda list this arrives over HTMX and
     * swaps the list fragment back; from the detail page it is a plain form post, so it
     * redirects rather than rendering a bare fragment.
     */
    @PostMapping("/{id}/attendance")
    fun updateAttendance(
        @PathVariable id: UUID,
        @RequestParam status: AttendanceStatus,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        trainingEventService.updateAttendance(id, status)

        if (isHtmxRequest != true) {
            return "redirect:/training-events/$id"
        }

        model.addAttribute("events", trainingEventService.findByCurrentUser())
        return "training-events/list :: eventsList"
    }

    @PostMapping("/{id}/delete")
    fun deleteTrainingEvent(
        @PathVariable id: UUID,
        @RequestParam(required = false) scope: String? = null
    ): String {
        if (scope.equals("THIS_AND_FOLLOWING", ignoreCase = true)) {
            trainingSeriesService.deleteThisAndFollowing(id)
        } else {
            trainingEventService.delete(id)
        }
        return "redirect:/training-events"
    }

    private fun populateFormOptions(model: Model) {
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        model.addAttribute("eventTypeOptions", TrainingEventType.entries.toTypedArray())
        model.addAttribute("attendanceStatusOptions", AttendanceStatus.entries.toTypedArray())
    }
}
