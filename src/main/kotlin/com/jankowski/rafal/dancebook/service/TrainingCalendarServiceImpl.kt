package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleCalendarProperties
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarSource
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CalendarSourceRepository
import com.jankowski.rafal.dancebook.repository.ShareRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarSpecification
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class TrainingCalendarServiceImpl(
    private val trainingCalendarRepository: TrainingCalendarRepository,
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingEventPersistence: TrainingEventPersistence,
    private val calendarProperties: GoogleCalendarProperties,
    private val appUserService: AppUserService,
    private val appUserRepository: AppUserRepository,
    private val calendarSourceRepository: CalendarSourceRepository,
    private val googleCalendarClient: GoogleCalendarClient
) : TrainingCalendarService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingCalendarServiceImpl::class.java)
    }

    override fun findAll(): List<TrainingCalendar> =
        trainingCalendarRepository.findAllByOrderByDisplayNameAsc()

    override fun findAllVisibleTo(user: AppUser?): List<TrainingCalendar> =
        trainingCalendarRepository.findAll(
            TrainingCalendarSpecification.visibleTo(user),
            Sort.by(Sort.Direction.ASC, "displayName")
        )

    override fun findAllEnabled(): List<TrainingCalendar> =
        trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc()

    override fun findById(id: UUID): TrainingCalendar? =
        trainingCalendarRepository.findById(id).orElse(null)

    override fun findByIdVisibleTo(id: UUID, user: AppUser?): TrainingCalendar? {
        val spec = TrainingCalendarSpecification.visibleTo(user)
            .and(TrainingCalendarSpecification.byId(id))
        return trainingCalendarRepository.findOne(spec).orElse(null)
    }

    override fun findDefault(user: AppUser?): TrainingCalendar? {
        val targetUser = user ?: appUserService.getCurrentUserOrNull()
        if (targetUser != null) {
            val def = targetUser.defaultCalendar
            if (def != null && def.enabled && isVisibleTo(def, targetUser)) {
                return def
            }
            // Fall back in-memory to first enabled calendar owned by user
            val owned = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(targetUser)
            val fallback = owned.firstOrNull { it.enabled } ?: owned.firstOrNull()
            if (fallback != null) {
                return fallback
            }
            // If user has no owned calendars, check visible calendars (e.g. public)
            val visible = findAllVisibleTo(targetUser)
            return visible.firstOrNull { it.enabled } ?: visible.firstOrNull()
        }
        return trainingCalendarRepository.findAllByEnabledTrueOrderByDisplayNameAsc().firstOrNull()
    }

    override fun requireDefault(user: AppUser?): TrainingCalendar =
        findDefault(user) ?: throw CalendarSyncException(
            if (user == null || user.role == Role.ADMIN) {
                "No default training calendar is configured. Add one under Admin → Training calendars before creating a session."
            } else {
                "No training calendar is configured. Create one under Training calendars before creating a session."
            }
        )

    @Transactional
    override fun add(request: TrainingCalendarRequest, enabled: Boolean): TrainingCalendar {
        val actor = appUserService.getCurrentUser()
        return add(request, actor, enabled)
    }

    @Transactional
    override fun add(request: TrainingCalendarRequest, actor: AppUser, enabled: Boolean): TrainingCalendar {
        val trimmedGoogleId = request.googleCalendarId.trim()
        if (trimmedGoogleId.isNotBlank()) {
            if (trainingCalendarRepository.findByGoogleCalendarId(trimmedGoogleId) != null) {
                throw IllegalArgumentException("A calendar with Google Calendar ID '$trimmedGoogleId' already exists.")
            }
            try {
                googleCalendarClient.verifyCalendar(trimmedGoogleId, requireWrite = true)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Cannot access Google Calendar '$trimmedGoogleId'. Please share the calendar with DanceBook's Google account with 'Make changes to events' permission.",
                    e
                )
            }
        }

        val calendar = TrainingCalendar().apply {
            this.owner = actor
            this.displayName = request.displayName.trim()
            this.visibility = request.visibility
            this.color = request.color?.trim()?.takeIf { it.isNotBlank() }
            this.enabled = enabled
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
        val saved = trainingCalendarRepository.save(calendar)

        if (trimmedGoogleId.isNotBlank()) {
            val source = CalendarSource().apply {
                this.calendar = saved
                this.googleCalendarId = trimmedGoogleId
                this.displayName = request.displayName.trim()
                this.isWriteTarget = true
                this.createdAt = LocalDateTime.now()
                this.updatedAt = LocalDateTime.now()
            }
            calendarSourceRepository.save(source)
            saved.sources.add(source)
        }

        if (actor.defaultCalendar == null && enabled) {
            actor.defaultCalendar = saved
            appUserRepository.save(actor)
        }
        return saved
    }

    override fun countSessions(id: UUID): Long =
        trainingEventRepository.countByCalendarId(id)

    @Transactional
    override fun update(id: UUID, request: TrainingCalendarRequest, enabled: Boolean?): TrainingCalendar {
        val actor = appUserService.getCurrentUser()
        return update(id, request, actor, enabled)
    }

    @Transactional
    override fun update(
        id: UUID,
        request: TrainingCalendarRequest,
        actor: AppUser,
        enabled: Boolean?
    ): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        checkOwnership(calendar, actor)

        val trimmedDisplayName = request.displayName.trim()
        if (trimmedDisplayName.isNotBlank()) {
            calendar.displayName = trimmedDisplayName
        }
        if (request.color != null) {
            calendar.color = request.color.trim().takeIf { it.isNotBlank() }
        }

        val trimmedGoogleId = request.googleCalendarId.trim()
        val currentWriteTarget = calendar.writeTarget
        if (trimmedGoogleId.isNotBlank() && trimmedGoogleId != currentWriteTarget?.googleCalendarId) {
            val sessionCount = trainingEventRepository.countByCalendarId(id)
            if (sessionCount > 0) {
                throw IllegalStateException("A calendar's Google ID may only be changed while it owns no sessions.")
            }
            val existing = trainingCalendarRepository.findByGoogleCalendarId(trimmedGoogleId)
            if (existing != null && existing.id != id) {
                throw IllegalArgumentException("A calendar with Google Calendar ID '$trimmedGoogleId' already exists.")
            }
            try {
                googleCalendarClient.verifyCalendar(trimmedGoogleId, requireWrite = true)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Cannot access Google Calendar '$trimmedGoogleId'. Please share the calendar with DanceBook's Google account with 'Make changes to events' permission.",
                    e
                )
            }
            if (currentWriteTarget != null) {
                currentWriteTarget.googleCalendarId = trimmedGoogleId
                currentWriteTarget.updatedAt = LocalDateTime.now()
                calendarSourceRepository.save(currentWriteTarget)
            } else {
                val newSource = CalendarSource().apply {
                    this.calendar = calendar
                    this.googleCalendarId = trimmedGoogleId
                    this.displayName = calendar.displayName
                    this.isWriteTarget = true
                }
                calendarSourceRepository.save(newSource)
                calendar.sources.add(newSource)
            }
        }

        if (enabled != null) {
            if (!enabled && calendar.isDefaultFor(actor)) {
                val remaining = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(actor)
                    .filter { it.id != id }
                actor.defaultCalendar = remaining.firstOrNull { it.enabled }
                appUserRepository.save(actor)
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
        checkOwnership(calendar, actor)

        val events = trainingEventRepository.findAllByCalendarId(id)
        events.forEach { event ->
            trainingEventPersistence.remove(event, actor)
        }

        // Fall back default calendar if deleted calendar was the user's default
        if (actor.defaultCalendar?.id == id) {
            val remaining = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(actor)
                .filter { it.id != id }
            actor.defaultCalendar = remaining.firstOrNull { it.enabled } ?: remaining.firstOrNull()
            appUserRepository.save(actor)
        }

        // Also check any other user who had this calendar as default
        val otherUsers = appUserRepository.findAllByDefaultCalendar(calendar)
        for (otherUser in otherUsers) {
            if (otherUser.id != actor.id) {
                val userCals = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(otherUser)
                val fallback = userCals.firstOrNull { it.enabled }
                    ?: findAllVisibleTo(otherUser).filter { it.id != id }.firstOrNull { it.enabled }
                    ?: userCals.firstOrNull()
                otherUser.defaultCalendar = fallback
                appUserRepository.save(otherUser)
            }
        }

        trainingCalendarRepository.delete(calendar)
    }

    @Transactional
    override fun setDefault(id: UUID): TrainingCalendar {
        val actor = appUserService.getCurrentUser()
        return setDefault(id, actor)
    }

    @Transactional
    override fun setDefault(id: UUID, user: AppUser): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        if (!isVisibleTo(calendar, user)) {
            throw AccessDeniedException("You do not have access to this calendar.")
        }
        if (!calendar.enabled) {
            if (calendar.owner?.id != user.id && user.role != Role.ADMIN) {
                throw IllegalStateException("Cannot set a disabled calendar as default.")
            }
            calendar.enabled = true
            calendar.updatedAt = LocalDateTime.now()
            trainingCalendarRepository.save(calendar)
        }
        user.defaultCalendar = calendar
        appUserRepository.save(user)
        return calendar
    }

    @Transactional
    override fun setEnabled(id: UUID, enabled: Boolean): TrainingCalendar {
        val actor = appUserService.getCurrentUser()
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        checkOwnership(calendar, actor)

        if (!enabled && calendar.isDefaultFor(actor)) {
            val remaining = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(actor)
                .filter { it.id != id }
            actor.defaultCalendar = remaining.firstOrNull { it.enabled }
            appUserRepository.save(actor)
        }
        calendar.enabled = enabled
        calendar.updatedAt = LocalDateTime.now()
        return trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun setVisibility(id: UUID, visibility: Visibility, actor: AppUser): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        checkOwnership(calendar, actor)
        calendar.visibility = visibility
        calendar.updatedAt = LocalDateTime.now()

        if (visibility == Visibility.PRIVATE) {
            val otherUsers = appUserRepository.findAllByDefaultCalendar(calendar)
            for (otherUser in otherUsers) {
                if (otherUser.id != actor.id && otherUser.role != Role.ADMIN) {
                    val userCals = trainingCalendarRepository.findAllByOwnerOrderByDisplayNameAsc(otherUser)
                    otherUser.defaultCalendar = userCals.firstOrNull { it.enabled }
                        ?: findAllVisibleTo(otherUser).filter { it.id != id }.firstOrNull { it.enabled }
                    appUserRepository.save(otherUser)
                }
            }
        }

        return trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun addSource(
        calendarId: UUID,
        googleCalendarId: String,
        displayName: String?,
        isWriteTarget: Boolean,
        actor: AppUser
    ): CalendarSource {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            IllegalArgumentException("Training calendar with id $calendarId not found")
        }
        checkOwnership(calendar, actor)

        val trimmed = googleCalendarId.trim()
        require(trimmed.isNotBlank()) { "Google Calendar ID cannot be blank." }

        if (calendar.sources.any { it.googleCalendarId.equals(trimmed, ignoreCase = true) }) {
            throw IllegalArgumentException("Google Calendar ID '$trimmed' is already linked to this calendar.")
        }

        val willBeWriteTarget = isWriteTarget || calendar.sources.isEmpty()
        try {
            googleCalendarClient.verifyCalendar(trimmed, requireWrite = willBeWriteTarget)
        } catch (e: Exception) {
            val permissionMsg = if (willBeWriteTarget) "with 'Make changes to events' permission" else ""
            throw IllegalArgumentException(
                "Cannot access Google Calendar '$trimmed'. Please ensure it is shared with DanceBook's Google account $permissionMsg.".trim(),
                e
            )
        }

        if (willBeWriteTarget) {
            calendarSourceRepository.clearWriteTargets(calendarId)
            calendar.sources.forEach { it.isWriteTarget = false }
        }

        val source = CalendarSource().apply {
            this.calendar = calendar
            this.googleCalendarId = trimmed
            this.displayName = displayName?.trim()?.takeIf { it.isNotBlank() }
            this.isWriteTarget = willBeWriteTarget
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
        val saved = calendarSourceRepository.save(source)
        calendar.sources.add(saved)
        calendar.updatedAt = LocalDateTime.now()
        trainingCalendarRepository.save(calendar)
        return saved
    }

    @Transactional
    override fun removeSource(calendarId: UUID, sourceId: UUID, actor: AppUser) {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            IllegalArgumentException("Training calendar with id $calendarId not found")
        }
        checkOwnership(calendar, actor)

        if (calendar.sources.size <= 1) {
            throw IllegalStateException("A calendar must have at least one source.")
        }

        val source = calendar.sources.firstOrNull { it.id == sourceId }
            ?: throw IllegalArgumentException("Source with id $sourceId not found on this calendar.")

        if (source.isWriteTarget) {
            throw IllegalStateException("Choose another write target before removing this source.")
        }

        calendar.sources.remove(source)
        calendarSourceRepository.delete(source)
        calendar.updatedAt = LocalDateTime.now()
        trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun setWriteTarget(calendarId: UUID, sourceId: UUID, actor: AppUser): CalendarSource {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            IllegalArgumentException("Training calendar with id $calendarId not found")
        }
        checkOwnership(calendar, actor)

        val source = calendar.sources.firstOrNull { it.id == sourceId }
            ?: throw IllegalArgumentException("Source with id $sourceId not found on this calendar.")

        try {
            googleCalendarClient.verifyCalendar(source.googleCalendarId, requireWrite = true)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Cannot set '${source.googleCalendarId}' as write target. Please ensure it is shared with DanceBook's Google account with 'Make changes to events' permission.",
                e
            )
        }

        calendarSourceRepository.clearWriteTargets(calendarId)
        calendar.sources.forEach { it.isWriteTarget = (it.id == sourceId) }
        source.isWriteTarget = true
        source.updatedAt = LocalDateTime.now()
        calendar.updatedAt = LocalDateTime.now()
        calendarSourceRepository.save(source)
        trainingCalendarRepository.save(calendar)
        return source
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
            val rootAdmin = try {
                appUserService.getRootAdmin()
            } catch (e: Exception) {
                null
            } ?: appUserRepository.findAll().firstOrNull()

            if (rootAdmin == null) {
                log.warn("Cannot bootstrap default calendar '{}' because no user exists to own it.", seed)
                return
            }

            val cal = TrainingCalendar().apply {
                this.owner = rootAdmin
                this.displayName = "Primary Calendar"
                this.visibility = Visibility.PUBLIC
                this.enabled = true
                this.createdAt = LocalDateTime.now()
                this.updatedAt = LocalDateTime.now()
            }
            val saved = trainingCalendarRepository.save(cal)
            val source = CalendarSource().apply {
                this.calendar = saved
                this.googleCalendarId = seed
                this.displayName = "Primary Calendar"
                this.isWriteTarget = true
                this.createdAt = LocalDateTime.now()
                this.updatedAt = LocalDateTime.now()
            }
            calendarSourceRepository.save(source)
            saved.sources.add(source)
            if (rootAdmin.defaultCalendar == null) {
                rootAdmin.defaultCalendar = saved
                appUserRepository.save(rootAdmin)
            }
            saved
        }

        val backfilled = trainingEventRepository.assignMissingCalendar(calendar)
        if (backfilled > 0) {
            log.info(
                "Backfilled {} training events with calendar '{}' ({})",
                backfilled, calendar.displayName, calendar.writeTarget?.googleCalendarId
            )
        }
    }

    private fun checkOwnership(calendar: TrainingCalendar, user: AppUser) {
        if (user.role == Role.ADMIN) return
        if (calendar.owner?.id != user.id) {
            throw AccessDeniedException("You do not have permission to modify this calendar.")
        }
    }

    private fun isVisibleTo(calendar: TrainingCalendar, user: AppUser?): Boolean {
        if (user?.role == Role.ADMIN) return true
        if (calendar.visibility == Visibility.PUBLIC) return true
        if (user != null && calendar.owner?.id == user.id) return true
        return false
    }
}
