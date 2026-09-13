package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
@RequestMapping("/admin/calendars")
@PreAuthorize("hasRole('ADMIN')")
class AdminCalendarController(
    private val trainingCalendarService: TrainingCalendarService,
    private val googleCalendarClient: GoogleCalendarClient
) {

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @GetMapping("/form")
    fun showAddForm(model: Model): String {
        return "admin/dashboard :: addCalendarForm"
    }

    @GetMapping("/form/cancel")
    fun cancelAddForm(): String {
        return "admin/dashboard :: empty"
    }

    @PostMapping
    fun add(
        @Valid request: TrainingCalendarRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val error = bindingResult.allErrors.firstOrNull()?.defaultMessage ?: "Invalid calendar data"
            model.addAttribute("calendarError", error)
            model.addAttribute("showAddForm", true)
        } else {
            val trimmedId = request.googleCalendarId.trim()
            var verifiedSummary: String? = null
            var verificationError: String? = null
            try {
                verifiedSummary = googleCalendarClient.verifyCalendar(trimmedId)
            } catch (e: Exception) {
                verificationError = e.message ?: "Could not reach Google Calendar"
            }

            val enabled = verificationError == null
            try {
                trainingCalendarService.add(request, enabled = enabled)
                if (enabled) {
                    model.addAttribute("calendarSuccess", "Connected — \"$verifiedSummary\"")
                } else {
                    model.addAttribute("calendarError", verificationError)
                }
            } catch (e: IllegalArgumentException) {
                model.addAttribute("calendarError", e.message)
                model.addAttribute("showAddForm", true)
            } catch (e: IllegalStateException) {
                model.addAttribute("calendarError", e.message)
                model.addAttribute("showAddForm", true)
            }
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/verify")
    fun verify(@PathVariable id: UUID, model: Model): String {
        val calendar = trainingCalendarService.findById(id)
        if (calendar == null) {
            model.addAttribute("calendarError", "Training calendar with id $id not found")
        } else {
            try {
                val summary = googleCalendarClient.verifyCalendar(calendar.googleCalendarId)
                model.addAttribute("calendarSuccess", "Connected — \"$summary\"")
            } catch (e: Exception) {
                model.addAttribute("calendarError", e.message ?: "Verification failed")
            }
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/default")
    fun makeDefault(@PathVariable id: UUID, model: Model): String {
        try {
            trainingCalendarService.setDefault(id)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/enabled")
    fun setEnabled(
        @PathVariable id: UUID,
        @RequestParam(required = false) enabled: Boolean?,
        model: Model
    ): String {
        try {
            val targetEnabled = enabled ?: !(trainingCalendarService.findById(id)?.enabled ?: false)
            trainingCalendarService.setEnabled(id, targetEnabled)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }
}
