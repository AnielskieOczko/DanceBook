package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jankowski.rafal.dancebook.model.StorageCleanupLog
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

class AdminStorageServiceTest {

    private val googleDriveService = mock(GoogleDriveService::class.java)
    private val materialRepository = mock(MaterialRepository::class.java)
    private val storageCleanupLogRepository = mock(StorageCleanupLogRepository::class.java)
    private val service = AdminStorageServiceImpl(googleDriveService, materialRepository, storageCleanupLogRepository, jacksonObjectMapper())

    @Test
    fun `a file no note points at is orphaned, and the sizes add up per group`() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(
            listOf(
                GoogleDriveService.DriveFileInfo("linked", "linked.mp4", 100L, 0L),
                GoogleDriveService.DriveFileInfo("orphan-a", "a.mp4", 40L, 0L),
                GoogleDriveService.DriveFileInfo("orphan-b", "b.mp4", null, 0L)
            )
        )
        `when`(materialRepository.findAllDriveFileIds()).thenReturn(setOf("linked"))

        val inspection = service.inspectDrive()

        assertEquals(1, inspection.linkedCount)
        assertEquals(2, inspection.orphanedCount)
        assertEquals(100L, inspection.linkedSize)
        assertEquals(40L, inspection.orphanedSize)
        assertEquals(3, inspection.totalCount)
        assertEquals(140L, inspection.totalSize)
        assertEquals(listOf(false, true, true), inspection.files.map { it.isOrphaned })
    }

    @Test
    fun `cleanup history is newest first, cut to the limit, and reads the deleted files out of the log`() {
        val newest = StorageCleanupLog(
            executedAt = LocalDateTime.of(2026, 10, 20, 8, 0), filesDeletedCount = 1, isDryRun = false,
            details = """[{"id":"x","name":"x.mp4","size":10,"createdTime":0}]"""
        )
        val older = StorageCleanupLog(executedAt = LocalDateTime.of(2026, 10, 1, 8, 0), filesDeletedCount = 0, isDryRun = true, details = "not json")
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(listOf(newest, older))

        val limited = service.cleanupHistory(1)
        assertEquals(1, limited.size)
        assertEquals(listOf("x.mp4"), limited.first().deletedFiles.map { it.name })

        val all = service.cleanupHistory()
        assertEquals(2, all.size)
        assertTrue(all.last().deletedFiles.isEmpty(), "an unreadable log reads as no files, not as an error")
    }
}
