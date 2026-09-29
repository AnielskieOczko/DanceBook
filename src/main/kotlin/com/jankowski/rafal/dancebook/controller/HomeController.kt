package com.jankowski.rafal.dancebook.controller

import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.service.DashboardService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Controller
class HomeController(
    private val dashboardService: DashboardService,
    private val trainingEventService: TrainingEventService
) {

    companion object {
        private val log = LoggerFactory.getLogger(HomeController::class.java)

        /** The two answers the hero offers; cancelling a session is a calendar decision. */
        private val WRAP_UP_STATUSES = setOf(AttendanceStatus.ATTENDED, AttendanceStatus.SKIPPED)
    }

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("dashboard", dashboardService.dashboardForCurrentUser())
        return "index"
    }

    /**
     * Confirms or skips a session from the hero, through the same service call the training
     * views use, so the `TrainingRecord` write and the activity event are unchanged.
     *
     * Over HTMX the wrap-up section swaps in place and the week strip and month card follow it
     * out of band, since both change with the answer. A failed update re-renders the section
     * with the error and leaves the session unconfirmed.
     */
    @PostMapping("/home/sessions/{id}/attendance")
    fun updateAttendance(
        @PathVariable id: UUID,
        @RequestParam status: AttendanceStatus,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model
    ): String {
        if (status !in WRAP_UP_STATUSES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only attended or skipped can be set here")
        }

        var error: String? = null
        try {
            trainingEventService.updateAttendance(id, status)
        } catch (e: Exception) {
            log.error("Failed to set attendance {} for session {} from the home page", status, id, e)
            error = e.message ?: "Could not save your answer. Please try again."
        }

        if (isHtmxRequest != true) {
            return "redirect:/"
        }

        model.addAttribute("dashboard", dashboardService.dashboardForCurrentUser())
        model.addAttribute("wrapUpError", error)
        // Read by the week and month fragments to mark themselves as out-of-band swaps.
        model.addAttribute("oob", true)
        return "home/wrap-up-response :: wrapUpResponse"
    }

    @GetMapping("/ping")
    @org.springframework.web.bind.annotation.ResponseBody
    fun ping(): String {
        return "<span class=\"text-success font-bold\">HTMX is working!</span>"
    }
}
