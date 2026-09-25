package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.UploadedFile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UploadedFileRepository : JpaRepository<UploadedFile, String> {
    fun existsByDriveFileIdAndUploaderId(driveFileId: String, uploaderId: UUID): Boolean
}
