package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.model.UploadedFile
import com.jankowski.rafal.dancebook.repository.UploadedFileRepository
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

data class UploadSessionRequest(
    val fileName: String,
    val mimeType: String,
    val fileSize: Long? = null
)

data class UploadSessionResponse(
    val uploadUrl: String
)

data class FinalizeRequest(
    val fileId: String
)

@RestController
@RequestMapping("/api/materials")
class DriveUploadController(
    private val googleDriveService: GoogleDriveService,
    private val appUserService: AppUserService,
    private val uploadedFileRepository: UploadedFileRepository
) {

    /**
     * Creates a resumable upload session on Google Drive.
     * Returns a pre-authenticated URL the browser can PUT file data to directly.
     * Never exposes Google credentials to the browser.
     */
    @PostMapping("/upload-session")
    fun createUploadSession(
        @RequestBody request: UploadSessionRequest,
        @RequestHeader("Origin", required = false) origin: String?
    ): ResponseEntity<UploadSessionResponse> {
        val currentUser = appUserService.getCurrentUser()
        val uploadUrl = googleDriveService.createResumableSession(
            fileName = request.fileName,
            mimeType = request.mimeType,
            fileSize = request.fileSize,
            origin = origin,
            uploaderId = currentUser.id!!
        )
        return ResponseEntity.ok(UploadSessionResponse(uploadUrl = uploadUrl))
    }

    /**
     * Finalizes an upload and verifies that the current user is the one who created the upload session.
     */
    @PostMapping("/finalize-upload")
    fun finalizeUpload(@RequestBody request: FinalizeRequest): ResponseEntity<Void> {
        val currentUser = appUserService.getCurrentUser()
        val uploaderId = googleDriveService.getFileUploaderId(request.fileId)
        if (uploaderId != currentUser.id.toString()) {
            throw AccessDeniedException("User does not have permission to finalize file ${request.fileId}")
        }
        googleDriveService.finalizeUpload(request.fileId)
        uploadedFileRepository.save(
            UploadedFile(
                driveFileId = request.fileId,
                uploader = currentUser,
                createdAt = LocalDateTime.now()
            )
        )
        return ResponseEntity.ok().build()
    }
}
