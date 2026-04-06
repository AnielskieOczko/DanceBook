package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UploadConfigResponse(
    val accessToken: String,
    val folderId: String
)

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
    private val googleDriveService: GoogleDriveService
) {

    /**
     * Returns a fresh access token + folder ID for direct browser uploads.
     * The token is generated server-side from a stored refresh token — no popup needed.
     */
    @GetMapping("/upload-config")
    fun getUploadConfig(): ResponseEntity<UploadConfigResponse> {
        val config = UploadConfigResponse(
            accessToken = googleDriveService.getAccessToken(),
            folderId = googleDriveService.getFolderId()
        )
        return ResponseEntity.ok(config)
    }

    /**
     * Creates a resumable upload session on Google Drive.
     * Returns a pre-authenticated URL the browser can PUT file data to directly.
     */
    @PostMapping("/upload-session")
    fun createUploadSession(
        @RequestBody request: UploadSessionRequest,
        @RequestHeader("Origin", required = false) origin: String?
    ): ResponseEntity<UploadSessionResponse> {
        val uploadUrl = googleDriveService.createResumableSession(
            fileName = request.fileName,
            mimeType = request.mimeType,
            fileSize = request.fileSize,
            origin = origin
        )
        return ResponseEntity.ok(UploadSessionResponse(uploadUrl = uploadUrl))
    }

    /**
     * Sets public read permission on an uploaded file.
     * Called by the frontend after the upload to the resumable URL completes.
     */
    @PostMapping("/finalize-upload")
    fun finalizeUpload(@RequestBody request: FinalizeRequest): ResponseEntity<Void> {
        googleDriveService.finalizeUpload(request.fileId)
        return ResponseEntity.ok().build()
    }
}
