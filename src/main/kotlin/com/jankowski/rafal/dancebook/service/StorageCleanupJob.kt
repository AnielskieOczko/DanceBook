package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.model.StorageCleanupLog
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class StorageCleanupJob(
    private val materialRepository: MaterialRepository,
    private val googleDriveService: GoogleDriveService,
    private val storageCleanupLogRepository: StorageCleanupLogRepository,
    private val objectMapper: ObjectMapper
) {
    // We only clean files older than 24 hours (86,400,000 milliseconds)
    // This stops us from deleting a video uploaded right now
    private val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    fun runCleanup(dryRun: Boolean = false): List<String> {
        val dataBaseIds = materialRepository.findAllDriveFileIds()
        val driveFiles = googleDriveService.listFilesInFolder()

        val now = System.currentTimeMillis()
        val orphanedFiles = mutableListOf<GoogleDriveService.DriveFileInfo>()

        for (file in driveFiles) {
            val isOldEnough = (now - file.createdTime) > ONE_DAY_MS
            val isMissingFromDataBase = !dataBaseIds.contains(file.id)

            if (isOldEnough && isMissingFromDataBase) {
                orphanedFiles.add(file)
                if (!dryRun) {
                    googleDriveService.deleteFile(file.id)
                }
            }

        }

        // Audit Log Generation
        val logEntry = StorageCleanupLog(
            executedAt = LocalDateTime.now(),
            filesDeletedCount = orphanedFiles.size,
            isDryRun = dryRun,
            details = if (orphanedFiles.isNotEmpty()) objectMapper.writeValueAsString(orphanedFiles) else null
        )
        storageCleanupLogRepository.save(logEntry)

        return orphanedFiles.map { it.id }
    }

    @Scheduled(cron = "0 0 3 1,15 * *")
    fun runAutoCleanup() {
        runCleanup(false)
    }

}