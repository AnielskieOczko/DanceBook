package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.UploadedFileRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DriveUploadControllerTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var uploadedFileRepository: UploadedFileRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = appUserRepository.findByUsername("upload-tester") ?: appUserRepository.save(AppUser().apply {
            username = "upload-tester"
            displayName = "Upload Tester"
            email = "upload-tester@example.com"
            password = passwordEncoder.encode("secret")
            role = Role.USER
        })
    }

    @Test
    fun `upload-config endpoint is removed and does not return config`() {
        mockMvc.perform(
            get("/api/materials/upload-config")
                .with(user(testUser.username).roles("USER"))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `create upload session succeeds and returns upload URL without any credentials`() {
        val expectedUploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=mock-session-id"
        `when`(googleDriveService.createResumableSession(
            fileName = "my-video.mp4",
            mimeType = "video/mp4",
            fileSize = 1024L,
            origin = "http://localhost:8080",
            uploaderId = testUser.id!!
        )).thenReturn(expectedUploadUrl)

        mockMvc.perform(
            post("/api/materials/upload-session")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .header("Origin", "http://localhost:8080")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"my-video.mp4","mimeType":"video/mp4","fileSize":1024}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.uploadUrl").value(expectedUploadUrl))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
    }

    @Test
    fun `finalize upload succeeds and persists record when caller matches uploader`() {
        val fileId = "test-file-abc"
        `when`(googleDriveService.getFileUploaderId(fileId)).thenReturn(testUser.id.toString())

        mockMvc.perform(
            post("/api/materials/finalize-upload")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"$fileId"}""")
        ).andExpect(status().isOk)

        verify(googleDriveService).finalizeUpload(fileId)
        assertTrue(uploadedFileRepository.existsByDriveFileIdAndUploaderId(fileId, testUser.id!!))
    }

    @Test
    fun `finalize upload is rejected with 403 when file was not uploaded by caller`() {
        val fileId = "someone-elses-file"
        val otherUserId = UUID.randomUUID()
        `when`(googleDriveService.getFileUploaderId(fileId)).thenReturn(otherUserId.toString())

        mockMvc.perform(
            post("/api/materials/finalize-upload")
                .with(user(testUser.username).roles("USER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"$fileId"}""")
        ).andExpect(status().isForbidden)
    }
}
