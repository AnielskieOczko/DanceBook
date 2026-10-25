package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class CustomListFormIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var customListRepository: CustomListRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var transactionTemplate: org.springframework.transaction.support.TransactionTemplate

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var testCategory: DanceCategory
    private lateinit var testDanceType: DanceType

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        userA = appUserRepository.findByUsername("userA-form-test") ?: appUserRepository.save(AppUser().apply {
            username = "userA-form-test"
            email = "usera-form@example.com"
            displayName = "User A Form"
            password = "password123"
            role = Role.USER
        })

        userB = appUserRepository.findByUsername("userB-form-test") ?: appUserRepository.save(AppUser().apply {
            username = "userB-form-test"
            email = "userb-form@example.com"
            displayName = "User B Form"
            password = "password123"
            role = Role.USER
        })

        testCategory = danceCategoryRepository.save(DanceCategory().apply {
            name = "Form Test Category ${UUID.randomUUID()}"
            predefined = false
        })

        testDanceType = danceTypeRepository.save(DanceType().apply {
            name = "Form Test Waltz ${UUID.randomUUID()}"
            category = testCategory
        })
    }

    @Test
    fun `preview endpoint returns matching notes and count for rules`() {
        materialRepository.save(Material().apply {
            name = "Waltz Reverse Turn Note"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            rating = 4
            version = 0
            updatedAt = LocalDateTime.now()
        })
        materialRepository.save(Material().apply {
            name = "Waltz Natural Turn Note"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            rating = 3
            version = 0
            updatedAt = LocalDateTime.now()
        })

        mockMvc.perform(
            get("/lists/preview")
                .with(user(userA.username).roles("USER"))
                .param("nameFilter", "Turn")
                .param("danceTypeIds", testDanceType.id.toString())
                .param("minRating", "3")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"desktop-preview-container\"")))
            .andExpect(content().string(containsString("id=\"mobile-preview-container\"")))
            .andExpect(content().string(containsString("2")))
            .andExpect(content().string(containsString("notes match")))
            .andExpect(content().string(containsString("Waltz Reverse Turn Note")))
            .andExpect(content().string(containsString("Waltz Natural Turn Note")))
    }

    @Test
    fun `preview endpoint returns same notes as collection view for same rules`() {
        val note1 = materialRepository.save(Material().apply {
            name = "Shared Filter Note 1"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            rating = 4
            version = 0
            updatedAt = LocalDateTime.now()
        })
        val note2 = materialRepository.save(Material().apply {
            name = "Shared Filter Note 2"
            owner = userA
            visibility = Visibility.PUBLIC
            danceType = testDanceType
            rating = 5
            version = 0
            updatedAt = LocalDateTime.now()
        })
        materialRepository.save(Material().apply {
            name = "Irrelevant Note Other"
            owner = userA
            visibility = Visibility.PUBLIC
            rating = 1
            version = 0
            updatedAt = LocalDateTime.now()
        })

        val list = customListRepository.save(CustomList().apply {
            name = "Saved Collection Matching Filter"
            owner = userA
            nameFilter = "Shared Filter"
            minRating = 4
            danceTypes.add(testDanceType)
            visibility = Visibility.PUBLIC
        })

        // Collection view
        val viewResult = mockMvc.perform(
            get("/lists/${list.id}")
                .with(user(userA.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Shared Filter Note 1")))
            .andExpect(content().string(containsString("Shared Filter Note 2")))
            .andExpect(content().string(not(containsString("Irrelevant Note Other"))))
            .andReturn().response.contentAsString

        // Preview endpoint with identical rules
        val previewResult = mockMvc.perform(
            get("/lists/preview")
                .with(user(userA.username).roles("USER"))
                .param("nameFilter", list.nameFilter)
                .param("minRating", list.minRating.toString())
                .param("danceTypeIds", testDanceType.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Shared Filter Note 1")))
            .andExpect(content().string(containsString("Shared Filter Note 2")))
            .andExpect(content().string(not(containsString("Irrelevant Note Other"))))
            .andReturn().response.contentAsString

        assertTrue(viewResult.contains(note1.name))
        assertTrue(viewResult.contains(note2.name))
        assertTrue(previewResult.contains(note1.name))
        assertTrue(previewResult.contains(note2.name))
    }

    @Test
    fun `preview endpoint handles zero-match case with explanatory message`() {
        mockMvc.perform(
            get("/lists/preview")
                .with(user(userA.username).roles("USER"))
                .param("nameFilter", "DefinitelyDoesNotMatchAnything123456")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("0")))
            .andExpect(content().string(containsString("No notes match yet")))
            .andExpect(content().string(containsString("You can still save it")))
    }

    @Test
    fun `another user's private note is excluded from preview`() {
        materialRepository.save(Material().apply {
            name = "User B Secret Private Note"
            owner = userB
            visibility = Visibility.PRIVATE
            danceType = testDanceType
            rating = 5
            version = 0
            updatedAt = LocalDateTime.now()
        })

        mockMvc.perform(
            get("/lists/preview")
                .with(user(userA.username).roles("USER"))
                .param("nameFilter", "Secret Private Note")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("User B Secret Private Note"))))
            .andExpect(content().string(containsString("No notes match yet")))
    }

    @Test
    fun `saving New collection with every field succeeds`() {
        val imageFile = MockMultipartFile(
            "image",
            "cover-test.png",
            "image/png",
            "test-image-content".toByteArray()
        )

        val result = mockMvc.perform(
            multipart("/lists")
                .file(imageFile)
                .with(csrf())
                .with(user(userA.username).roles("USER"))
                .param("name", "Full Collection Test")
                .param("nameFilter", "heel")
                .param("minRating", "3")
                .param("danceCategoryIds", testCategory.id.toString())
                .param("danceTypeIds", testDanceType.id.toString())
                .param("isPublic", "true")
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val redirectedUrl = result.response.redirectedUrl
        assertNotNull(redirectedUrl)
        val createdId = UUID.fromString(redirectedUrl!!.removePrefix("/lists/"))

        transactionTemplate.execute {
            val created = customListRepository.findById(createdId).orElseThrow()
            assertEquals("Full Collection Test", created.name)
            assertEquals("heel", created.nameFilter)
            assertEquals(3.toShort(), created.minRating)
            assertTrue(created.isPublic)
            assertNotNull(created.imageFilename)
            assertTrue(created.danceCategories.any { it.id == testCategory.id })
            assertTrue(created.danceTypes.any { it.id == testDanceType.id })
            assertEquals(userA.id, created.owner?.id)
        }
    }

    @Test
    fun `saving Edit collection with every field and removeImage clears image`() {
        val list = customListRepository.save(CustomList().apply {
            name = "Original Name"
            owner = userA
            nameFilter = "original"
            minRating = 2
            isPublic = false
            imageFilename = "original-cover.png"
            danceTypes.add(testDanceType)
            danceCategories.add(testCategory)
        })

        mockMvc.perform(
            post("/lists/${list.id}")
                .with(csrf())
                .with(user(userA.username).roles("USER"))
                .param("name", "Updated Name")
                .param("nameFilter", "updated")
                .param("minRating", "4")
                .param("isPublic", "true")
                .param("removeImage", "true")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/lists/${list.id}"))

        val updated = customListRepository.findById(list.id!!).orElseThrow()
        assertEquals("Updated Name", updated.name)
        assertEquals("updated", updated.nameFilter)
        assertEquals(4.toShort(), updated.minRating)
        assertTrue(updated.isPublic)
        assertNull(updated.imageFilename)
    }

    @Test
    fun `validation rejects invalid file format and oversized file`() {
        val textFile = MockMultipartFile(
            "image",
            "notes.txt",
            "text/plain",
            "some-text".toByteArray()
        )

        mockMvc.perform(
            multipart("/lists")
                .file(textFile)
                .with(csrf())
                .with(user(userA.username).roles("USER"))
                .param("name", "Valid Name")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("Only PNG, JPG or GIF images are supported.")))

        val largeFile = MockMultipartFile(
            "image",
            "huge.png",
            "image/png",
            ByteArray(10 * 1024 * 1024 + 10)
        )

        mockMvc.perform(
            multipart("/lists")
                .file(largeFile)
                .with(csrf())
                .with(user(userA.username).roles("USER"))
                .param("name", "Valid Name")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("The file is too large. Choose an image under 10 MB.")))
    }

    @Test
    fun `GET new collection form renders back to Collections and correct cover buttons`() {
        mockMvc.perform(
            get("/lists/new")
                .with(csrf())
                .with(user(userA.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Collections")))
            .andExpect(content().string(containsString("No cover yet")))
            .andExpect(content().string(containsString("id=\"coverChooseBtn\"")))
            .andExpect(content().string(containsString("id=\"coverChangeBtn\" class=\"btn-outline btn-sm min-h-[38px] cursor-pointer inline-flex items-center hidden!\"")))
            .andExpect(content().string(containsString("id=\"coverRemoveBtn\" class=\"text-primary text-sm font-semibold hover:underline px-2 cursor-pointer inline-flex items-center min-h-[38px] hidden!\"")))
    }

    @Test
    fun `GET edit collection form renders collection name in back link and correct cover buttons`() {
        val list = customListRepository.save(CustomList().apply {
            name = "Special Waltz Notes"
            owner = userA
            imageFilename = "cover-waltz.jpg"
        })

        mockMvc.perform(
            get("/lists/${list.id}/edit")
                .with(csrf())
                .with(user(userA.username).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Special Waltz Notes")))
            .andExpect(content().string(containsString("cover-waltz.jpg")))
            .andExpect(content().string(containsString("id=\"coverChooseBtn\" class=\"btn-outline btn-sm min-h-[38px] cursor-pointer inline-flex items-center hidden!\"")))
            .andExpect(content().string(containsString("id=\"coverChangeBtn\" class=\"btn-outline btn-sm min-h-[38px] cursor-pointer inline-flex items-center\"")))
            .andExpect(content().string(containsString("id=\"coverRemoveBtn\" class=\"text-primary text-sm font-semibold hover:underline px-2 cursor-pointer inline-flex items-center min-h-[38px]\"")))
    }

    @Test
    fun `GET preview renders star rating badge when note has rating`() {
        materialRepository.save(Material().apply {
            name = "Rated Note Preview"
            owner = userA
            visibility = Visibility.PUBLIC
            rating = 5.toShort()
            createdAt = LocalDateTime.now()
        })

        mockMvc.perform(
            get("/lists/preview")
                .with(csrf())
                .with(user(userA.username).roles("USER"))
                .param("nameFilter", "Rated Note Preview")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Rated Note Preview")))
            .andExpect(content().string(containsString(">5</span>")))
    }
}
