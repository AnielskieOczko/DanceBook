package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Feeds the FullCalendar view.
 *
 * Colours are sent as hex rather than class names on purpose: Tailwind's content globs
 * cover the templates directory only, so a class name a script puts on an element is
 * never emitted into output.css and would silently render unstyled.
 */
@RestController
@RequestMapping("/api/training-events")
class TrainingCalendarApiController(
    private val trainingEventService: TrainingEventService
) {

    private companion object {
        // Noble Harmony tokens, resolved here because JS cannot reach Tailwind's palette.
        const val COLOR_PLANNED = "#1e2524"      // primary-container
        const val COLOR_ATTENDED = "#2e5d51"     // success
        const val COLOR_SKIPPED = "#ba1a1a"      // danger
        const val COLOR_CANCELLED = "#737877"    // outline
        const val COLOR_UNCONFIRMED = "#695d46"  // secondary — wants attention
        const val TEXT_COLOR = "#ffffff"
    }

    @GetMapping("/calendar")
    fun calendarFeed(
        @RequestParam start: String,
        @RequestParam end: String
    ): List<CalendarEventResponse> =
        trainingEventService.findInRange(parseFlexible(start), parseFlexible(end))
            .map { it.toCalendarEvent() }

    private fun TrainingEvent.toCalendarEvent(): CalendarEventResponse {
        val colour = when {
            isAwaitingConfirmation -> COLOR_UNCONFIRMED
            attendanceStatus == AttendanceStatus.ATTENDED -> COLOR_ATTENDED
            attendanceStatus == AttendanceStatus.SKIPPED -> COLOR_SKIPPED
            attendanceStatus == AttendanceStatus.CANCELLED -> COLOR_CANCELLED
            else -> COLOR_PLANNED
        }

        return CalendarEventResponse(
            id = id.toString(),
            title = title,
            // No offset: the app stores LocalDateTime and the calendar runs on
            // timeZone 'local', so adding one here would shift every event twice.
            start = startTime.toString(),
            end = endTime.toString(),
            url = "/training-events/$id",
            backgroundColor = colour,
            borderColor = colour,
            textColor = TEXT_COLOR,
            extendedProps = CalendarEventProps(
                attendanceStatus = attendanceStatus.name,
                awaitingConfirmation = isAwaitingConfirmation,
                eventType = eventType.name,
                styles = segments.map { "${it.danceCategory?.name} ${it.durationMinutes}min" },
                repeating = series != null
            )
        )
    }

    /**
     * FullCalendar sends its window as an ISO string that may or may not carry an offset
     * depending on the browser and view, so accept both rather than 400-ing on one of them.
     */
    private fun parseFlexible(value: String): LocalDateTime =
        try {
            OffsetDateTime.parse(value).toLocalDateTime()
        } catch (e: DateTimeParseException) {
            LocalDateTime.parse(value)
        }
}

data class CalendarEventResponse(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val url: String,
    val backgroundColor: String,
    val borderColor: String,
    val textColor: String,
    val extendedProps: CalendarEventProps
)

data class CalendarEventProps(
    val attendanceStatus: String,
    val awaitingConfirmation: Boolean,
    val eventType: String,
    val styles: List<String>,
    val repeating: Boolean
)
