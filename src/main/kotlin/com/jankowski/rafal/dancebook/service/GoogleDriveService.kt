package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleDriveProperties
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.json.JsonHttpContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

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

    fun getFolderId(): String = driveProperties.folderId

    /**
     * Creates a resumable upload session on Google Drive.
     * Returns the pre-authenticated upload URL that the frontend can PUT to directly.
     * Attaches the uploader's user ID to appProperties so file ownership can be verified.
     */
    fun createResumableSession(
        fileName: String,
        mimeType: String,
        fileSize: Long?,
        origin: String?,
        uploaderId: UUID
    ): String {
        val requestFactory = drive.requestFactory

        val metaData = mapOf(
            "name" to fileName,
            "parents" to listOf(driveProperties.folderId),
            "appProperties" to mapOf("uploadedBy" to uploaderId.toString())
        )

        val url = GenericUrl(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        )
        val content = JsonHttpContent(drive.jsonFactory, metaData)
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
     * Downloads file media from Google Drive with optional Range header support.
     * Does not buffer the entire stream into memory.
     */
    fun downloadMedia(fileId: String, rangeHeader: String?): DriveMediaDownload {
        val url = GenericUrl("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val request = drive.requestFactory.buildGetRequest(url).apply {
            headers.set("Accept-Encoding", "identity")
            rangeHeader?.let { headers.range = it }
        }
        val response = try {
            request.execute()
        } catch (e: HttpResponseException) {
            if (e.statusCode == 404) {
                throw EntityNotFoundException("Video file not found on Google Drive: $fileId")
            }
            if (e.statusCode == 416) {
                val contentRange = e.headers?.contentRange ?: e.headers?.getFirstHeaderStringValue("Content-Range")
                return DriveMediaDownload(
                    statusCode = 416,
                    contentType = e.headers?.contentType,
                    contentLength = 0L,
                    contentRange = contentRange,
                    stream = ByteArrayInputStream(ByteArray(0))
                )
            }
            throw e
        }
        return DriveMediaDownload(
            statusCode = response.statusCode,
            contentType = response.contentType,
            contentLength = response.headers.contentLength,
            contentRange = response.headers.contentRange,
            stream = response.content
        )
    }

    /**
     * Reads the appProperties from Google Drive metadata to identify who uploaded the file.
     */
    fun getFileUploaderId(fileId: String): String? {
        return try {
            val file = drive.files().get(fileId)
                .setFields("id, appProperties")
                .setSupportsAllDrives(true)
                .execute()
            file.appProperties?.get("uploadedBy")
        } catch (e: Exception) {
            logger.warn("Could not retrieve metadata for file {}: {}", fileId, e.message)
            null
        }
    }

    /**
     * Finalizes upload on server side.
     * New uploads remain private to the app account (no public 'anyone' permission is added).
     */
    fun finalizeUpload(fileId: String) {
        logger.info("Upload finalized for file {}", fileId)
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

    /**
     * One-off idempotent cleanup of public ('anyone') permissions on existing files in the Drive folder.
     */
    fun cleanPermissions(): PermissionCleanupResult {
        logger.info("Starting one-off cleanup of public permissions in Drive folder {}", driveProperties.folderId)
        val files = listFilesInFolder()
        var permissionsRemoved = 0
        val modifiedFiles = mutableListOf<String>()

        for (file in files) {
            try {
                val permList = drive.permissions().list(file.id).setSupportsAllDrives(true).execute()
                val anyonePerms = permList.permissions?.filter { it.type == "anyone" } ?: emptyList()
                for (perm in anyonePerms) {
                    logger.info("Removing public permission {} from file {} ({})", perm.id, file.id, file.name)
                    drive.permissions().delete(file.id, perm.id).setSupportsAllDrives(true).execute()
                    permissionsRemoved++
                    if (!modifiedFiles.contains(file.id)) {
                        modifiedFiles.add(file.id)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error inspecting/cleaning permissions for file {}: {}", file.id, e.message)
            }
        }
        logger.info("Public permissions cleanup finished: scanned {} files, removed {} permissions across {} files",
            files.size, permissionsRemoved, modifiedFiles.size)
        return PermissionCleanupResult(
            filesScanned = files.size,
            permissionsRemoved = permissionsRemoved,
            modifiedFileIds = modifiedFiles
        )
    }

    fun listFilesInFolder(): List<DriveFileInfo> {
        val results = mutableListOf<DriveFileInfo>()
        var pageToken: String? = null

        do {
            val query = "('${driveProperties.folderId}' in parents) and trashed = false"

            val result = drive.files().list()
                .setQ(query)
                .setFields("nextPageToken, files(id, name, size, createdTime)")
                .setPageToken(pageToken)
                .execute()

            for (file in result.files) {
                results.add(DriveFileInfo(
                    id = file.id,
                    name = file.name,
                    size = file.getSize(),
                    createdTime = file.createdTime.value
                ))
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)
        return results
    }

    data class DriveFileInfo(
        val id: String, 
        val name: String, 
        val size: Long?, 
        val createdTime: Long
    )

    data class DriveMediaDownload(
        val statusCode: Int,
        val contentType: String?,
        val contentLength: Long?,
        val contentRange: String?,
        val stream: InputStream
    ) : AutoCloseable {
        override fun close() {
            stream.close()
        }
    }

    data class PermissionCleanupResult(
        val filesScanned: Int,
        val permissionsRemoved: Int,
        val modifiedFileIds: List<String>
    )
}
