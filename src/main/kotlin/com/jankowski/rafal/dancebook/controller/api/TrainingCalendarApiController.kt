package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
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
 * Colours are sent as hex rather than class names because FullCalendar owns the chip element
 * and the palette has to reach it as inline style. A chip is drawn as a tinted fill behind a
 * solid leading stripe, so the feed sends both: [CalendarEventResponse.backgroundColor] is the
 * tint, [CalendarEventResponse.borderColor] the solid colour the renderer uses for the stripe.
 * All five values live in [TrainingEventPalette], shared with the legend on the page.
 */
@RestController
@RequestMapping("/api/training-events")
class TrainingCalendarApiController(
    private val trainingEventService: TrainingEventService
) {

    @GetMapping("/calendar")
    fun calendarFeed(
        @RequestParam start: String,
        @RequestParam end: String
    ): List<CalendarEventResponse> =
        trainingEventService.findInRange(parseFlexible(start), parseFlexible(end))
            .map { it.toCalendarEvent() }

    private fun TrainingEvent.toCalendarEvent(): CalendarEventResponse {
        val swatch = TrainingEventPalette.swatchFor(this)

        return CalendarEventResponse(
            id = id.toString(),
            title = title,
            // No offset: the app stores LocalDateTime and the calendar runs on
            // timeZone 'local', so adding one here would shift every event twice.
            start = startTime.toString(),
            end = endTime.toString(),
            url = "/training-events/$id",
            backgroundColor = swatch.tint,
            borderColor = swatch.color,
            textColor = TrainingEventPalette.TEXT_COLOR,
            extendedProps = CalendarEventProps(
                status = swatch.key,
                statusLabel = swatch.label,
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
    /** Palette key the chip renderer styles on: planned, unconfirmed, attended, skipped, cancelled. */
    val status: String,
    val statusLabel: String,
    val attendanceStatus: String,
    val awaitingConfirmation: Boolean,
    val eventType: String,
    val styles: List<String>,
    val repeating: Boolean
)
