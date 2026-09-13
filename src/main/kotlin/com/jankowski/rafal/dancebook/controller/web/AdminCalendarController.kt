package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
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
    private val trainingCalendarService: TrainingCalendarService
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
            try {
                trainingCalendarService.add(request)
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
