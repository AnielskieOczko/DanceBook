package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.dto.groupByMonth
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
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
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
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

        /** Evening default for a day clicked in month view; most training is after work. */
        private const val DEFAULT_HOUR = 18
    }

    @GetMapping
    fun listTrainingEvents(
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val events = trainingEventService.findByCurrentUser(
            eventTypes, categoryIds, attendanceStatuses, search, awaitingConfirmation
        )
        populateEventsList(model, events)
        // ArrayList, not emptyList(): Kotlin's EmptyList is an internal object whose
        // members SpEL cannot reflect on, so contains(...) fails at template render time.
        model.addAttribute("selectedEventTypes", ArrayList(eventTypes ?: emptyList()))
        model.addAttribute("selectedCategoryIds", ArrayList(categoryIds ?: emptyList()))
        model.addAttribute("selectedStatuses", ArrayList(attendanceStatuses ?: emptyList()))
        model.addAttribute("search", search)
        model.addAttribute("selectedAwaitingConfirmation", awaitingConfirmation)

        // Filter dropdown data is only needed for the full page, never for a fragment swap.
        if (isHtmxRequest != true) {
            model.addAttribute("pageTitle", "Training")
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            model.addAttribute("eventTypeOptions", TrainingEventType.entries.toTypedArray())
            model.addAttribute("attendanceStatusOptions", AttendanceStatus.entries.toTypedArray())
            model.addAttribute(
                "activeFilterCount",
                activeFilterCount(eventTypes, categoryIds, attendanceStatuses, awaitingConfirmation = awaitingConfirmation)
            )
        }

        return if (isHtmxRequest == true) {
            "training-events/list :: eventsList"
        } else {
            "training-events/list"
        }
    }

    @GetMapping("/calendar")
    fun showCalendar(model: Model): String {
        model.addAttribute("pageTitle", "Training calendar")
        // The legend and the JSON feed read the same palette, so the two can never drift.
        model.addAttribute("statusLegend", TrainingEventPalette.LEGEND)
        return "training-events/calendar"
    }

    /**
     * The quick-create card shown when a slot is clicked or dragged on the calendar,
     * mirroring Google's in-place create rather than sending the user to a full page.
     */
    @GetMapping("/quick-create")
    fun quickCreate(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime,
        model: Model
    ): String {
        // Clicking a day in month view selects the whole day, which would prefill
        // midnight-to-midnight. Offer a plausible evening slot instead, the way Google
        // does when you click a date rather than a time.
        val allDaySelection = start.toLocalTime() == LocalTime.MIDNIGHT &&
            Duration.between(start, end).toHours() >= 24
        val slotStart = if (allDaySelection) start.toLocalDate().atTime(DEFAULT_HOUR, 0) else start
        val slotEnd = if (allDaySelection) slotStart.plusHours(1) else end

        model.addAttribute(
            "trainingEvent",
            TrainingEventRequest(
                date = slotStart.toLocalDate(),
                startTime = slotStart.toLocalTime(),
                endTime = slotEnd.toLocalTime()
            )
        )
        model.addAttribute("quickCreateWeekday", start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
        return "training-events/calendar :: quickCreateCard"
    }

    /** Drag or resize on the calendar; writes through to Google like any other move. */
    @PostMapping("/{id}/reschedule")
    @ResponseBody
    fun reschedule(
        @PathVariable id: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime
    ): ResponseEntity<Map<String, String>> =
        try {
            trainingEventService.reschedule(id, start, end)
            ResponseEntity.ok(mapOf("status" to "ok"))
        } catch (e: Exception) {
            // The calendar reverts the drag on a non-2xx, so the message has to come back
            // in the body for the page to explain why.
            log.error("Failed to reschedule training event {}", id, e)
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Could not move this session")))
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
                attendanceStatus = event.attendanceStatus.name,
                // The series keeps its existing horizon when an edit is applied to every
                // occurrence. Without it the regeneration has no end date to work to and
                // rejects the save with "A repeat end date is required".
                repeatUntil = event.series?.endsOn
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
            return redisplayEditForm(model, id)
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
            return redisplayEditForm(model, id)
        }

        return "redirect:/training-events"
    }

    /**
     * Re-renders the edit form after a failed save.
     *
     * `isSeriesOccurrence` has to be restored here, not just on the initial GET: without it the
     * "apply changes to" selector disappears from the redisplayed form, so the user's retry
     * posts no edit scope at all and silently updates one occurrence instead of the series.
     */
    private fun redisplayEditForm(model: Model, id: UUID): String {
        model.addAttribute("trainingEventId", id)
        model.addAttribute("isSeriesOccurrence", trainingEventService.findById(id).series != null)
        populateFormOptions(model)
        return "training-events/form"
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

        populateEventsList(model, trainingEventService.findByCurrentUser())
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

    /**
     * Everything the `eventsList` fragment needs. Every path that renders it -- the full page,
     * the HTMX filter swap and the attendance swap -- goes through here; a path that only set
     * `events` would render the fragment with no month headings at all.
     */
    private fun populateEventsList(model: Model, events: List<TrainingEvent>) {
        model.addAttribute("events", events)
        model.addAttribute("monthGroups", groupByMonth(events))
    }

    /** Drives the "Filters" badge on the collapsed mobile filter panel. */
    private fun activeFilterCount(vararg selections: Collection<*>?, awaitingConfirmation: Boolean? = null): Int =
        selections.count { !it.isNullOrEmpty() } + (if (awaitingConfirmation == true) 1 else 0)

    /**
     * A training log is read in months: how many sessions, how many hours. Grouping happens
     * here rather than in the template because Thymeleaf can only compare a row against its
     * predecessor, which makes the totals awkward and the markup worse.
     */
    private fun populateFormOptions(model: Model) {
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        model.addAttribute("eventTypeOptions", TrainingEventType.entries.toTypedArray())
        model.addAttribute("attendanceStatusOptions", AttendanceStatus.entries.toTypedArray())
    }
}
