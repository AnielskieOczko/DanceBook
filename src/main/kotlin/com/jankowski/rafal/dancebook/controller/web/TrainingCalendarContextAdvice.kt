package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.AppUserService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import java.time.LocalDateTime

/**
 * Supplies the active-calendar context to the training pages.
 *
 * Deliberately not part of NavbarAdvice, which feeds every page in the app: calendars are
 * irrelevant to the syllabus, materials and choreography pages, and loading them there would
 * be a wasted query on every request.
 */
@ControllerAdvice(
    assignableTypes = [
        TrainingEventWebController::class,
        TrainingStatsWebController::class,
        TrainingTimelineWebController::class,
        TrainingHistoryWebController::class
    ]
)
class TrainingCalendarContextAdvice(
    private val activeCalendarService: ActiveCalendarService,
    private val appUserService: AppUserService
) {

    @ModelAttribute("activeCalendar")
    fun activeCalendar(): TrainingCalendar? = activeCalendarService.active()

    @ModelAttribute("selectableCalendars")
    fun selectableCalendars(): List<TrainingCalendar> = activeCalendarService.selectable()

    /**
     * Whether "Add Session" leads anywhere: only a calendar's owner adds sessions to it, so a
     * subscriber whose active (or default) calendar is someone else's gets no button that
     * would end in a 403.
     */
    @ModelAttribute("canAddSession")
    fun canAddSession(): Boolean {
        val user = runCatching { appUserService.getCurrentUser() }.getOrNull() ?: return false
        fun writable(calendar: TrainingCalendar) = calendar.enabled &&
            (user.role == Role.ADMIN || calendar.owner == null || calendar.owner?.id == user.id)
        val active = activeCalendarService.active()
        return if (active != null) writable(active) else activeCalendarService.selectable().any(::writable)
    }

    @ModelAttribute("lastSyncedAt")
    fun lastSyncedAt(): LocalDateTime? {
        val active = activeCalendarService.active()
        return if (active != null) {
            active.lastSyncedAt
        } else {
            activeCalendarService.selectable()
                .filter { it.enabled }
                .mapNotNull { it.lastSyncedAt }
                .maxOrNull()
        }
    }
}
