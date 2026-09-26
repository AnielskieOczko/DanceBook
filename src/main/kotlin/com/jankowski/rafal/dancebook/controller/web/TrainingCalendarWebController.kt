package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarMemberService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

@Controller
@RequestMapping("/training-calendars")
class TrainingCalendarWebController(
    private val trainingCalendarService: TrainingCalendarService,
    private val googleCalendarClient: GoogleCalendarClient,
    private val appUserService: AppUserService,
    private val calendarMemberService: CalendarMemberService
) {

    @GetMapping
    fun list(model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendars = trainingCalendarService.findAllVisibleTo(currentUser)
        val pendingInvites = calendarMemberService.getPendingInvitesFor(currentUser)
        val publicCalendars = trainingCalendarService.findAllPublic().filter { it.owner?.id != currentUser.id }
        // A HashSet, not toSet(): Kotlin's EmptySet is internal, so SpEL cannot call contains() on it.
        val subscribedCalendarIds = calendars.filter { it.owner?.id != currentUser.id && calendarMemberService.isMember(it.id!!, currentUser) }.mapNotNull { it.id }.toHashSet()
        model.addAttribute("calendars", calendars)
        model.addAttribute("currentUser", currentUser)
        model.addAttribute("pendingInvites", pendingInvites)
        model.addAttribute("publicCalendars", publicCalendars)
        model.addAttribute("subscribedCalendarIds", subscribedCalendarIds)
        return "training-calendars/index"
    }

    @GetMapping("/new")
    fun newCalendar(model: Model): String {
        if (!model.containsAttribute("trainingCalendar")) {
            model.addAttribute("trainingCalendar", TrainingCalendarRequest())
        }
        model.addAttribute("isNew", true)
        return "training-calendars/form"
    }

    @PostMapping
    fun create(
        @Valid @ModelAttribute("trainingCalendar") request: TrainingCalendarRequest,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        if (bindingResult.hasErrors()) {
            model.addAttribute("isNew", true)
            return "training-calendars/form"
        }

        val trimmedId = request.googleCalendarId.trim()
        try {
            googleCalendarClient.verifyCalendar(trimmedId, requireWrite = true)
        } catch (e: Exception) {
            bindingResult.rejectValue("googleCalendarId", "invalid", e.message ?: "Could not verify Google Calendar. Ensure it is shared with DanceBook with permission to make changes.")
            model.addAttribute("isNew", true)
            return "training-calendars/form"
        }

        try {
            trainingCalendarService.add(request, currentUser, enabled = true)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Calendar \"${request.displayName}\" connected successfully.")
            return "redirect:/training-calendars"
        } catch (e: Exception) {
            bindingResult.rejectValue("googleCalendarId", "invalid", e.message ?: "Failed to add calendar")
            model.addAttribute("isNew", true)
            return "training-calendars/form"
        }
    }

    @GetMapping("/{id}/edit")
    fun editCalendar(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        if (!model.containsAttribute("trainingCalendar")) {
            model.addAttribute(
                "trainingCalendar",
                TrainingCalendarRequest(
                    googleCalendarId = calendar.writeTarget?.googleCalendarId ?: calendar.sources.firstOrNull()?.googleCalendarId ?: "",
                    displayName = calendar.displayName,
                    visibility = calendar.visibility,
                    color = calendar.color
                )
            )
        }
        model.addAttribute("calendar", calendar)
        model.addAttribute("members", calendarMemberService.findMembers(id, currentUser))
        model.addAttribute("isNew", false)
        return "training-calendars/form"
    }

    @PostMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("trainingCalendar") request: TrainingCalendarRequest,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("calendar", calendar)
            model.addAttribute("members", calendarMemberService.findMembers(id, currentUser))
            model.addAttribute("isNew", false)
            return "training-calendars/form"
        }

        try {
            trainingCalendarService.update(id, request, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Calendar updated successfully.")
            return "redirect:/training-calendars"
        } catch (e: Exception) {
            bindingResult.reject("error", e.message ?: "Failed to update calendar")
            model.addAttribute("calendar", calendar)
            model.addAttribute("members", calendarMemberService.findMembers(id, currentUser))
            model.addAttribute("isNew", false)
            return "training-calendars/form"
        }
    }

    @PostMapping("/{id}/default")
    fun setDefault(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        trainingCalendarService.setDefault(id, currentUser)
        redirectAttributes.addFlashAttribute("calendarSuccess", "\"${calendar.displayName}\" is now your default calendar.")
        return "redirect:/training-calendars"
    }

    @GetMapping("/{id}/publish-dialog")
    fun showPublishDialog(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        model.addAttribute("dialogTitle", "Publish Calendar")
        model.addAttribute(
            "dialogMessage",
            "Every DanceBook user will see all events from these Google calendars. Are you sure you want to make \"${calendar.displayName}\" public?"
        )
        model.addAttribute("confirmLabel", "Publish")
        model.addAttribute("confirmUrl", "/training-calendars/$id/publish")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }
        trainingCalendarService.setVisibility(id, Visibility.PUBLIC, currentUser)
        redirectAttributes.addFlashAttribute("calendarSuccess", "Calendar \"${calendar.displayName}\" is now public.")
        return "redirect:/training-calendars"
    }

    @PostMapping("/{id}/unpublish")
    fun unpublish(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }
        trainingCalendarService.setVisibility(id, Visibility.PRIVATE, currentUser)
        redirectAttributes.addFlashAttribute("calendarSuccess", "Calendar \"${calendar.displayName}\" is now private.")
        return "redirect:/training-calendars"
    }

    @GetMapping("/{id}/unpublish-dialog")
    fun showUnpublishDialog(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        model.addAttribute("dialogTitle", "Make Calendar Private")
        model.addAttribute(
            "dialogMessage",
            "This will remove \"${calendar.displayName}\" from the public directory. Existing members and subscribers will keep their access. Are you sure you want to make it private?"
        )
        model.addAttribute("confirmLabel", "Make Private")
        model.addAttribute("confirmUrl", "/training-calendars/$id/unpublish")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/{id}/subscribe")
    fun subscribe(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        try {
            calendarMemberService.subscribe(id, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Subscribed to \"${calendar.displayName}\".")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to subscribe")
        }
        return "redirect:/training-calendars"
    }

    @PostMapping("/{id}/unsubscribe")
    fun unsubscribe(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findById(id)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        try {
            calendarMemberService.unsubscribe(id, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Unsubscribed from \"${calendar.displayName}\".")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to unsubscribe")
        }
        return "redirect:/training-calendars"
    }

    @PostMapping("/invites/{memberId}/accept")
    fun acceptInvite(@PathVariable memberId: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        try {
            val member = calendarMemberService.acceptInvite(memberId, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Accepted invitation to \"${member.calendar?.displayName}\".")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to accept invite")
        }
        return "redirect:/training-calendars"
    }

    @PostMapping("/invites/{memberId}/decline")
    fun declineInvite(@PathVariable memberId: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        try {
            calendarMemberService.declineInvite(memberId, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Declined invitation.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to decline invite")
        }
        return "redirect:/training-calendars"
    }

    @PostMapping("/{id}/members/invite")
    fun inviteMember(
        @PathVariable id: UUID,
        @RequestParam identifier: String,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }
        try {
            val member = calendarMemberService.inviteMember(id, identifier.trim(), currentUser)
            val name = member.user?.displayName ?: member.invitedEmail ?: identifier
            redirectAttributes.addFlashAttribute("calendarSuccess", "Invited $name.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to invite member")
        }
        return "redirect:/training-calendars/$id/edit"
    }

    @PostMapping("/{id}/members/{memberId}/delete")
    fun removeMember(
        @PathVariable id: UUID,
        @PathVariable memberId: UUID,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }
        try {
            calendarMemberService.removeMember(id, memberId, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Member removed.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to remove member")
        }
        return "redirect:/training-calendars/$id/edit"
    }

    @PostMapping("/{id}/sources")
    fun addSource(
        @PathVariable id: UUID,
        @RequestParam googleCalendarId: String,
        @RequestParam(required = false) displayName: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        val trimmed = googleCalendarId.trim()
        try {
            googleCalendarClient.verifyCalendar(trimmed, requireWrite = false)
            trainingCalendarService.addSource(id, trimmed, displayName, isWriteTarget = false, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Secondary Google calendar added.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to add source")
        }
        return "redirect:/training-calendars/$id/edit"
    }

    @PostMapping("/{id}/sources/{sourceId}/delete")
    fun removeSource(
        @PathVariable id: UUID,
        @PathVariable sourceId: UUID,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        try {
            trainingCalendarService.removeSource(id, sourceId, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Source removed.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to remove source")
        }
        return "redirect:/training-calendars/$id/edit"
    }

    @PostMapping("/{id}/sources/{sourceId}/write-target")
    fun setWriteTarget(
        @PathVariable id: UUID,
        @PathVariable sourceId: UUID,
        redirectAttributes: RedirectAttributes
    ): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        val source = calendar.sources.firstOrNull { it.id == sourceId }
            ?: throw EntityNotFoundException("Source not found")

        try {
            googleCalendarClient.verifyCalendar(source.googleCalendarId, requireWrite = true)
            trainingCalendarService.setWriteTarget(id, sourceId, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Write target updated to \"${source.displayName ?: source.googleCalendarId}\".")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Cannot set as write target. Ensure DanceBook has write permission in Google Calendar.")
        }
        return "redirect:/training-calendars/$id/edit"
    }

    @GetMapping("/{id}/delete-dialog")
    fun showDeleteDialog(@PathVariable id: UUID, model: Model): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
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
        model.addAttribute("confirmUrl", "/training-calendars/$id/delete")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        val currentUser = appUserService.getCurrentUser()
        val calendar = trainingCalendarService.findByIdVisibleTo(id, currentUser)
            ?: throw EntityNotFoundException("Training calendar with id $id not found")
        if (currentUser.role != Role.ADMIN && calendar.owner?.id != currentUser.id) {
            throw EntityNotFoundException("Training calendar with id $id not found")
        }

        try {
            trainingCalendarService.delete(id, currentUser)
            redirectAttributes.addFlashAttribute("calendarSuccess", "Calendar deleted.")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("calendarError", e.message ?: "Failed to delete calendar")
        }
        return "redirect:/training-calendars"
    }
}
