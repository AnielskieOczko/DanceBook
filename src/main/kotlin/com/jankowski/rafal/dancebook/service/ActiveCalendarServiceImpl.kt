package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ActiveCalendarServiceImpl(
    private val session: HttpSession,
    private val trainingCalendarService: TrainingCalendarService,
    private val trainingEventRepository: TrainingEventRepository
) : ActiveCalendarService {

    companion object {
        const val SESSION_KEY = "activeCalendarId"
        const val ALL = "ALL"
    }

    override fun active(): TrainingCalendar? {
        val stored = session.getAttribute(SESSION_KEY) as? String
            ?: return trainingCalendarService.findDefault()
        if (stored == ALL) return null
        // A calendar deleted while selected falls back rather than leaving a dead context.
        return trainingCalendarService.findById(UUID.fromString(stored))
            ?: trainingCalendarService.findDefault()
    }

    override fun setActive(calendarId: UUID?) {
        session.setAttribute(SESSION_KEY, calendarId?.toString() ?: ALL)
    }

    override fun selectable(): List<TrainingCalendar> {
        val inUse = trainingEventRepository.calendarIdsInUse().toSet()
        return trainingCalendarService.findAll().filter { it.enabled || it.id in inUse }
    }

    override fun creationTarget(): TrainingCalendar {
        val active = active() ?: return trainingCalendarService.requireDefault()
        if (!active.enabled) {
            // Deliberately not a silent fall back to the default: the session was asked for in
            // the calendar on screen, and filing it elsewhere without saying so is worse.
            throw CalendarSyncException(
                "${active.displayName} is disabled — choose another calendar to create a session."
            )
        }
        return active
    }
}
