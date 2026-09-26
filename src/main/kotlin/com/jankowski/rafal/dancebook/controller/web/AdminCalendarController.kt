package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import jakarta.servlet.http.HttpServletResponse
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
    private val googleCalendarClient: GoogleCalendarClient,
    private val appUserService: AppUserService
) {

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("currentUser", appUserService.getCurrentUser())
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

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val calendar = trainingCalendarService.findById(id)
        if (calendar == null) {
            model.addAttribute("calendarError", "Training calendar with id $id not found")
            model.addAttribute("calendars", trainingCalendarService.findAll())
            return "admin/dashboard :: calendarsSection"
        }
        val sessionCount = trainingCalendarService.countSessions(id)
        model.addAttribute("calendar", calendar)
        model.addAttribute("sessionCount", sessionCount)
        return "admin/dashboard :: editCalendarRow"
    }

    @GetMapping("/{id}/cancel")
    fun cancelEdit(@PathVariable id: UUID, model: Model): String {
        val calendar = trainingCalendarService.findById(id)
        val currentUser = appUserService.getCurrentUser()
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("cal", calendar)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarRow"
    }

    @PostMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid request: TrainingCalendarRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        val existing = trainingCalendarService.findById(id)
        if (existing == null) {
            model.addAttribute("calendarError", "Training calendar with id $id not found")
            model.addAttribute("calendars", trainingCalendarService.findAll())
            return "admin/dashboard :: calendarsSection"
        }

        if (bindingResult.hasErrors()) {
            val error = bindingResult.allErrors.firstOrNull()?.defaultMessage ?: "Invalid calendar data"
            model.addAttribute("calendarError", error)
            model.addAttribute("calendars", trainingCalendarService.findAll())
            return "admin/dashboard :: calendarsSection"
        }

        val trimmedGoogleId = request.googleCalendarId.trim()
        val googleIdChanged = trimmedGoogleId != existing.writeTarget?.googleCalendarId
        val currentUser = appUserService.getCurrentUser()

        if (googleIdChanged) {
            val sessionCount = trainingCalendarService.countSessions(id)
            if (sessionCount > 0) {
                model.addAttribute("calendarError", "A calendar's Google ID may only be changed while it owns no sessions.")
                model.addAttribute("currentUser", currentUser)
                model.addAttribute("calendars", trainingCalendarService.findAll())
                return "admin/dashboard :: calendarsSection"
            }

            var verifiedSummary: String? = null
            var verificationError: String? = null
            try {
                verifiedSummary = googleCalendarClient.verifyCalendar(trimmedGoogleId)
            } catch (e: Exception) {
                verificationError = e.message ?: "Could not reach Google Calendar"
            }

            val enabled = verificationError == null
            val wasDefault = existing.isDefaultFor(currentUser)
            try {
                val updated = trainingCalendarService.update(id, request, enabled = enabled)
                if (enabled) {
                    model.addAttribute("calendarSuccess", "Connected — \"$verifiedSummary\"")
                } else if (wasDefault && !updated.isDefaultFor(currentUser)) {
                    model.addAttribute("calendarError", "$verificationError. This calendar has been disabled and is no longer the default.")
                } else {
                    model.addAttribute("calendarError", verificationError)
                }
            } catch (e: IllegalArgumentException) {
                model.addAttribute("calendarError", e.message)
            } catch (e: IllegalStateException) {
                val message = if (verificationError != null) "$verificationError. ${e.message}" else e.message
                model.addAttribute("calendarError", message)
            }
        } else {
            try {
                trainingCalendarService.update(id, request)
                model.addAttribute("calendarSuccess", "Calendar updated")
            } catch (e: IllegalArgumentException) {
                model.addAttribute("calendarError", e.message)
            } catch (e: IllegalStateException) {
                model.addAttribute("calendarError", e.message)
            }
        }

        model.addAttribute("currentUser", currentUser)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @GetMapping("/{id}/delete-dialog")
    fun showDeleteDialog(
        @PathVariable id: UUID,
        response: HttpServletResponse? = null,
        model: Model
    ): String {
        val calendar = trainingCalendarService.findById(id)
        if (calendar == null) {
            response?.setHeader("HX-Retarget", "#calendarsSection")
            model.addAttribute("calendarError", "Training calendar with id $id not found")
            model.addAttribute("calendars", trainingCalendarService.findAll())
            return "admin/dashboard :: calendarsSection"
        }

        val sessionCount = trainingCalendarService.countSessions(id)
        val message = if (sessionCount > 0) {
            "This will remove $sessionCount training session${if (sessionCount == 1L) "" else "s"} from DanceBook. Their training history and statistics will be preserved as orphaned records. Events in Google Calendar will not be touched."
        } else {
            "Are you sure you want to delete calendar \"${calendar.displayName}\"? Events in Google Calendar will not be touched."
        }

        model.addAttribute("dialogTitle", "Delete Training Calendar")
        model.addAttribute("dialogMessage", message)
        model.addAttribute("confirmLabel", "Delete Calendar")
        model.addAttribute("confirmUrl", "/admin/calendars/$id/delete")
        model.addAttribute("hxTarget", "#calendarsSection")
        model.addAttribute("hxSwap", "outerHTML")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: UUID, model: Model): String {
        val user = appUserService.getCurrentUser()
        try {
            trainingCalendarService.delete(id, user)
            model.addAttribute("calendarSuccess", "Calendar deleted")
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("currentUser", user)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping
    fun add(
        @Valid request: TrainingCalendarRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        val currentUser = appUserService.getCurrentUser()
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
                trainingCalendarService.add(request, actor = currentUser, enabled = enabled)
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
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/verify")
    fun verify(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findById(id)
        if (calendar == null) {
            model.addAttribute("calendarError", "Training calendar with id $id not found")
        } else {
            try {
                val targetGoogleId = calendar.writeTarget?.googleCalendarId
                    ?: calendar.sources.firstOrNull()?.googleCalendarId
                    ?: throw IllegalStateException("Calendar has no sources")
                val summary = googleCalendarClient.verifyCalendar(targetGoogleId)
                model.addAttribute("calendarSuccess", "Connected — \"$summary\"")
            } catch (e: Exception) {
                model.addAttribute("calendarError", e.message ?: "Verification failed")
            }
        }
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/default")
    fun makeDefault(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        try {
            trainingCalendarService.setDefault(id, currentUser)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }

    @PostMapping("/{id}/enabled")
    fun setEnabled(
        @PathVariable id: UUID,
        @RequestParam(required = false) enabled: Boolean?,
        model: Model
    ): String {
        val currentUser = appUserService.getCurrentUser()
        try {
            val existing = trainingCalendarService.findById(id)
            val wasDefault = existing?.isDefaultFor(currentUser) == true
            val targetEnabled = enabled ?: !(existing?.enabled ?: false)
            val updated = trainingCalendarService.setEnabled(id, targetEnabled)
            if (wasDefault && !updated.isDefaultFor(currentUser)) {
                model.addAttribute("calendarError", "This calendar has been disabled and is no longer the default.")
            }
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }
}

