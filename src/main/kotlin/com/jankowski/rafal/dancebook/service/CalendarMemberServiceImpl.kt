package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CalendarMember
import com.jankowski.rafal.dancebook.model.CalendarMemberRole
import com.jankowski.rafal.dancebook.model.CalendarMemberState
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CalendarMemberRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarSpecification
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class CalendarMemberServiceImpl(
    private val calendarMemberRepository: CalendarMemberRepository,
    private val trainingCalendarRepository: TrainingCalendarRepository,
    private val appUserRepository: AppUserRepository
) : CalendarMemberService {

    companion object {
        private val log = LoggerFactory.getLogger(CalendarMemberServiceImpl::class.java)
    }

    override fun findMembers(calendarId: UUID, actor: AppUser): List<CalendarMember> {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            EntityNotFoundException("Training calendar with id $calendarId not found")
        }
        checkAccess(calendar, actor)
        return calendarMemberRepository.findAllByCalendarOrderByCreatedAtAsc(calendar)
    }

    @Transactional
    override fun inviteMember(calendarId: UUID, identifier: String, actor: AppUser): CalendarMember {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            EntityNotFoundException("Training calendar with id $calendarId not found")
        }
        checkOwnership(calendar, actor)

        val trimmed = identifier.trim()
        require(trimmed.isNotBlank()) { "Username or email cannot be blank." }

        val isEmail = trimmed.contains("@")
        return if (isEmail) {
            val existingUser = appUserRepository.findByEmailIgnoreCase(trimmed)
            if (existingUser != null) {
                if (existingUser.id == actor.id || existingUser.id == calendar.owner?.id) {
                    throw IllegalArgumentException("You cannot invite yourself.")
                }
                if (calendarMemberRepository.existsByCalendarIdAndUserId(calendarId, existingUser.id!!)) {
                    throw IllegalArgumentException("User is already a member or has an invitation.")
                }
                val member = CalendarMember().apply {
                    this.calendar = calendar
                    this.user = existingUser
                    this.invitedEmail = trimmed.lowercase()
                    this.role = CalendarMemberRole.VIEWER
                    this.state = CalendarMemberState.INVITED
                    this.createdAt = LocalDateTime.now()
                    this.updatedAt = LocalDateTime.now()
                }
                calendarMemberRepository.save(member)
            } else {
                if (calendarMemberRepository.existsByCalendarIdAndInvitedEmailIgnoreCaseAndState(
                        calendarId, trimmed, CalendarMemberState.INVITED
                    )) {
                    throw IllegalArgumentException("An invitation has already been sent to this email.")
                }
                val member = CalendarMember().apply {
                    this.calendar = calendar
                    this.user = null
                    this.invitedEmail = trimmed.lowercase()
                    this.role = CalendarMemberRole.VIEWER
                    this.state = CalendarMemberState.INVITED
                    this.createdAt = LocalDateTime.now()
                    this.updatedAt = LocalDateTime.now()
                }
                calendarMemberRepository.save(member)
            }
        } else {
            val user = appUserRepository.findByUsername(trimmed)
                ?: throw IllegalArgumentException("User '$trimmed' not found.")
            if (user.id == actor.id || user.id == calendar.owner?.id) {
                throw IllegalArgumentException("You cannot invite yourself.")
            }
            if (calendarMemberRepository.existsByCalendarIdAndUserId(calendarId, user.id!!)) {
                throw IllegalArgumentException("User is already a member or has an invitation.")
            }
            val member = CalendarMember().apply {
                this.calendar = calendar
                this.user = user
                this.invitedEmail = user.email?.lowercase()
                this.role = CalendarMemberRole.VIEWER
                this.state = CalendarMemberState.INVITED
                this.createdAt = LocalDateTime.now()
                this.updatedAt = LocalDateTime.now()
            }
            calendarMemberRepository.save(member)
        }
    }

    @Transactional
    override fun removeMember(calendarId: UUID, memberId: UUID, actor: AppUser) {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            EntityNotFoundException("Training calendar with id $calendarId not found")
        }
        checkOwnership(calendar, actor)

        val member = calendarMemberRepository.findById(memberId).orElseThrow {
            EntityNotFoundException("Calendar member with id $memberId not found")
        }
        if (member.calendar?.id != calendarId) {
            throw IllegalArgumentException("Member does not belong to this calendar.")
        }

        val targetUser = member.user
        calendarMemberRepository.delete(member)

        if (targetUser != null && targetUser.defaultCalendar?.id == calendarId) {
            fallBackDefaultCalendar(targetUser, calendarId)
        }
    }

    @Transactional
    override fun getPendingInvitesFor(user: AppUser): List<CalendarMember> {
        claimInvitesFor(user)
        return calendarMemberRepository.findAllByUserAndStateOrderByCreatedAtDesc(user, CalendarMemberState.INVITED)
    }

    @Transactional
    override fun countPendingInvitesFor(user: AppUser): Long {
        return getPendingInvitesFor(user).size.toLong()
    }

    @Transactional
    override fun acceptInvite(memberId: UUID, user: AppUser): CalendarMember {
        val member = calendarMemberRepository.findById(memberId).orElseThrow {
            EntityNotFoundException("Invitation not found")
        }
        if (member.user?.id != user.id || member.state != CalendarMemberState.INVITED) {
            throw AccessDeniedException("You do not have permission to accept this invitation.")
        }

        member.state = CalendarMemberState.ACTIVE
        member.updatedAt = LocalDateTime.now()
        val saved = calendarMemberRepository.save(member)

        if (user.defaultCalendar == null && member.calendar?.enabled == true) {
            user.defaultCalendar = member.calendar
            appUserRepository.save(user)
        }
        return saved
    }

    @Transactional
    override fun declineInvite(memberId: UUID, user: AppUser) {
        val member = calendarMemberRepository.findById(memberId).orElseThrow {
            EntityNotFoundException("Invitation not found")
        }
        if (member.user?.id != user.id || member.state != CalendarMemberState.INVITED) {
            throw AccessDeniedException("You do not have permission to decline this invitation.")
        }
        calendarMemberRepository.delete(member)
    }

    @Transactional
    override fun subscribe(calendarId: UUID, user: AppUser): CalendarMember {
        val calendar = trainingCalendarRepository.findById(calendarId).orElseThrow {
            EntityNotFoundException("Training calendar with id $calendarId not found")
        }
        if (calendar.visibility != Visibility.PUBLIC) {
            throw AccessDeniedException("Cannot subscribe to a private calendar.")
        }
        if (calendar.owner?.id == user.id) {
            throw IllegalArgumentException("You are already the owner of this calendar.")
        }

        val existing = calendarMemberRepository.findByCalendarIdAndUserId(calendarId, user.id!!)
        if (existing != null) {
            if (existing.state == CalendarMemberState.ACTIVE) {
                return existing
            }
            existing.state = CalendarMemberState.ACTIVE
            existing.updatedAt = LocalDateTime.now()
            val saved = calendarMemberRepository.save(existing)
            if (user.defaultCalendar == null && calendar.enabled) {
                user.defaultCalendar = calendar
                appUserRepository.save(user)
            }
            return saved
        }

        val newMember = CalendarMember().apply {
            this.calendar = calendar
            this.user = user
            this.role = CalendarMemberRole.VIEWER
            this.state = CalendarMemberState.ACTIVE
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }
        val saved = calendarMemberRepository.save(newMember)
        if (user.defaultCalendar == null && calendar.enabled) {
            user.defaultCalendar = calendar
            appUserRepository.save(user)
        }
        return saved
    }

    @Transactional
    override fun unsubscribe(calendarId: UUID, user: AppUser) {
        val member = calendarMemberRepository.findByCalendarIdAndUserId(calendarId, user.id!!)
            ?: return
        calendarMemberRepository.delete(member)

        if (user.defaultCalendar?.id == calendarId) {
            fallBackDefaultCalendar(user, calendarId)
        }
    }

    @Transactional
    override fun claimInvitesFor(user: AppUser) {
        val email = user.email?.trim()?.takeIf { it.isNotBlank() } ?: return
        val unclaimed = calendarMemberRepository.findAllByUserIdIsNullAndInvitedEmailIgnoreCase(email)
        for (invite in unclaimed) {
            // Check if user already has an active or invited row on this calendar
            val existing = calendarMemberRepository.findByCalendarIdAndUserId(invite.calendar!!.id!!, user.id!!)
            if (existing != null) {
                calendarMemberRepository.delete(invite)
            } else {
                invite.user = user
                invite.updatedAt = LocalDateTime.now()
                calendarMemberRepository.save(invite)
                log.info("Claimed calendar invite {} for user '{}'", invite.id, user.username)
            }
        }
    }

    override fun isMember(calendarId: UUID, user: AppUser): Boolean {
        val calendar = trainingCalendarRepository.findById(calendarId).orElse(null) ?: return false
        if (calendar.owner?.id == user.id) return true
        return calendarMemberRepository.existsByCalendarIdAndUserIdAndState(calendarId, user.id!!, CalendarMemberState.ACTIVE)
    }

    private fun checkOwnership(calendar: TrainingCalendar, user: AppUser) {
        if (user.role == Role.ADMIN) return
        if (calendar.owner?.id != user.id) {
            throw AccessDeniedException("You do not have permission to modify this calendar's members.")
        }
    }

    private fun checkAccess(calendar: TrainingCalendar, user: AppUser) {
        if (user.role == Role.ADMIN) return
        if (calendar.owner?.id == user.id) return
        if (calendarMemberRepository.existsByCalendarIdAndUserIdAndState(calendar.id!!, user.id!!, CalendarMemberState.ACTIVE)) return
        throw AccessDeniedException("You do not have access to this calendar.")
    }

    private fun fallBackDefaultCalendar(user: AppUser, excludedCalendarId: UUID) {
        val userCalendars = trainingCalendarRepository.findAll(
            TrainingCalendarSpecification.memberOf(user),
            Sort.by(Sort.Direction.ASC, "displayName")
        ).filter { it.id != excludedCalendarId }

        user.defaultCalendar = userCalendars.firstOrNull { it.enabled } ?: userCalendars.firstOrNull()
        appUserRepository.save(user)
    }
}
