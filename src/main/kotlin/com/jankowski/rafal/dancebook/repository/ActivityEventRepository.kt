package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.ActivityEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ActivityEventRepository : JpaRepository<ActivityEvent, UUID> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<ActivityEvent>

    fun findTop10ByOrderByCreatedAtDesc(): List<ActivityEvent>

    @Query("""
        SELECT e FROM ActivityEvent e
        LEFT JOIN NotificationReadStatus s ON e.id = s.event.id AND s.user.id = :userId
        WHERE s.isRead IS NULL OR s.isRead = false
        ORDER BY e.createdAt DESC
    """)
    fun findUnreadByUser(@Param("userId") userId: UUID): List<ActivityEvent>

    @Query("""
        SELECT COUNT(e) FROM ActivityEvent e
        LEFT JOIN NotificationReadStatus s ON e.id = s.event.id AND s.user.id = :userId
        WHERE s.isRead IS NULL OR s.isRead = false
    """)
    fun countUnreadByUser(@Param("userId") userId: UUID): Long
}
