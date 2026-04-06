package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UploadConfigResponse(
    val clientId: String,
    val folderId: String
)

@RestController
@RequestMapping("/api/materials")
class DriveUploadController(
    private val googleDriveService: GoogleDriveService
) {

    @GetMapping("/upload-config")
    fun getUploadConfig(): ResponseEntity<UploadConfigResponse> {
        val config = UploadConfigResponse(
            clientId = googleDriveService.getClientId(),
            folderId = googleDriveService.getFolderId()
        )
        return ResponseEntity.ok(config)
    }
}
