package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.jankowski.rafal.dancebook.service.KnowledgeIndexStatus
import java.time.LocalDateTime

data class AdminDriveFileDto(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val createdTime: Long,
    val isOrphaned: Boolean
)

data class LogPresentation(
    val executedAt: LocalDateTime,
    val filesDeletedCount: Int,
    val isDryRun: Boolean,
    val deletedFiles: List<GoogleDriveService.DriveFileInfo>
)

/** What is in Google Drive and how much of it no note points at. */
data class DriveInspection(
    val files: List<AdminDriveFileDto>,
    val orphanedSize: Long,
    val linkedSize: Long,
    val orphanedCount: Int,
    val linkedCount: Int
) {
    val totalSize: Long get() = orphanedSize + linkedSize
    val totalCount: Int get() = orphanedCount + linkedCount
}

/** The values the admin dashboard shows in its summary panel and on its rows. */
data class AdminOverview(
    val userCount: Long,
    val figureCount: Long,
    val noteCount: Long,
    val calendarCount: Int,
    val defaultCalendarName: String?,
    val danceTypeCount: Int,
    val categoryCount: Int,
    val categoryNames: List<String>,
    val pollInterval: Int,
    val autoLogout: Int,
    val knowledgeStatus: KnowledgeIndexStatus?,
    val knowledgeIndexStale: Boolean,
    val lastCleanupAt: LocalDateTime?
) {
    /** True when the index is out of date or some chunks still lack an embedding. */
    val knowledgeNeedsAttention: Boolean
        get() = knowledgeIndexStale || (knowledgeStatus != null && !knowledgeStatus.isComplete)
}

/** A slice of the user list: the first rows, and how many there are in all. */
data class AdminUsersView(
    val users: List<AppUser>,
    val total: Long,
    val adminCount: Long,
    val all: Boolean
)
