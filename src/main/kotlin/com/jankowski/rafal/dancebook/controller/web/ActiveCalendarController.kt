package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * Sets the calendar scoping the training views.
 *
 * Answers with HX-Refresh rather than a fragment: one shared selector is rendered on five
 * views that share no shape — the agenda's #events-list, the FullCalendar grid and three
 * others — so teaching it each one's target and fragment name would be far more machinery
 * than a page reload on an action taken a few times a day.
 */
@Controller
@RequestMapping("/training-events/active-calendar")
class ActiveCalendarController(
    private val activeCalendarService: ActiveCalendarService
) {

    @PostMapping
    fun setActiveCalendar(@RequestParam calendarId: String): ResponseEntity<Void> {
        // The select only ever posts "ALL" or a real id, but this is a plain endpoint any
        // authenticated client can hit, so bad input is a 400 rather than a 500.
        val selected = if (calendarId == "ALL") {
            null
        } else {
            runCatching { UUID.fromString(calendarId) }.getOrElse {
                return ResponseEntity.badRequest().build()
            }
        }
        activeCalendarService.setActive(selected)
        return ResponseEntity.noContent().header("HX-Refresh", "true").build()
    }
}
