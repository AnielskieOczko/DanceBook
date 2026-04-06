package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleDriveProperties
import org.springframework.stereotype.Service

@Service
class GoogleDriveService(
    private val driveProperties: GoogleDriveProperties
) {
    fun getClientId(): String = driveProperties.clientId
    fun getFolderId(): String = driveProperties.folderId
}
