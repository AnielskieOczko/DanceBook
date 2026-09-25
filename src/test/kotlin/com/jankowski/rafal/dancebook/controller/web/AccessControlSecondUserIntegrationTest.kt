package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.dto.CustomListRequest
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import org.springframework.http.MediaType
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class AccessControlSecondUserIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var materialService: MaterialService
    @Autowired private lateinit var activityEventService: ActivityEventService
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var commentService: CommentService
    @Autowired private lateinit var commentRepository: CommentRepository
    @Autowired private lateinit var customListService: CustomListService
    @Autowired private lateinit var customListRepository: CustomListRepository
    @Autowired private lateinit var choreographyService: ChoreographyService
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var uploadedFileRepository: UploadedFileRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var dataSource: javax.sql.DataSource

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private fun <T> asUser(user: AppUser, block: () -> T): T {
        val auth = UsernamePasswordAuthenticationToken(
            user.username, null, listOf(SimpleGrantedAuthority("ROLE_" + user.role.name))
        )
        SecurityContextHolder.getContext().authentication = auth
        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var adminUser: AppUser
    private lateinit var testDanceType: DanceType
    private lateinit var testDanceFigure: DanceFigure

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())
        `when`(googleDriveService.downloadMedia(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(
            GoogleDriveService.DriveMediaDownload(200, "video/mp4", 100L, null, java.io.ByteArrayInputStream(ByteArray(100)))
        )
        `when`(googleDriveService.downloadMedia(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("bytes=0-10"))).thenReturn(
            GoogleDriveService.DriveMediaDownload(206, "video/mp4", 11L, "bytes 0-10/100", java.io.ByteArrayInputStream(ByteArray(11)))
        )

        adminUser = appUserRepository.findByUsername("sec-admin") ?: appUserRepository.save(AppUser().apply {
            username = "sec-admin"
            displayName = "Admin User"
            email = "sec-admin@example.com"
            password = passwordEncoder.encode("secret")
            role = Role.ADMIN
        })

        userA = appUserRepository.findByUsername("sec-user-a") ?: appUserRepository.save(AppUser().apply {
            username = "sec-user-a"
            displayName = "User A"
            email = "sec-user-a@example.com"
            password = passwordEncoder.encode("secret")
            role = Role.USER
        })

        userB = appUserRepository.findByUsername("sec-user-b") ?: appUserRepository.save(AppUser().apply {
            username = "sec-user-b"
            displayName = "User B"
            email = "sec-user-b@example.com"
            password = passwordEncoder.encode("secret")
            role = Role.USER
        })

        val cat = danceCategoryRepository.save(DanceCategory().apply {
            name = "Access Test Cat " + UUID.randomUUID()
            predefined = false
        })
        testDanceType = danceTypeRepository.save(DanceType().apply {
            name = "Access Test Style " + UUID.randomUUID()
            category = cat
            predefined = false
        })
        testDanceFigure = danceFigureRepository.save(DanceFigure().apply {
            name = "Access Test Figure " + UUID.randomUUID()
            danceType = testDanceType
        })
    }

    @Test
    fun `private note is completely invisible to User B and visible to owner and admin`() {
        // User A creates a private note (default is private)
        val note = materialRepository.save(Material().apply {
            name = "User A Secret Note"
            description = "Private description"
            owner = userA
            visibility = Visibility.PRIVATE
            danceType = testDanceType
            driveFileId = "sec-user-a-video"
            version = 0
            updatedAt = LocalDateTime.now()
        })
        val noteId = note.id!!
        uploadedFileRepository.save(UploadedFile("sec-user-a-video", userA, LocalDateTime.now()))

        // Add a figure to the note
        val fig = Figure().apply {
            material = note
            danceFigure = testDanceFigure
            startTime = 0
            endTime = 10
        }
        note.figures.add(fig)
        val savedNote = materialRepository.save(note)
        val figId = savedNote.figures.firstOrNull()?.id ?: UUID.randomUUID()

        // Add a comment to the note by User A
        val comment = asUser(userA) { commentService.addComment(noteId, "User A private comment", userA) }
        val commentId = comment.id!!

        // 1. User B gets 404 on page, edit, API, comments, fragments, video
        mockMvc.perform(get("/materials/$noteId").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/video").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/edit").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/api/materials/$noteId").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/comments/$commentId").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/comments/$commentId/edit").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(
            post("/materials/$noteId/comments")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("content", "B should not comment")
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            put("/materials/$noteId/comments/$commentId")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("content", "B edit")
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            delete("/materials/$noteId/comments/$commentId")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/materials/$noteId/figures")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("danceFigureId", testDanceFigure.id.toString())
                .param("startTime", "5")
                .param("endTime", "15")
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/materials/$noteId/figures/$figId/delete")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isNotFound)

        // 2. Absent from User B's library list
        mockMvc.perform(get("/materials").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("User A Secret Note"))))

        // 3. Absent from User B's note picker on training events form
        mockMvc.perform(get("/training-events/new").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("User A Secret Note"))))

        // 4. User A (owner) can view and edit, and stream video with Range support
        mockMvc.perform(get("/materials/$noteId").with(user(userA.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("User A Secret Note")))
            .andExpect(content().string(containsString("Private")))

        mockMvc.perform(get("/materials/$noteId/video").with(user(userA.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(header().string("Accept-Ranges", "bytes"))

        mockMvc.perform(
            get("/materials/$noteId/video")
                .with(user(userA.username).roles("USER"))
                .header("Range", "bytes=0-10")
        )
            .andExpect(status().isPartialContent)
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Content-Range", "bytes 0-10/100"))

        mockMvc.perform(get("/materials/$noteId/edit").with(user(userA.username).roles("USER")))
            .andExpect(status().isOk)

        // 5. Admin can view and stream video
        mockMvc.perform(get("/materials/$noteId").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("User A Secret Note")))

        mockMvc.perform(get("/materials/$noteId/video").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
    }

    @Test
    fun `publishing note makes it visible to B, and making it private again revokes access`() {
        // User A creates a note
        val note = materialRepository.save(Material().apply {
            name = "Toggle Visibility Note"
            owner = userA
            visibility = Visibility.PRIVATE
            danceType = testDanceType
            driveFileId = "sec-toggle-video"
            version = 0
            updatedAt = LocalDateTime.now()
        })
        val noteId = note.id!!
        uploadedFileRepository.save(UploadedFile("sec-toggle-video", userA, LocalDateTime.now()))

        // Initially User B cannot see page or video
        mockMvc.perform(get("/materials/$noteId").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/video").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        // User A publishes it
        note.visibility = Visibility.PUBLIC
        materialRepository.save(note)

        // Now User B can view it, play its video, and view on /materials
        mockMvc.perform(get("/materials/$noteId").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Toggle Visibility Note")))
            .andExpect(content().string(containsString("Public")))

        mockMvc.perform(get("/materials/$noteId/video").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)

        mockMvc.perform(get("/materials").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Toggle Visibility Note")))

        // User B cannot edit User A's public note (non-owner edit returns 404)
        mockMvc.perform(get("/materials/$noteId/edit").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        // User B can comment on it
        mockMvc.perform(
            post("/materials/$noteId/comments")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("content", "User B comment on public note")
        ).andExpect(status().isOk)

        val comments = asUser(userA) { commentService.getCommentsForMaterial(noteId) }
        val userBComment = comments.first { it.author?.id == userB.id }

        // User A makes it private again
        val freshNote = materialRepository.findById(noteId).get()
        freshNote.visibility = Visibility.PRIVATE
        materialRepository.save(freshNote)

        // User B loses access everywhere immediately (page and video)
        mockMvc.perform(get("/materials/$noteId").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials/$noteId/video").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/materials").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Toggle Visibility Note"))))

        // User B trying to edit their own comment on the now-private note gets 404
        mockMvc.perform(
            put("/materials/$noteId/comments/${userBComment.id}")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("content", "User B trying to edit")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `saving a note with a file id the user did not upload is rejected, and so is finalizing one`() {
        val fileA = "user-a-uploaded-file-456"
        uploadedFileRepository.save(UploadedFile(driveFileId = fileA, uploader = userA, createdAt = LocalDateTime.now()))
        `when`(googleDriveService.getFileUploaderId(fileA)).thenReturn(userA.id.toString())

        // 1. User B tries to finalize User A's file -> 403 Forbidden
        mockMvc.perform(
            post("/api/materials/finalize-upload")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"$fileA"}""")
        ).andExpect(status().isForbidden)

        // 2. User B tries to save a note with User A's file -> 403 Forbidden
        mockMvc.perform(
            post("/materials")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("name", "User B Imposter Note")
                .param("driveFileId", fileA)
                .param("version", "0")
        ).andExpect(status().isForbidden)

        // 3. User A can save a note with fileA
        mockMvc.perform(
            post("/materials")
                .with(user(userA.username).roles("USER"))
                .with(csrf())
                .param("name", "User A Legitimate Note")
                .param("driveFileId", fileA)
                .param("version", "0")
        ).andExpect(status().is3xxRedirection)

        val note = materialRepository.findAll().first { it.name == "User A Legitimate Note" }
        assertEquals(fileA, note.driveFileId)

        // 4. Updating note keeping existing file succeeds without re-verification
        mockMvc.perform(
            post("/materials/${note.id}")
                .with(user(userA.username).roles("USER"))
                .with(csrf())
                .param("name", "User A Renamed Note")
                .param("driveFileId", fileA)
                .param("version", note.version.toString())
        ).andExpect(status().is3xxRedirection)

        // 5. Deleting note deletes file from Drive
        mockMvc.perform(
            post("/materials/${note.id}/delete")
                .with(user(userA.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().is3xxRedirection)

        org.mockito.Mockito.verify(googleDriveService).deleteFile(fileA)
    }

    @Test
    fun `private collections and choreographies return 404 for other users`() {
        // User A creates private collection
        val list = customListRepository.save(CustomList().apply {
            name = "User A Private Collection"
            owner = userA
            visibility = Visibility.PRIVATE
        })

        // User A creates private choreography
        val choreo = choreographyRepository.save(Choreography().apply {
            name = "User A Private Choreo"
            owner = userA
            danceType = testDanceType
            visibility = Visibility.PRIVATE
            updatedAt = LocalDateTime.now()
        })

        // User B gets 404 on private collection
        mockMvc.perform(get("/lists/${list.id}").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/lists/${list.id}/edit").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        // User B gets 404 on private choreography
        mockMvc.perform(get("/choreographies/${choreo.id}").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/choreographies/${choreo.id}/edit").with(user(userB.username).roles("USER")))
            .andExpect(status().isNotFound)

        // Admin can view both
        mockMvc.perform(get("/lists/${list.id}").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)

        mockMvc.perform(get("/choreographies/${choreo.id}").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
    }

    @Test
    fun `public collection with private notes warns owner and filters notes for viewer`() {
        // User A creates a private note
        materialRepository.save(Material().apply {
            name = "Matched Private Note"
            owner = userA
            visibility = Visibility.PRIVATE
            danceType = testDanceType
            version = 0
            updatedAt = LocalDateTime.now()
        })

        // User A creates a public note
        materialRepository.save(Material().apply {
            name = "Matched Public Note"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            version = 0
            updatedAt = LocalDateTime.now()
        })

        // User A creates a public collection filtering by testDanceType
        val list = customListRepository.save(CustomList().apply {
            name = "User A Public Collection"
            owner = userA
            visibility = Visibility.PUBLIC
            danceTypes.add(testDanceType)
        })

        // When User A (owner) views the collection, warning alert banner is shown
        mockMvc.perform(get("/lists/${list.id}").with(user(userA.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Private Notes Warning")))
            .andExpect(content().string(containsString("Matched Private Note")))
            .andExpect(content().string(containsString("Matched Public Note")))

        // When User B views the public collection, User B only sees the public note and NO warning banner
        mockMvc.perform(get("/lists/${list.id}").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Private Notes Warning"))))
            .andExpect(content().string(not(containsString("Matched Private Note"))))
            .andExpect(content().string(containsString("Matched Public Note")))
    }

    @Test
    fun `user B notifications, dashboard counts, and API do not leak user A private notes, comments, or deleted items`() {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val pubNoteName = "User A Public Note $uniqueSuffix"
        val privNoteName = "User A Secret Note $uniqueSuffix"
        val privCommentContent = "Secret comment on private note $uniqueSuffix"
        val deletedPrivNoteName = "User A Ephemeral Note $uniqueSuffix"

        // User A creates a public note and adds a comment to it
        val pubNote = asUser(userA) {
            materialService.create(MaterialRequest(
                name = pubNoteName,
                danceTypeId = testDanceType.id,
                isPublic = true,
                version = 0
            ))
        }
        asUser(userA) {
            commentService.addComment(pubNote.id!!, "Public comment $uniqueSuffix", userA)
        }

        // User A creates a private note and adds a comment to it
        val privNote = asUser(userA) {
            materialService.create(MaterialRequest(
                name = privNoteName,
                danceTypeId = testDanceType.id,
                isPublic = false,
                version = 0
            ))
        }
        asUser(userA) {
            commentService.addComment(privNote.id!!, privCommentContent, userA)
        }

        // User A creates another private note and deletes it while private
        val ephemeralNote = asUser(userA) {
            materialService.create(MaterialRequest(
                name = deletedPrivNoteName,
                danceTypeId = testDanceType.id,
                isPublic = false,
                version = 0
            ))
        }
        asUser(userA) {
            materialService.delete(ephemeralNote.id!!)
        }

        // 1. GET /notifications for User B should NOT contain private note, private comment, or deleted private note
        mockMvc.perform(get("/notifications").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(pubNoteName)))
            .andExpect(content().string(not(containsString(privNoteName))))
            .andExpect(content().string(not(containsString(privCommentContent))))
            .andExpect(content().string(not(containsString(deletedPrivNoteName))))

        // 2. GET /notifications/count for User B returns OK
        mockMvc.perform(get("/notifications/count").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)

        // The badge counts exactly the entries the notifications page shows, none of them hidden ones
        val unreadForB = activityEventService.getUnreadEvents(userB.id!!)
        assertEquals(unreadForB.size.toLong(), activityEventService.getUnreadCount(userB.id!!))
        assertTrue(unreadForB.none { it.targetName in setOf(privNoteName, deletedPrivNoteName) })

        // 3. GET /notifications/history and /activity-history for User B
        mockMvc.perform(get("/notifications/history").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(pubNoteName)))
            .andExpect(content().string(not(containsString(privNoteName))))
            .andExpect(content().string(not(containsString(privCommentContent))))
            .andExpect(content().string(not(containsString(deletedPrivNoteName))))

        mockMvc.perform(get("/activity-history").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString(privNoteName))))
            .andExpect(content().string(not(containsString(privCommentContent))))
            .andExpect(content().string(not(containsString(deletedPrivNoteName))))

        // 4. GET / (Dashboard) for User B:
        // Dashboard should not show private note in recent activity
        mockMvc.perform(get("/").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString(privNoteName))))
            .andExpect(content().string(not(containsString(privCommentContent))))
            .andExpect(content().string(not(containsString(deletedPrivNoteName))))

        // 5. GET /api/materials for User B:
        mockMvc.perform(get("/api/materials").with(user(userB.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.name == '$pubNoteName')]").exists())
            .andExpect(jsonPath("$.content[?(@.name == '$privNoteName')]").doesNotExist())
            .andExpect(jsonPath("$.content[?(@.name == '$deletedPrivNoteName')]").doesNotExist())
    }

    @Test
    fun `user B modifying user A public note, collection, or choreography returns 403 Forbidden`() {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)

        // User A creates public note, collection, and choreography
        val note = asUser(userA) {
            materialService.create(MaterialRequest(
                name = "A Public Note $uniqueSuffix",
                danceTypeId = testDanceType.id,
                isPublic = true,
                version = 0
            ))
        }

        val list = asUser(userA) {
            customListService.create(CustomListRequest(
                name = "A Public List $uniqueSuffix",
                isPublic = true
            ))
        }

        val choreo = asUser(userA) {
            choreographyService.create(ChoreographyRequest(
                name = "A Public Choreo $uniqueSuffix",
                danceTypeId = testDanceType.id,
                isPublic = true
            ))
        }

        // Web POST note update by User B -> 403
        mockMvc.perform(
            post("/materials/${note.id}")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("name", "B Hacked Note")
                .param("version", "0")
        ).andExpect(status().isForbidden)

        // Web POST note delete by User B -> 403
        mockMvc.perform(
            post("/materials/${note.id}/delete")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isForbidden)

        // Web POST add figure to note by User B -> 403
        mockMvc.perform(
            post("/materials/${note.id}/figures")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("danceFigureId", testDanceFigure.id.toString())
                .param("startTime", "0")
                .param("endTime", "10")
        ).andExpect(status().isForbidden)

        // Web POST collection update by User B -> 403
        mockMvc.perform(
            post("/lists/${list.id}")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("name", "B Hacked List")
        ).andExpect(status().isForbidden)

        // Web POST collection delete by User B -> 403
        mockMvc.perform(
            post("/lists/${list.id}/delete")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isForbidden)

        // Web POST choreography update by User B -> 403
        mockMvc.perform(
            post("/choreographies/${choreo.id}/metadata")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .param("name", "B Hacked Choreo")
                .param("danceTypeId", testDanceType.id.toString())
        ).andExpect(status().isForbidden)

        // Web POST choreography delete by User B -> 403
        mockMvc.perform(
            post("/choreographies/${choreo.id}/delete")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isForbidden)

        // API PUT note update by User B -> 403
        mockMvc.perform(
            put("/api/materials/${note.id}")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"B Hacked Note","version":0}""")
        ).andExpect(status().isForbidden)

        // API DELETE note by User B -> 403
        mockMvc.perform(
            delete("/api/materials/${note.id}")
                .with(user(userB.username).roles("USER"))
                .with(csrf())
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `api update without isPublic preserves existing public visibility`() {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)

        // User A creates a public note
        val note = asUser(userA) {
            materialService.create(MaterialRequest(
                name = "Original Public Note $uniqueSuffix",
                danceTypeId = testDanceType.id,
                isPublic = true,
                version = 0
            ))
        }

        // User A updates via API without specifying isPublic
        mockMvc.perform(
            put("/api/materials/${note.id}")
                .with(user(userA.username).roles("USER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Updated Public Note $uniqueSuffix","version":0}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.isPublic").value(true))
            .andExpect(jsonPath("$.visibility").value("PUBLIC"))

        // Verify entity in repository still has Visibility.PUBLIC
        val updatedNote = materialRepository.findById(note.id!!).get()
        assertEquals(Visibility.PUBLIC, updatedNote.visibility)
        assertTrue(updatedNote.isPublic)
    }

    @Test
    fun `streaming video does not hold database connection while stream is being read`() {
        val note = materialRepository.save(Material().apply {
            name = "Stream Connection Note"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            driveFileId = "stream-conn-video"
            version = 0
            updatedAt = LocalDateTime.now()
        })
        val noteId = note.id!!
        uploadedFileRepository.save(UploadedFile("stream-conn-video", userA, LocalDateTime.now()))

        val hikariDs = dataSource as com.zaxxer.hikari.HikariDataSource
        val activeConnectionsDuringStream = java.util.concurrent.atomic.AtomicInteger(-1)

        val stubbedStream = object : java.io.InputStream() {
            private val delegate = java.io.ByteArrayInputStream("streamed-video-bytes".toByteArray())

            override fun read(): Int {
                activeConnectionsDuringStream.set(hikariDs.hikariPoolMXBean.activeConnections)
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                activeConnectionsDuringStream.set(hikariDs.hikariPoolMXBean.activeConnections)
                return delegate.read(b, off, len)
            }
        }

        `when`(googleDriveService.downloadMedia("stream-conn-video", null)).thenReturn(
            GoogleDriveService.DriveMediaDownload(
                statusCode = 200,
                contentType = "video/mp4",
                contentLength = 20L,
                contentRange = null,
                stream = stubbedStream
            )
        )

        mockMvc.perform(get("/materials/$noteId/video").with(user(userA.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(header().string("Accept-Ranges", "bytes"))

        assertEquals(0, activeConnectionsDuringStream.get(), "Hikari pool should have 0 active connections while streaming video")
    }

    @Test
    fun `streaming video with range beyond file end returns 416 with Content-Range`() {
        val note = materialRepository.save(Material().apply {
            name = "Range 416 Note"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            driveFileId = "stream-416-video"
            version = 0
            updatedAt = LocalDateTime.now()
        })
        val noteId = note.id!!
        uploadedFileRepository.save(UploadedFile("stream-416-video", userA, LocalDateTime.now()))

        `when`(googleDriveService.downloadMedia("stream-416-video", "bytes=99999-")).thenReturn(
            GoogleDriveService.DriveMediaDownload(
                statusCode = 416,
                contentType = null,
                contentLength = 0L,
                contentRange = "bytes */100",
                stream = java.io.ByteArrayInputStream(ByteArray(0))
            )
        )

        mockMvc.perform(
            get("/materials/$noteId/video")
                .with(user(userA.username).roles("USER"))
                .header("Range", "bytes=99999-")
        )
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Content-Range", "bytes */100"))
    }
}
