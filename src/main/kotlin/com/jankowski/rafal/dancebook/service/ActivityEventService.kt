package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.ActivityEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface ActivityEventService {
    fun getUnreadCount(userId: UUID): Long
    fun getUnreadEvents(userId: UUID): List<ActivityEvent>
    fun getRecentEvents(limit: Int): List<ActivityEvent>
    fun markAllAsRead(userId: UUID)
    fun markAsRead(eventId: UUID, userId: UUID)
    fun getAllEvents(pageable: Pageable): Page<ActivityEvent>
}
