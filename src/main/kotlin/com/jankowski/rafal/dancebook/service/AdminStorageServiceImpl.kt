package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.dto.AdminDriveFileDto
import com.jankowski.rafal.dancebook.dto.DriveInspection
import com.jankowski.rafal.dancebook.dto.LogPresentation
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminStorageServiceImpl(
    private val googleDriveService: GoogleDriveService,
    private val materialRepository: MaterialRepository,
    private val storageCleanupLogRepository: StorageCleanupLogRepository,
    private val objectMapper: ObjectMapper
) : AdminStorageService {

    @Transactional(readOnly = true)
    override fun inspectDrive(): DriveInspection {
        val driveFiles = googleDriveService.listFilesInFolder()
        val databaseIds = materialRepository.findAllDriveFileIds()

        var orphanedSize = 0L
        var linkedSize = 0L
        var orphanedCount = 0
        var linkedCount = 0

        val files = driveFiles.map { file ->
            val orphaned = !databaseIds.contains(file.id)
            if (orphaned) {
                orphanedSize += (file.size ?: 0L)
                orphanedCount++
            } else {
                linkedSize += (file.size ?: 0L)
                linkedCount++
            }
            AdminDriveFileDto(
                id = file.id,
                name = file.name,
                sizeBytes = file.size,
                createdTime = file.createdTime,
                isOrphaned = orphaned
            )
        }
        return DriveInspection(files, orphanedSize, linkedSize, orphanedCount, linkedCount)
    }

    @Transactional(readOnly = true)
    override fun cleanupHistory(limit: Int?): List<LogPresentation> {
        val logs = storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()
        return (if (limit != null) logs.take(limit) else logs).map { log ->
            val filesList = try {
                if (!log.details.isNullOrBlank() && log.details!!.startsWith("[")) {
                    objectMapper.readValue(log.details, object : TypeReference<List<GoogleDriveService.DriveFileInfo>>() {})
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            LogPresentation(
                executedAt = log.executedAt,
                filesDeletedCount = log.filesDeletedCount,
                isDryRun = log.isDryRun,
                deletedFiles = filesList
            )
        }
    }
}
