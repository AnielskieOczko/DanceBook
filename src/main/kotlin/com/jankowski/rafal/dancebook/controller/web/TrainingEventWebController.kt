package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.FormSelectOption
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.dto.groupByMonth
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.SeriesScope
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.CalendarSyncException
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import jakarta.persistence.EntityNotFoundException
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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import com.jankowski.rafal.dancebook.dto.BulkSegmentsRequest
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.MaterialService
import java.time.DayOfWeek
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
    private val danceCategoryService: DanceCategoryService,
    private val activeCalendarService: ActiveCalendarService,
    private val trainingCalendarService: TrainingCalendarService,
    private val calendarSyncService: CalendarSyncService,
    private val materialService: MaterialService,
    private val appUserService: AppUserService
) {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingEventWebController::class.java)

        /** Evening default for a day clicked in month view; most training is after work. */
        private const val DEFAULT_HOUR = 18
    }

    @PostMapping("/sync")
    fun syncNow(
        @RequestHeader(value = "HX-Request", required = false) isHtmx: Boolean? = null
    ): ResponseEntity<Any> {
        val report = calendarSyncService.syncAll()
        if (report.hasFailures) {
            val error = report.failureMessages.joinToString("; ")
            log.error("Sync now completed with failures: {}", error)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to error))
        }
        return ResponseEntity.noContent().header("HX-Refresh", "true").build()
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
            eventTypes, categoryIds, attendanceStatuses, search, awaitingConfirmation,
            activeCalendarService.active()?.id
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

        val targetCalendar = runCatching { activeCalendarService.creationTarget() }.getOrNull()
        model.addAttribute("targetCalendar", targetCalendar)
        model.addAttribute(
            "trainingEvent",
            TrainingEventRequest(
                date = slotStart.toLocalDate(),
                startTime = slotStart.toLocalTime(),
                endTime = slotEnd.toLocalTime(),
                calendarId = targetCalendar?.id
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
        populateFormOptions(model)
        val targetCalendar = try {
            activeCalendarService.creationTarget()
        } catch (e: CalendarSyncException) {
            model.addAttribute("calendarError", e.message)
            null
        }
        model.addAttribute("targetCalendar", targetCalendar)
        model.addAttribute("trainingEvent", TrainingEventRequest(calendarId = targetCalendar?.id))
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
            model.addAttribute("targetCalendar", runCatching { activeCalendarService.creationTarget() }.getOrNull())
            return "training-events/form"
        }

        try {
            val targetCalendar = activeCalendarService.validateCreationTarget(request.calendarId)
            val scopedRequest = request.copy(calendarId = targetCalendar.id)
            if (scopedRequest.isRepeating) {
                trainingSeriesService.create(scopedRequest)
            } else {
                trainingEventService.create(scopedRequest)
            }
        } catch (e: EntityNotFoundException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to create training event '{}'", request.title, e)
            bindingResult.rejectValue("title", "error.trainingEvent", e.message ?: "Failed to create training event")
            populateFormOptions(model)
            model.addAttribute("targetCalendar", runCatching { activeCalendarService.creationTarget() }.getOrNull())
            return "training-events/form"
        }

        return "redirect:/training-events"
    }

    @GetMapping("/{id}")
    fun showDetails(@PathVariable id: UUID, model: Model): String {
        val event = trainingEventService.findById(id)
        model.addAttribute("event", event)
        model.addAttribute("currentUser", appUserService.getCurrentUser())
        model.addAttribute("pageTitle", event.title)
        return "training-events/view"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val event = trainingEventService.findById(id)
        val series = event.series
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
                attendanceStatus = event.attendanceFor(currentUser).name,
                repeat = if (series != null) "WEEKLY" else "NONE",
                // The series keeps its existing horizon when an edit is applied to every
                // occurrence. Without it the regeneration has no end date to work to and
                // rejects the save with "A repeat end date is required".
                repeatUntil = series?.endsOn,
                dayOfWeek = series?.dayOfWeek ?: event.startTime.dayOfWeek
            )
        )
        model.addAttribute("trainingEventId", id)
        model.addAttribute("isSeriesOccurrence", series != null)
        model.addAttribute("seriesDayOfWeek", series?.dayOfWeek)
        model.addAttribute("seriesEndsOn", series?.endsOn)
        model.addAttribute("seriesStartTime", series?.startTime)
        model.addAttribute("seriesEndTime", series?.endTime)
        model.addAttribute("targetCalendar", event.calendar ?: trainingCalendarService.findDefault())
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
            when (request.editScope) {
                SeriesScope.THIS_AND_FOLLOWING -> trainingSeriesService.updateThisAndFollowing(id, request)
                SeriesScope.ALL_EVENTS -> trainingSeriesService.updateAll(id, request)
                SeriesScope.THIS_EVENT -> {
                    val event = trainingEventService.findById(id)
                    if (event.series != null) {
                        trainingSeriesService.updateThisEvent(id, request)
                    } else {
                        trainingEventService.update(id, request)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to update training event {}", id, e)
            bindingResult.rejectValue("title", "error.trainingEvent", e.message ?: "Failed to update training event")
            return redisplayEditForm(model, id)
        }

        return "redirect:/training-events"
    }

    /**
     * Renders the confirm dialog for an edit, stating created, moved, and removed counts,
     * and warning when past sessions with recorded attendance would be dropped.
     */
    @PostMapping("/{id}/confirm-dialog")
    fun editConfirmDialog(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("trainingEvent") request: TrainingEventRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val errorMsg = bindingResult.allErrors.firstOrNull()?.defaultMessage ?: "Please fill in all required fields."
            model.addAttribute("dialogTitle", "Cannot Update Series")
            model.addAttribute("dialogMessage", errorMsg)
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        val event = trainingEventService.findById(id)
        val series = event.series

        if (series == null) {
            model.addAttribute("dialogTitle", "Edit Session")
            model.addAttribute("dialogMessage", "Apply changes to this session?")
            model.addAttribute("confirmLabel", "Save")
            model.addAttribute("confirmUrl", "/training-events/$id")
            return "fragments/confirm-dialog :: confirmModal"
        }

        try {
            val plan = trainingSeriesService.calculatePatternReconcile(id, request)
            model.addAttribute("dialogTitle", "Edit Recurring Series")
            model.addAttribute(
                "dialogMessage",
                "Updating this repeating series will recompute session dates:"
            )
            model.addAttribute("reconcilePlan", plan)
            model.addAttribute("confirmLabel", "Apply Changes")
            model.addAttribute("confirmUrl", "/training-events/$id")
            model.addAttribute("confirmFormId", "trainingEventForm")
            return "fragments/confirm-dialog :: confirmModal"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("dialogTitle", "Cannot Update Series")
            model.addAttribute("dialogMessage", e.message ?: "Invalid series pattern")
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }
    }

    /**
     * Re-renders the edit form after a failed save.
     *
     * `isSeriesOccurrence` has to be restored here, not just on the initial GET: without it the
     * "apply changes to" selector disappears from the redisplayed form, so the user's retry
     * posts no edit scope at all and silently updates one occurrence instead of the series.
     */
    private fun redisplayEditForm(model: Model, id: UUID): String {
        val event = trainingEventService.findById(id)
        val series = event.series
        model.addAttribute("trainingEventId", id)
        model.addAttribute("isSeriesOccurrence", series != null)
        model.addAttribute("seriesDayOfWeek", series?.dayOfWeek)
        model.addAttribute("seriesEndsOn", series?.endsOn)
        model.addAttribute("seriesStartTime", series?.startTime)
        model.addAttribute("seriesEndTime", series?.endTime)
        model.addAttribute("targetCalendar", event.calendar ?: trainingCalendarService.findDefault())
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

        // This swaps the whole #events-list fragment the user is looking at, so it has to
        // honour the active calendar — otherwise confirming attendance on a scoped agenda
        // silently reverts it to every calendar.
        populateEventsList(
            model,
            trainingEventService.findByCurrentUser(calendarId = activeCalendarService.active()?.id)
        )
        return "training-events/list :: eventsList"
    }

    /**
     * Bulk attendance update across a selection of sessions. Swaps the agenda list back
     * with active filters preserved, leaving future sessions untouched and displaying
     * feedback on how many were updated and skipped.
     */
    @PostMapping("/bulk-attendance")
    fun bulkUpdateAttendance(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        @RequestParam status: AttendanceStatus,
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val result = trainingEventService.bulkUpdateAttendance(sessionIds ?: emptyList(), status)

        if (isHtmxRequest != true) {
            return "redirect:/training-events"
        }

        val events = trainingEventService.findByCurrentUser(
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = search,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = activeCalendarService.active()?.id
        )
        populateEventsList(model, events)
        model.addAttribute("bulkMessage", result.message)

        return "training-events/list :: eventsList"
    }

    /**
     * Renders the shared confirmation dialog for a bulk delete selection, stating how many
     * sessions will be deleted and that they are removed from Google Calendar too.
     */
    @PostMapping("/bulk-delete-dialog")
    fun bulkDeleteDialog(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        model: Model
    ): String {
        val ids = sessionIds ?: emptyList()
        val count = ids.size

        if (count > TrainingEventService.MAX_BULK_ACTION) {
            model.addAttribute("dialogTitle", "Cannot Delete Sessions")
            model.addAttribute(
                "dialogMessage",
                TrainingEventService.bulkCapRefusal(count, "delete")
            )
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        if (count == 0) {
            model.addAttribute("dialogTitle", "No Sessions Selected")
            model.addAttribute("dialogMessage", "Please select at least one session to delete.")
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        val message = when (count) {
            1 -> "1 training session will be deleted and removed from Google Calendar. Any recorded training history will be preserved as orphaned records."
            else -> "$count training sessions will be deleted and removed from Google Calendar. Any recorded training history will be preserved as orphaned records."
        }

        model.addAttribute("dialogTitle", if (count == 1) "Delete Session" else "Delete Sessions")
        model.addAttribute("dialogMessage", message)
        model.addAttribute("selectedCount", count)
        model.addAttribute("confirmLabel", if (count == 1) "Delete Session" else "Delete $count Sessions")
        model.addAttribute("confirmUrl", "/training-events/bulk-delete")
        model.addAttribute("hxTarget", "#events-list")
        model.addAttribute("hxSwap", "outerHTML")
        model.addAttribute("hxInclude", "#filterForm")
        model.addAttribute("sessionIds", ids)
        return "fragments/confirm-dialog :: confirmModal"
    }

    /**
     * Bulk deletion across a selection of sessions. Removes sessions from Google Calendar
     * and local database, and swaps the agenda list back with active filters preserved,
     * displaying feedback on how many were deleted.
     */
    @PostMapping("/bulk-delete")
    fun bulkDelete(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val result = trainingEventService.bulkDelete(sessionIds ?: emptyList())

        if (isHtmxRequest != true) {
            return "redirect:/training-events"
        }

        val events = trainingEventService.findByCurrentUser(
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = search,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = activeCalendarService.active()?.id
        )
        populateEventsList(model, events)
        model.addAttribute("bulkMessage", result.message)

        return "training-events/list :: eventsList"
    }

    /**
     * Renders the modal dialog for changing event type across a selection of sessions.
     */
    @PostMapping("/bulk-edit-type-dialog")
    fun bulkEditTypeDialog(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        model: Model
    ): String {
        val ids = sessionIds ?: emptyList()
        val count = ids.size

        if (count > TrainingEventService.MAX_BULK_ACTION) {
            model.addAttribute("dialogTitle", "Cannot Update Sessions")
            model.addAttribute(
                "dialogMessage",
                TrainingEventService.bulkCapRefusal(count, "update")
            )
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        if (count == 0) {
            model.addAttribute("dialogTitle", "No Sessions Selected")
            model.addAttribute("dialogMessage", "Please select at least one session to update.")
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        model.addAttribute("dialogTitle", "Change Event Type")
        model.addAttribute("selectedCount", count)
        model.addAttribute("sessionIds", ids)
        model.addAttribute("eventTypeOptions", TrainingEventType.entries.toTypedArray())
        return "fragments/bulk-edit-dialog :: editEventTypeModal"
    }

    /**
     * Bulk event type update across a selection of sessions.
     */
    @PostMapping("/bulk-edit-type")
    fun bulkEditType(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        @RequestParam eventType: TrainingEventType,
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val result = trainingEventService.bulkUpdateEventType(sessionIds ?: emptyList(), eventType)

        if (isHtmxRequest != true) {
            return "redirect:/training-events"
        }

        val events = trainingEventService.findByCurrentUser(
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = search,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = activeCalendarService.active()?.id
        )
        populateEventsList(model, events)
        model.addAttribute("bulkMessage", result.message)

        return "training-events/list :: eventsList"
    }

    /**
     * Renders the modal dialog for setting style segments across a selection of sessions.
     */
    @PostMapping("/bulk-edit-styles-dialog")
    fun bulkEditStylesDialog(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        model: Model
    ): String {
        val ids = sessionIds ?: emptyList()
        val count = ids.size

        if (count > TrainingEventService.MAX_BULK_ACTION) {
            model.addAttribute("dialogTitle", "Cannot Update Sessions")
            model.addAttribute(
                "dialogMessage",
                TrainingEventService.bulkCapRefusal(count, "update")
            )
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        if (count == 0) {
            model.addAttribute("dialogTitle", "No Sessions Selected")
            model.addAttribute("dialogMessage", "Please select at least one session to update.")
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        model.addAttribute("dialogTitle", "Replace Style Segments")
        model.addAttribute("selectedCount", count)
        model.addAttribute("sessionIds", ids)
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        return "fragments/bulk-edit-dialog :: editStylesModal"
    }

    /**
     * Bulk style segments update across a selection of sessions.
     */
    @PostMapping("/bulk-edit-styles")
    fun bulkEditStyles(
        @ModelAttribute request: BulkSegmentsRequest,
        @RequestParam(required = false) sessionIds: List<UUID>? = null,
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val ids = if (request.sessionIds.isNotEmpty()) request.sessionIds else (sessionIds ?: emptyList())
        val result = trainingEventService.bulkUpdateSegments(ids, request.segments)

        if (isHtmxRequest != true) {
            return "redirect:/training-events"
        }

        val events = trainingEventService.findByCurrentUser(
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = search,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = activeCalendarService.active()?.id
        )
        populateEventsList(model, events)
        model.addAttribute("bulkMessage", result.message)

        return "training-events/list :: eventsList"
    }

    /**
     * Renders the modal dialog for setting material (Note / external link) across a selection of sessions.
     */
    @PostMapping("/bulk-edit-material-dialog")
    fun bulkEditMaterialDialog(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        model: Model
    ): String {
        val ids = sessionIds ?: emptyList()
        val count = ids.size

        if (count > TrainingEventService.MAX_BULK_ACTION) {
            model.addAttribute("dialogTitle", "Cannot Update Sessions")
            model.addAttribute(
                "dialogMessage",
                TrainingEventService.bulkCapRefusal(count, "update")
            )
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        if (count == 0) {
            model.addAttribute("dialogTitle", "No Sessions Selected")
            model.addAttribute("dialogMessage", "Please select at least one session to update.")
            model.addAttribute("cancelLabel", "Close")
            model.addAttribute("confirmUrl", null)
            return "fragments/confirm-dialog :: confirmModal"
        }

        model.addAttribute("dialogTitle", "Attach Note or Link")
        model.addAttribute("selectedCount", count)
        model.addAttribute("sessionIds", ids)
        model.addAttribute("materials", materialService.findAll())
        return "fragments/bulk-edit-dialog :: editMaterialModal"
    }

    /**
     * Bulk material update across a selection of sessions.
     */
    @PostMapping("/bulk-edit-material")
    fun bulkEditMaterial(
        @RequestParam(required = false) sessionIds: List<UUID>?,
        @RequestParam(required = false) materialId: UUID? = null,
        @RequestParam(required = false) materialsUrl: String? = null,
        @RequestParam(required = false, defaultValue = "false") clearMaterial: Boolean = false,
        @RequestParam(required = false) eventTypes: List<TrainingEventType>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) attendanceStatuses: List<AttendanceStatus>? = null,
        @RequestParam(required = false) search: String? = null,
        @RequestParam(required = false) awaitingConfirmation: Boolean? = null,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = sessionIds ?: emptyList(),
            materialId = materialId,
            materialsUrl = materialsUrl,
            clearMaterial = clearMaterial
        )

        if (isHtmxRequest != true) {
            return "redirect:/training-events"
        }

        val events = trainingEventService.findByCurrentUser(
            eventTypes = eventTypes,
            categoryIds = categoryIds,
            attendanceStatuses = attendanceStatuses,
            titleSearch = search,
            awaitingConfirmation = awaitingConfirmation,
            calendarId = activeCalendarService.active()?.id
        )
        populateEventsList(model, events)
        model.addAttribute("bulkMessage", result.message)

        return "training-events/list :: eventsList"
    }

    @GetMapping("/{id}/delete-dialog")
    fun deleteDialog(
        @PathVariable id: UUID,
        model: Model
    ): String {
        val event = trainingEventService.findById(id)
        val series = event.series
        if (series == null) {
            model.addAttribute("dialogTitle", "Delete Session")
            model.addAttribute(
                "dialogMessage",
                "Delete this training session? It will also be removed from your Google Calendar."
            )
            model.addAttribute("confirmLabel", "Delete Session")
            model.addAttribute("confirmUrl", "/training-events/$id/delete")
            return "fragments/confirm-dialog :: confirmModal"
        }

        val scopeOptions = trainingSeriesService.calculateDeleteScopeOptions(id)
        model.addAttribute("dialogTitle", "Delete Recurring Session")
        model.addAttribute(
            "dialogMessage",
            "This session is part of a repeating series. How far should this delete reach?"
        )
        model.addAttribute("scopeOptions", scopeOptions)
        model.addAttribute("confirmLabel", "Delete")
        model.addAttribute("confirmUrl", "/training-events/$id/delete")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/{id}/delete")
    fun deleteTrainingEvent(
        @PathVariable id: UUID,
        @RequestParam(required = false) scope: SeriesScope? = null,
        redirectAttributes: RedirectAttributes = RedirectAttributesModelMap()
    ): String {
        val result = when (scope ?: SeriesScope.THIS_EVENT) {
            SeriesScope.THIS_EVENT -> {
                trainingEventService.delete(id)
                null
            }
            SeriesScope.THIS_AND_FOLLOWING -> trainingSeriesService.deleteThisAndFollowing(id)
            SeriesScope.ALL_EVENTS -> trainingSeriesService.deleteAll(id)
        }
        if (result != null && result.failedCount > 0) {
            redirectAttributes.addFlashAttribute("bulkMessage", result.message)
        }
        return "redirect:/training-events"
    }

    /**
     * Everything the `eventsList` fragment needs. Every path that renders it -- the full page,
     * the HTMX filter swap and the attendance swap -- goes through here; a path that only set
     * `events` would render the fragment with no month headings at all.
     */
    private fun populateEventsList(model: Model, events: List<TrainingEvent>) {
        val currentUser = appUserService.getCurrentUser()
        model.addAttribute("events", events)
        model.addAttribute("monthGroups", groupByMonth(events, currentUser))
        model.addAttribute("currentUser", currentUser)
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
        model.addAttribute(
            "dayOfWeekOptions",
            DayOfWeek.entries.map {
                FormSelectOption(
                    value = it.name,
                    label = it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                )
            }
        )
        model.addAttribute(
            "repeatOptions",
            listOf(
                FormSelectOption("NONE", "Does not repeat"),
                FormSelectOption("WEEKLY", "Weekly on this weekday")
            )
        )
    }
}
