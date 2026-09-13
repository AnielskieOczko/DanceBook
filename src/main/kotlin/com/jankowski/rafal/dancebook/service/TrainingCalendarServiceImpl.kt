package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class TrainingCalendarServiceImpl(
    private val trainingCalendarRepository: TrainingCalendarRepository,
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingEventPersistence: TrainingEventPersistence,
    private val calendarProperties: GoogleCalendarProperties
) : TrainingCalendarService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingCalendarServiceImpl::class.java)
    }

    override fun findAll(): List<TrainingCalendar> =
        trainingCalendarRepository.findAllByOrderByDisplayNameAsc()

    override fun findAllEnabled(): List<TrainingCalendar> =
        trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()

    override fun findById(id: UUID): TrainingCalendar? =
        trainingCalendarRepository.findById(id).orElse(null)

    override fun findDefault(): TrainingCalendar? =
        trainingCalendarRepository.findByIsDefaultTrue()

    override fun requireDefault(): TrainingCalendar =
        findDefault() ?: throw CalendarSyncException(
            "No default training calendar is configured. Add one under Admin → Training calendars before creating a session."
        )

    @Transactional
    override fun add(request: TrainingCalendarRequest, enabled: Boolean): TrainingCalendar {
        val trimmedGoogleId = request.googleCalendarId.trim()
        if (trainingCalendarRepository.findByGoogleCalendarId(trimmedGoogleId) != null) {
            throw IllegalArgumentException("A calendar with Google Calendar ID '$trimmedGoogleId' already exists.")
        }
        val isFirst = trainingCalendarRepository.count() == 0L
        val calendar = TrainingCalendar().apply {
            googleCalendarId = trimmedGoogleId
            displayName = request.displayName.trim()
            isDefault = isFirst && enabled
            this.enabled = enabled
            createdAt = LocalDateTime.now()
            updatedAt = LocalDateTime.now()
        }
        return trainingCalendarRepository.save(calendar)
    }

    override fun countSessions(id: UUID): Long =
        trainingEventRepository.countByCalendarId(id)

    @Transactional
    override fun update(id: UUID, request: TrainingCalendarRequest, enabled: Boolean?): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        val trimmedDisplayName = request.displayName.trim()
        if (trimmedDisplayName.isNotBlank()) {
            calendar.displayName = trimmedDisplayName
        }

        val trimmedGoogleId = request.googleCalendarId.trim()
        if (trimmedGoogleId.isNotBlank() && trimmedGoogleId != calendar.googleCalendarId) {
            val sessionCount = trainingEventRepository.countByCalendarId(id)
            if (sessionCount > 0) {
                throw IllegalStateException("A calendar's Google ID may only be changed while it owns no sessions.")
            }
            val existing = trainingCalendarRepository.findByGoogleCalendarId(trimmedGoogleId)
            if (existing != null && existing.id != id) {
                throw IllegalArgumentException("A calendar with Google Calendar ID '$trimmedGoogleId' already exists.")
            }
            calendar.googleCalendarId = trimmedGoogleId
        }

        if (enabled != null) {
            if (!enabled && calendar.isDefault) {
                val totalCalendars = trainingCalendarRepository.count()
                if (totalCalendars > 1) {
                    throw IllegalStateException("Make another calendar the default before disabling this one.")
                }
                calendar.isDefault = false
            }
            calendar.enabled = enabled
        }

        calendar.updatedAt = LocalDateTime.now()
        return trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun delete(id: UUID, actor: AppUser) {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        val totalCalendars = trainingCalendarRepository.count()
        if (calendar.isDefault && totalCalendars > 1) {
            throw IllegalStateException("Make another calendar the default before deleting this one.")
        }

        val events = trainingEventRepository.findAllByCalendarId(id)
        events.forEach { event ->
            trainingEventPersistence.remove(event, actor)
        }
        trainingCalendarRepository.delete(calendar)
    }

    @Transactional
    override fun setDefault(id: UUID): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        trainingCalendarRepository.clearDefaultExcept(id)
        trainingCalendarRepository.markDefault(id)
        calendar.isDefault = true
        calendar.enabled = true
        return calendar
    }

    @Transactional
    override fun setEnabled(id: UUID, enabled: Boolean): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        if (!enabled && calendar.isDefault) {
            val totalCalendars = trainingCalendarRepository.count()
            if (totalCalendars > 1) {
                throw IllegalStateException("Make another calendar the default before disabling this one.")
            }
            calendar.isDefault = false
        }
        calendar.enabled = enabled
        calendar.updatedAt = LocalDateTime.now()
        return trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun bootstrapDefaultCalendar() {
        val seed = calendarProperties.calendarId.trim()
        if (seed.isBlank()) {
            log.warn(
                "google.calendar.calendar-id is blank. Configure a default calendar under " +
                "Admin → Training calendars or set GOOGLE_CALENDAR_ID before creating sessions."
            )
            return
        }

        val existing = trainingCalendarRepository.findByGoogleCalendarId(seed)
        val calendar = if (existing != null) {
            existing
        } else {
            val hasDefault = trainingCalendarRepository.findByIsDefaultTrue() != null
            trainingCalendarRepository.save(TrainingCalendar().apply {
                googleCalendarId = seed
                displayName = "Primary Calendar"
                isDefault = !hasDefault
                enabled = true
                createdAt = LocalDateTime.now()
                updatedAt = LocalDateTime.now()
            })
        }

        val backfilled = trainingEventRepository.assignMissingCalendar(calendar)
        if (backfilled > 0) {
            log.info(
                "Backfilled {} training events with calendar '{}' ({})",
                backfilled, calendar.displayName, calendar.googleCalendarId
            )
        }
    }
}
