package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.*
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class FormValidationWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository

    @MockBean private lateinit var googleDriveService: GoogleDriveService
    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var adminUser: AppUser
    private lateinit var category: DanceCategory
    private lateinit var danceType: DanceType
    private lateinit var choreography: Choreography
    private lateinit var choreoEntry: ChoreographyEntry

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        adminUser = appUserRepository.findByUsername("validation-admin") ?: appUserRepository.save(AppUser().apply {
            username = "validation-admin"
            email = "val-admin@example.com"
            displayName = "Validation Admin"
            password = "password123"
            role = Role.ADMIN
        })

        category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Validation Category ${UUID.randomUUID()}"
            predefined = true
        })

        danceType = danceTypeRepository.save(DanceType().apply {
            name = "Validation Type ${UUID.randomUUID()}"
            this.category = this@FormValidationWebTest.category
        })

        val savedChoreo = Choreography().apply {
            name = "Validation Choreo ${UUID.randomUUID()}"
            danceType = this@FormValidationWebTest.danceType
            owner = adminUser
        }
        val entry = ChoreographyEntry().apply {
            this.choreography = savedChoreo
            sortOrder = 1
            entryType = EntryType.SECTION_LABEL
            sectionLabel = "Opening"
        }
        savedChoreo.entries.add(entry)
        choreography = choreographyRepository.save(savedChoreo)
        choreoEntry = choreography.entries.first()
    }

    @Test
    fun `dance category form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/dance-categories")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `dance type form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/dance-types")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
                .param("categoryId", category.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `material form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/materials")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
                .param("version", "0")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `dance figure form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/dance-figures")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
                .param("danceTypeId", danceType.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `custom list form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/lists")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `choreography form rejects empty name and shows error summary`() {
        mockMvc.perform(
            post("/choreographies")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "")
                .param("danceTypeId", danceType.id.toString())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `choreography form rejects missing dance style and shows error summary`() {
        mockMvc.perform(
            post("/choreographies")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("name", "Valid Choreo Name")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `choreography entry rejects notes over 500 characters on create`() {
        val longNotes = "a".repeat(501)
        mockMvc.perform(
            post("/choreographies/${choreography.id}/entries")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("entryType", "SECTION_LABEL")
                .param("notes", longNotes)
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("500")))
    }

    @Test
    fun `choreography entry rejects notes over 500 characters on update`() {
        val longNotes = "a".repeat(501)
        mockMvc.perform(
            post("/choreographies/${choreography.id}/entries/${choreoEntry.id}")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("notes", longNotes)
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("500")))
    }

    @Test
    fun `training event form rejects empty title and shows error summary`() {
        mockMvc.perform(
            post("/training-events")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("title", "")
                .param("date", "2026-03-21")
                .param("startTime", "18:00")
                .param("endTime", "19:00")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `admin user create validation failure renders inside page without 400 Whitelabel`() {
        mockMvc.perform(
            post("/admin/users")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("username", "")
                .param("password", "abc")
                .param("confirmPassword", "abc")
        )
            .andExpect(status().isOk)
            .andExpect(model().attributeExists("createUserError"))
            .andExpect(content().string(containsString("role=\"alert\"")))
    }

    @Test
    fun `admin user edit validation failure renders inside page without 400 Whitelabel`() {
        mockMvc.perform(
            post("/admin/users/${adminUser.id}/edit")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("username", "")
        )
            .andExpect(status().isOk)
            .andExpect(model().attributeExists("updateUserError"))
            .andExpect(content().string(containsString("role=\"alert\"")))
    }

    @Test
    fun `admin user edit with omitted role fails validation and does not demote user`() {
        val originalRole = adminUser.role
        mockMvc.perform(
            post("/admin/users/${adminUser.id}/edit")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN"))
                .param("username", adminUser.username)
                .param("email", adminUser.email)
                .param("displayName", adminUser.displayName)
                // role is intentionally omitted
        )
            .andExpect(status().isOk)
            .andExpect(model().attributeExists("updateUserError"))
            .andExpect(content().string(containsString("role=\"alert\"")))

        val freshUser = appUserRepository.findById(adminUser.id!!).get()
        assertEquals(originalRole, freshUser.role)
    }

    @Test
    fun `profile change password rejects invalid input and shows error summary`() {
        mockMvc.perform(
            post("/profile/password")
                .with(csrf())
                .with(user(adminUser.username).roles("ADMIN", "USER"))
                .param("currentPassword", "")
                .param("newPassword", "")
                .param("confirmPassword", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"errorSummary\"")))
            .andExpect(content().string(containsString("border-error")))
    }

    @Test
    fun `no form screen renders duplicate field names`() {
        val formEndpoints = listOf(
            "/materials/new",
            "/dance-figures/new",
            "/dance-categories/new",
            "/dance-types/new",
            "/lists/new",
            "/choreographies/new",
            "/training-events/new",
            "/profile"
        )

        for (endpoint in formEndpoints) {
            val result = mockMvc.perform(
                get(endpoint)
                    .with(csrf())
                    .with(user(adminUser.username).roles("ADMIN", "USER"))
            ).andExpect(status().isOk).andReturn()

            val html = result.response.contentAsString
            val errors = findDuplicateFormFieldErrors(html, endpoint)
            assertTrue(
                errors.isEmpty(),
                "Found duplicate field names in $endpoint:\n" + errors.joinToString("\n")
            )
        }
    }

    private fun findDuplicateFormFieldErrors(html: String, uri: String): List<String> {
        val formRegex = Regex("""<form\b[^>]*>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val elementRegex = Regex("""<(input|select|textarea)\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val typeRegex = Regex("""\btype\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val disabledRegex = Regex("""\bdisabled\b""", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

        val errors = mutableListOf<String>()

        for ((formIndex, formMatch) in formRegex.findAll(html).withIndex()) {
            val formContent = formMatch.groupValues[1]
            val fieldNames = mutableListOf<String>()
            val checkboxNamesAndValues = mutableSetOf<Pair<String, String>>()

            for (elMatch in elementRegex.findAll(formContent)) {
                val tag = elMatch.groupValues[1].lowercase()
                val attrs = elMatch.groupValues[2]

                // Disabled elements are not submittable and do not post form state
                if (disabledRegex.containsMatchIn(attrs)) continue

                val type = typeRegex.find(attrs)?.groupValues?.get(1)?.lowercase() ?: if (tag == "input") "text" else ""

                // Submit/button elements do not post form state; radio buttons share name by design
                if (type == "submit" || type == "button" || type == "reset" || type == "radio") continue

                val name = nameRegex.find(attrs)?.groupValues?.get(1)?.trim()
                if (name.isNullOrEmpty() || name == "_csrf") continue

                if (type == "checkbox") {
                    val value = valueRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    // Multiple checkboxes sharing the same name must have distinct values (multi-select)
                    if (!checkboxNamesAndValues.add(name to value)) {
                        fieldNames.add(name)
                    }
                } else {
                    fieldNames.add(name)
                }
            }

            val duplicates = fieldNames.groupingBy { it }.eachCount().filter { it.value > 1 }
            if (duplicates.isNotEmpty()) {
                errors.add("Form #$formIndex in $uri has duplicate field names: $duplicates")
            }
        }

        return errors
    }
}
