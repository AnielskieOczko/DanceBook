package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "storage_cleanup_log")
class StorageCleanupLog(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null,

    @Column(nullable = false, updatable = false)
    var executedAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false, updatable = false)
    var filesDeletedCount: Int = 0,

    @Column(nullable = false, updatable = false)
    var isDryRun: Boolean = false,

    @Column(columnDefinition = "TEXT", updatable = false)
    var details: String? = null
)
