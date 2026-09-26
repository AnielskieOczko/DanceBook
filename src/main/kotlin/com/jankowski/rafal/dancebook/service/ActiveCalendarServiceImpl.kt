package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.ShareRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ActiveCalendarServiceImpl(
    private val session: HttpSession,
    private val trainingCalendarService: TrainingCalendarService,
    private val trainingEventRepository: TrainingEventRepository,
    private val appUserService: AppUserService
) : ActiveCalendarService {

    companion object {
        const val SESSION_KEY = "activeCalendarId"
        const val ALL = "ALL"
    }

    private fun resolveCurrentUser(): AppUser? {
        return try {
            appUserService.getCurrentUser()
        } catch (e: Exception) {
            appUserService.getCurrentUserOrNull()
        }
    }

    override fun active(): TrainingCalendar? {
        val currentUser = resolveCurrentUser()
        val stored = session.getAttribute(SESSION_KEY) as? String
            ?: return trainingCalendarService.findDefault(currentUser)
        if (stored == ALL) return null
        val calendar = trainingCalendarService.findById(UUID.fromString(stored))
        if (calendar != null && (currentUser == null || isVisibleTo(calendar, currentUser))) {
            return calendar
        }
        return trainingCalendarService.findDefault(currentUser)
    }

    override fun setActive(calendarId: UUID?) {
        session.setAttribute(SESSION_KEY, calendarId?.toString() ?: ALL)
    }

    override fun selectable(): List<TrainingCalendar> {
        val currentUser = resolveCurrentUser()
        val visibleCalendars = trainingCalendarService.findAllVisibleTo(currentUser)
        val inUse = trainingEventRepository.calendarIdsInUse().toSet()
        return visibleCalendars.filter { it.enabled || it.id in inUse }
    }

    override fun creationTarget(): TrainingCalendar {
        val currentUser = resolveCurrentUser()
        val active = active() ?: return trainingCalendarService.requireDefault(currentUser)
        if (!active.enabled) {
            throw CalendarSyncException(
                "${active.displayName} is disabled — choose another calendar to create a session."
            )
        }
        return active
    }

    override fun validateCreationTarget(calendarId: UUID?): TrainingCalendar {
        val currentUser = resolveCurrentUser()
        val active = active()
        if (active != null) {
            if (!active.enabled) {
                throw CalendarSyncException(
                    "${active.displayName} is disabled — choose another calendar to create a session."
                )
            }
            if (calendarId != null) {
                val target = trainingCalendarService.findById(calendarId)
                    ?: throw IllegalArgumentException("Training calendar with id $calendarId not found")
                if (!target.enabled) {
                    throw CalendarSyncException(
                        "${target.displayName} is disabled — choose another calendar to create a session."
                    )
                }
                if (target.id != active.id) {
                    throw IllegalArgumentException(
                        "Cannot create session in '${target.displayName}': active calendar is '${active.displayName}'."
                    )
                }
                return target
            }
            return active
        }

        // Under "All calendars":
        if (calendarId == null) {
            val def = trainingCalendarService.findDefault(currentUser)
            if (def != null && def.enabled) return def
            throw CalendarSyncException(
                "No target calendar specified — choose a calendar to create a session."
            )
        }
        val target = trainingCalendarService.findById(calendarId)
            ?: throw IllegalArgumentException("Training calendar with id $calendarId not found")
        if (!target.enabled) {
            throw CalendarSyncException(
                "${target.displayName} is disabled — choose another calendar to create a session."
            )
        }
        return target
    }

    private fun isVisibleTo(calendar: TrainingCalendar, user: AppUser): Boolean {
        if (user.role == Role.ADMIN) return true
        if (calendar.visibility == Visibility.PUBLIC) return true
        if (calendar.owner?.id == user.id) return true
        return false
    }
}
