package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [
        DanceFigureWebController::class,
        TrainingEventWebController::class,
        AdminController::class
    ],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
        OAuth2ClientWebSecurityAutoConfiguration::class
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [SecurityConfig::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    RichTextServiceImpl::class,
    LocalizationWebTest.SecurityTestConfig::class
)
class LocalizationWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    // AdminController collaborators
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var materialRepository: MaterialRepository
    @MockitoBean private lateinit var storageCleanupLogRepository: StorageCleanupLogRepository
    @MockitoBean private lateinit var storageCleanupJob: StorageCleanupJob
    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var fileStorageService: FileStorageService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var syllabusImporterService: SyllabusImporterService
    @MockitoBean private lateinit var knowledgeIndexService: KnowledgeIndexService
    @MockitoBean private lateinit var googleCalendarClient: GoogleCalendarClient

    // DanceFigureWebController collaborators
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService

    // TrainingEventWebController collaborators
    @MockitoBean private lateinit var trainingEventService: TrainingEventService
    @MockitoBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var materialService: MaterialService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val polishLocale = Locale.forLanguageTag("pl")
    private val englishLocale = Locale.ENGLISH
    private lateinit var adminUser: AppUser

    @BeforeEach
    fun setUp() {
        adminUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Administrator"
            email = "admin@example.com"
            role = Role.ADMIN
            locale = null
        }
        val auth = UsernamePasswordAuthenticationToken(
            adminUser, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
        )
        SecurityContextHolder.getContext().authentication = auth

        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
        `when`(activeCalendarService.selectable()).thenReturn(emptyList())
        `when`(activeCalendarService.active()).thenReturn(null)
    }

    @Test
    fun `dance class enum labels render in English when locale is English`() {
        mockMvc.perform(get("/dance-figures/new").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Class E")))
            .andExpect(content().string(containsString("Class A")))
            .andExpect(content().string(containsString("Class S")))
            .andExpect(content().string(not(containsString("Klasa A"))))
            .andExpect(content().string(not(containsString("Klasa S"))))
    }

    @Test
    fun `dance class enum labels render in Polish when locale is Polish`() {
        mockMvc.perform(get("/dance-figures/new").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Klasa E")))
            .andExpect(content().string(containsString("Klasa A")))
            .andExpect(content().string(containsString("Klasa S")))
            .andExpect(content().string(not(containsString("Class A"))))
            .andExpect(content().string(not(containsString("Class S"))))
    }

    @Test
    fun `training event type and attendance enum labels render in English when locale is English`() {
        mockMvc.perform(get("/training-events/new").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Workshop")))
            .andExpect(content().string(containsString("Competition")))
            .andExpect(content().string(containsString("Planned")))
            .andExpect(content().string(not(containsString("Warsztaty"))))
            .andExpect(content().string(not(containsString("Turniej"))))
    }

    @Test
    fun `training event type and attendance enum labels render in Polish when locale is Polish`() {
        mockMvc.perform(get("/training-events/new").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Warsztaty")))
            .andExpect(content().string(containsString("Turniej")))
            .andExpect(content().string(containsString("Zaplanowany")))
            .andExpect(content().string(not(containsString(">Workshop<"))))
            .andExpect(content().string(not(containsString(">Competition<"))))
    }

    @Test
    fun `date format renders according to locale on training event view`() {
        val eventId = UUID.randomUUID()
        val event = TrainingEvent().apply {
            id = eventId
            title = "Test practice"
            startTime = LocalDateTime.of(2026, 9, 10, 18, 0)
            endTime = LocalDateTime.of(2026, 9, 10, 20, 0)
            eventType = TrainingEventType.TRAINING
            createdBy = adminUser
            calendar = TrainingCalendar().apply {
                id = UUID.randomUUID()
                owner = adminUser
            }
        }
        `when`(trainingEventService.findById(eventId)).thenReturn(event)

        // English: September and Thursday
        mockMvc.perform(get("/training-events/$eventId").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("September")))
            .andExpect(content().string(containsString("Thursday")))

        // Polish: września and czwartek
        mockMvc.perform(get("/training-events/$eventId").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("września")))
            .andExpect(content().string(containsString("czwartek")))
    }

    @Test
    fun `storage numbers format with decimal dot in English and decimal comma in Polish`() {
        val dummyFile = GoogleDriveService.DriveFileInfo(
            id = "file-123",
            name = "video.mp4",
            size = 2621440L, // 2.5 MB
            createdTime = 0L
        )
        `when`(googleDriveService.listFilesInFolder()).thenReturn(listOf(dummyFile))
        `when`(materialRepository.findAllDriveFileIds()).thenReturn(setOf("file-123"))

        // English: 2.50 MB
        mockMvc.perform(get("/admin/storage/files").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("2.50 MB")))

        // Polish: 2,50 MB
        mockMvc.perform(get("/admin/storage/files").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("2,50 MB")))
    }

    @Test
    fun `bean validation message renders in English when locale is English`() {
        mockMvc.perform(
            post("/admin/users")
                .with(csrf())
                .locale(englishLocale)
                .param("username", "")
                .param("email", "test@example.com")
                .param("displayName", "Test User")
                .param("password", "secret123")
                .param("role", "USER")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Username cannot be empty")))
            .andExpect(content().string(not(containsString("Nazwa użytkownika nie może być pusta"))))
    }

    @Test
    fun `bean validation message renders in Polish when locale is Polish`() {
        mockMvc.perform(
            post("/admin/users")
                .with(csrf())
                .locale(polishLocale)
                .param("username", "")
                .param("email", "test@example.com")
                .param("displayName", "Test User")
                .param("password", "secret123")
                .param("role", "USER")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nazwa użytkownika nie może być pusta")))
            .andExpect(content().string(not(containsString("Username cannot be blank"))))
    }
}
