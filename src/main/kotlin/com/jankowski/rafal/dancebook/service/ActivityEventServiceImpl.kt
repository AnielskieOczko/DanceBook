package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.NotificationReadStatus
import com.jankowski.rafal.dancebook.repository.ActivityEventRepository
import com.jankowski.rafal.dancebook.repository.NotificationReadStatusRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class ActivityEventServiceImpl(
    private val activityEventRepository: ActivityEventRepository,
    private val readStatusRepository: NotificationReadStatusRepository,
    private val appUserService: AppUserService
) : ActivityEventService {

    companion object {
        private val log = LoggerFactory.getLogger(ActivityEventServiceImpl::class.java)
    }

    override fun getUnreadCount(userId: UUID): Long {
        return activityEventRepository.countUnreadByUser(userId)
    }

    override fun getUnreadEvents(userId: UUID): List<ActivityEvent> {
        return activityEventRepository.findUnreadByUser(userId)
    }

    override fun getRecentEvents(limit: Int): List<ActivityEvent> {
        return activityEventRepository.findTop10ByOrderByCreatedAtDesc()
    }

    @Transactional
    override fun markAllAsRead(userId: UUID) {
        log.debug("Marking all events as read for user {}", userId)
        val user = appUserService.findById(userId)
        val unread = activityEventRepository.findUnreadByUser(userId)

        unread.forEach { event ->
            val existing = readStatusRepository.findByEventIdAndUserId(event.id!!, userId)
            if (existing == null) {
                val status = NotificationReadStatus().apply {
                    this.event = event
                    this.user = user
                    this.isRead = true
                    this.readAt = LocalDateTime.now()
                }
                readStatusRepository.save(status)
            } else {
                existing.isRead = true
                existing.readAt = LocalDateTime.now()
                readStatusRepository.save(existing)
            }
        }
    }

    @Transactional
    override fun markAsRead(eventId: UUID, userId: UUID) {
        log.debug("Marking event {} as read for user {}", eventId, userId)
        val existing = readStatusRepository.findByEventIdAndUserId(eventId, userId)
        if (existing != null) {
            existing.isRead = true
            existing.readAt = LocalDateTime.now()
            readStatusRepository.save(existing)
        } else {
            val user = appUserService.findById(userId)
            val event = activityEventRepository.findById(eventId).orElseThrow {
                jakarta.persistence.EntityNotFoundException("Event with id $eventId not found")
            }
            val status = NotificationReadStatus().apply {
                this.event = event
                this.user = user
                this.isRead = true
                this.readAt = LocalDateTime.now()
            }
            readStatusRepository.save(status)
        }
    }

    override fun getAllEvents(pageable: Pageable): Page<ActivityEvent> {
        return activityEventRepository.findAllByOrderByCreatedAtDesc(pageable)
    }
}
