package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class GoogleDriveServiceTest {

    @Test
    fun `DriveMediaDownload auto-closes underlying stream`() {
        var closed = false
        val stream = object : ByteArrayInputStream("video-data".toByteArray()) {
            override fun close() {
                super.close()
                closed = true
            }
        }

        val media = GoogleDriveService.DriveMediaDownload(
            statusCode = 206,
            contentType = "video/mp4",
            contentLength = 10L,
            contentRange = "bytes 0-9/10",
            stream = stream
        )

        media.use {
            assertEquals(206, it.statusCode)
            assertEquals("video/mp4", it.contentType)
            assertEquals(10L, it.contentLength)
            assertEquals("bytes 0-9/10", it.contentRange)
            assertFalse(closed)
        }

        assertTrue(closed)
    }

    @Test
    fun `PermissionCleanupResult holds summary data correctly`() {
        val result = GoogleDriveService.PermissionCleanupResult(
            filesScanned = 10,
            permissionsRemoved = 3,
            modifiedFileIds = listOf("f1", "f2")
        )

        assertEquals(10, result.filesScanned)
        assertEquals(3, result.permissionsRemoved)
        assertEquals(2, result.modifiedFileIds.size)
    }

    @Test
    fun `DriveMediaDownload holds 416 status and content-range correctly`() {
        val download = GoogleDriveService.DriveMediaDownload(
            statusCode = 416,
            contentType = null,
            contentLength = 0L,
            contentRange = "bytes */5000",
            stream = ByteArrayInputStream(ByteArray(0))
        )
        assertEquals(416, download.statusCode)
        assertEquals("bytes */5000", download.contentRange)
        assertEquals(0L, download.contentLength)
        assertNull(download.contentType)
        assertEquals(-1, download.stream.read())
    }
}
