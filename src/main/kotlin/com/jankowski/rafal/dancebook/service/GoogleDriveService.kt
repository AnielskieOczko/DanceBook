package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleDriveProperties
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.Permission
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class GoogleDriveService(
    private val driveProperties: GoogleDriveProperties
) {
    private val logger = LoggerFactory.getLogger(GoogleDriveService::class.java)

    private val credentials by lazy {
        logger.info("Initializing Google Drive Credentials...")
        logger.info("Client ID ends with: ${driveProperties.clientId.takeLast(4)}")
        logger.info("Client Secret length: ${driveProperties.clientSecret.length}")
        logger.info("Refresh Token length: ${driveProperties.refreshToken.length}")
        logger.info("Folder ID length: ${driveProperties.folderId.length}")

        val clientId = driveProperties.clientId.trim()
        val clientSecret = driveProperties.clientSecret.trim()
        val refreshToken = driveProperties.refreshToken.trim()

        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            val missing = mutableListOf<String>()
            if (clientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
            if (clientSecret.isBlank()) missing.add("GOOGLE_CLIENT_SECRET")
            if (refreshToken.isBlank()) missing.add("GOOGLE_REFRESH_TOKEN")
            
            val errorMsg = "CRITICAL: Missing required Google Drive properties: ${missing.joinToString()}. " +
                           "Check your Environment Variables / GitHub Secrets!"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .build()
    }

    private val drive: Drive by lazy {
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName("DanceBook")
            .build()
    }

    /**
     * Returns a fresh access token (auto-refreshes if expired).
     * Used by the frontend to upload directly to Google Drive.
     */
    fun getAccessToken(): String {
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    fun getFolderId(): String = driveProperties.folderId

    /**
     * Creates a resumable upload session on Google Drive.
     * Returns the pre-authenticated upload URL that the frontend can PUT to directly.
     */
    fun createResumableSession(fileName: String, mimeType: String, fileSize: Long?, origin: String?): String {
        val requestFactory = drive.requestFactory

        val metaData = mapOf("name" to fileName, "parents" to listOf(driveProperties.folderId))

        val url = com.google.api.client.http.GenericUrl(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        )
        val content = com.google.api.client.http.json.JsonHttpContent(drive.jsonFactory, metaData)
        val request = requestFactory.buildPostRequest(url, content).apply {
            headers.set("X-Upload-Content-Type", mimeType)
            fileSize?.let { headers.set("X-Upload-Content-Length", it.toString()) }
            origin?.let { headers.set("Origin", it) }
        }

        val response = request.execute()
        return response.headers.location
            ?: throw IllegalStateException("No upload URL returned by Google Drive")
    }

    /**
     * Sets public read permission on a file and returns its ID.
     * Called after frontend finishes uploading to the resumable URL.
     */
    fun finalizeUpload(fileId: String) {
        logger.info("Setting public permission for file {}", fileId)
        try {
            drive.permissions()
                .create(
                    fileId,
                    Permission().apply {
                        type = "anyone"
                        role = "reader"
                    }
                )
                .execute()
        } catch (e: Exception) {
            logger.warn("Set public read failed for {}: {}", fileId, e.message)
        }
    }

    /**
     * Deletes a file from Google Drive.
     */
    fun deleteFile(fileId: String) {
        logger.info("Deleting Drive file {}", fileId)
        try {
            drive.files().delete(fileId).setSupportsAllDrives(true).execute()
        } catch (e: Exception) {
            logger.error("Failed to delete {}: {}", fileId, e.message)
        }
    }
}
