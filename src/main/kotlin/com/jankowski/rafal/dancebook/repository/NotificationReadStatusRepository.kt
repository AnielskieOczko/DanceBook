package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.NotificationReadStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationReadStatusRepository : JpaRepository<NotificationReadStatus, UUID> {

    fun findByEventIdAndUserId(eventId: UUID, userId: UUID): NotificationReadStatus?

    fun existsByEventIdAndUserIdAndIsReadTrue(eventId: UUID, userId: UUID): Boolean
}
