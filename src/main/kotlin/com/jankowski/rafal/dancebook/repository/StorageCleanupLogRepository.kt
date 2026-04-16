package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.StorageCleanupLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StorageCleanupLogRepository : JpaRepository<StorageCleanupLog, UUID> {
    fun findAllByOrderByExecutedAtDesc(): List<StorageCleanupLog>
}
