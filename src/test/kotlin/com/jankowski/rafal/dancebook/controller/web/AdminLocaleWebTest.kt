package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.dto.AdminDriveFileDto
import com.jankowski.rafal.dancebook.dto.AdminOverview
import com.jankowski.rafal.dancebook.dto.AdminUsersView
import com.jankowski.rafal.dancebook.dto.DriveInspection
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [AdminController::class, AdminCalendarController::class],
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
    AdminLocaleWebTest.SecurityTestConfig::class
)
class AdminLocaleWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var adminOverviewService: AdminOverviewService
    @MockitoBean private lateinit var adminStorageService: AdminStorageService
    @MockitoBean private lateinit var storageCleanupJob: StorageCleanupJob
    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var fileStorageService: FileStorageService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var syllabusImporterService: SyllabusImporterService
    @MockitoBean private lateinit var knowledgeIndexService: KnowledgeIndexService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var googleCalendarClient: GoogleCalendarClient

    // NavbarAdvice dependencies
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
        `when`(systemSettingService.getIntSetting("polling_interval_minutes", 5)).thenReturn(5)
        `when`(systemSettingService.getIntSetting("auto_logout_minutes", 10)).thenReturn(15)
        `when`(adminOverviewService.overview(adminUser)).thenReturn(
            AdminOverview(
                userCount = 10L,
                figureCount = 374L,
                noteCount = 25L,
                calendarCount = 0,
                defaultCalendarName = null,
                danceTypeCount = 11,
                categoryCount = 3,
                categoryNames = listOf("Standard", "Latin", "Social"),
                pollInterval = 5,
                autoLogout = 15,
                knowledgeStatus = null,
                knowledgeIndexStale = false,
                lastCleanupAt = null
            )
        )
        `when`(adminOverviewService.users(false, 5)).thenReturn(AdminUsersView(listOf(adminUser), 1L, 1L, false))
        `when`(adminOverviewService.users(true, 5)).thenReturn(AdminUsersView(listOf(adminUser), 1L, 1L, true))
        `when`(trainingCalendarService.findAll()).thenReturn(emptyList())
    }

    @Test
    fun `admin dashboard renders in Polish when locale is Polish`() {
        mockMvc.perform(get("/admin").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            // Page header and summary panel
            .andExpect(content().string(containsString("Administracja")))
            .andExpect(content().string(containsString("Zarządzaj DanceBook: ludzie, treści, wyszukiwanie i magazyn.")))
            .andExpect(content().string(containsString("kalendarze treningowe")))
            .andExpect(content().string(containsString("Wymaga uwagi")))
            .andExpect(content().string(containsString("Nic nie wymaga uwagi")))
            // Groups and row titles
            .andExpect(content().string(containsString("Wyszukiwanie i magazyn")))
            .andExpect(content().string(containsString("Style taneczne")))
            .andExpect(content().string(containsString("Import sylabusa")))
            .andExpect(content().string(containsString("Domyślne obrazy")))
            .andExpect(content().string(containsString("Indeks wiedzy")))
            .andExpect(content().string(containsString("Dysk Google")))
            .andExpect(content().string(containsString("Ustawienia aplikacji")))
            .andExpect(content().string(containsString("Powiadomienia co 5 min")))
            .andExpect(content().string(containsString("Historia zadań czyszczenia")))
            // Buttons and labels inside the rows
            .andExpect(content().string(containsString("Zapisz konfigurację")))
            .andExpect(content().string(containsString("Importuj figury i kroki")))
            .andExpect(content().string(containsString("Odbuduj indeks wiedzy")))
            .andExpect(content().string(containsString("Symulacja czyszczenia")))
            .andExpect(content().string(containsString("Uruchom usuwanie")))
            .andExpect(content().string(containsString("Wyczyść uprawnienia publiczne")))
            .andExpect(content().string(containsString("Dodaj kalendarz")))
            .andExpect(content().string(containsString("Utwórz konto")))
            // Table headers
            .andExpect(content().string(containsString("Użytkownik")))
            .andExpect(content().string(containsString("Dołączył(a)")))
            .andExpect(content().string(containsString("Akcje")))
            // Absence of English equivalents
            .andExpect(content().string(not(containsString(">Admin<"))))
            .andExpect(content().string(not(containsString("Needs attention"))))
            .andExpect(content().string(not(containsString(">Knowledge index<"))))
            .andExpect(content().string(not(containsString(">Application settings<"))))
            .andExpect(content().string(not(containsString("Save Configuration"))))
            .andExpect(content().string(not(containsString("Import Figures &amp; Steps"))))
            .andExpect(content().string(not(containsString("Rebuild Knowledge Index"))))
            .andExpect(content().string(not(containsString("Simulate Cleanup"))))
            .andExpect(content().string(not(containsString("Run Deletion"))))
            .andExpect(content().string(not(containsString("Clean Public Permissions"))))
            .andExpect(content().string(not(containsString("Create Account"))))
    }

    @Test
    fun `admin dashboard renders in English when locale is English`() {
        mockMvc.perform(get("/admin").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Run DanceBook: people, content, search and storage.")))
            .andExpect(content().string(containsString("training calendars")))
            .andExpect(content().string(containsString("Needs attention")))
            .andExpect(content().string(containsString("Nothing needs attention")))
            .andExpect(content().string(containsString("Search and storage")))
            .andExpect(content().string(containsString("Dance styles")))
            .andExpect(content().string(containsString("Syllabus import")))
            .andExpect(content().string(containsString("Default images")))
            .andExpect(content().string(containsString("Knowledge index")))
            .andExpect(content().string(containsString("Google Drive")))
            .andExpect(content().string(containsString("Application settings")))
            .andExpect(content().string(containsString("Notifications every 5 min")))
            .andExpect(content().string(containsString("Save Configuration")))
            .andExpect(content().string(containsString("Import Figures &amp; Steps")))
            .andExpect(content().string(containsString("Rebuild Knowledge Index")))
            .andExpect(content().string(containsString("Simulate Cleanup")))
            .andExpect(content().string(containsString("Run Deletion")))
            .andExpect(content().string(containsString("Clean Public Permissions")))
            .andExpect(content().string(containsString("Create Account")))
    }

    @Test
    fun `user create form fragment renders in Polish`() {
        mockMvc.perform(get("/admin/users/form").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nazwa użytkownika")))
            .andExpect(content().string(containsString("Nazwa wyświetlana")))
            .andExpect(content().string(containsString("Rola")))
            .andExpect(content().string(containsString("Początkowe hasło (min. 8 znaków)")))
            .andExpect(content().string(containsString("Zapisz konto")))
            .andExpect(content().string(containsString("Anuluj")))
            .andExpect(content().string(not(containsString("Save Account"))))
            .andExpect(content().string(not(containsString("Initial Password"))))
    }

    @Test
    fun `user create form fragment renders in English`() {
        mockMvc.perform(get("/admin/users/form").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Username")))
            .andExpect(content().string(containsString("Display Name")))
            .andExpect(content().string(containsString("Role")))
            .andExpect(content().string(containsString("Initial Password (min 8 characters)")))
            .andExpect(content().string(containsString("Save Account")))
            .andExpect(content().string(containsString("Cancel")))
    }

    @Test
    fun `calendars section fragment renders in Polish`() {
        mockMvc.perform(get("/admin/calendars").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Brak skonfigurowanych kalendarzy. Dodaj kalendarz, aby rozpocząć tworzenie treningów.")))
            .andExpect(content().string(not(containsString("No calendars configured. Add one to start creating sessions."))))
    }

    @Test
    fun `calendars section fragment renders in English`() {
        mockMvc.perform(get("/admin/calendars").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No calendars configured. Add one to start creating sessions.")))
    }

    @Test
    fun `add calendar form fragment renders in Polish`() {
        mockMvc.perform(get("/admin/calendars/form").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nazwa wyświetlana")))
            .andExpect(content().string(containsString("ID Kalendarza Google")))
            .andExpect(content().string(containsString("Dodaj kalendarz")))
            .andExpect(content().string(containsString("np. Kalendarz treningowy")))
            .andExpect(content().string(not(containsString("Add Calendar"))))
    }

    @Test
    fun `add calendar form fragment renders in English`() {
        mockMvc.perform(get("/admin/calendars/form").with(csrf()).locale(englishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Display Name")))
            .andExpect(content().string(containsString("Google Calendar ID")))
            .andExpect(content().string(containsString("Add Calendar")))
            .andExpect(content().string(containsString("e.g. Training Calendar")))
    }

    @Test
    fun `storage driveSection fragment renders in Polish`() {
        val testFile = GoogleDriveService.DriveFileInfo(
            id = "file-123",
            name = "dance_clip.mp4",
            size = 10485760L,
            createdTime = 1600000000000L
        )
        `when`(adminStorageService.inspectDrive()).thenReturn(
            DriveInspection(
                files = listOf(AdminDriveFileDto(testFile.id, testFile.name, testFile.size, testFile.createdTime, true)),
                orphanedSize = 10485760L,
                linkedSize = 0L,
                orphanedCount = 1,
                linkedCount = 0
            )
        )

        mockMvc.perform(get("/admin/storage/files").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Całkowita przestrzeń w chmurze")))
            .andExpect(content().string(containsString("Powiązane notatki")))
            .andExpect(content().string(containsString("Osierocone pliki")))
            .andExpect(content().string(containsString("Nazwa pliku")))
            .andExpect(content().string(containsString("Rozmiar")))
            .andExpect(content().string(containsString("Status")))
            .andExpect(content().string(containsString("Osierocony")))
            .andExpect(content().string(not(containsString("Total Cloud Space"))))
            .andExpect(content().string(not(containsString("Linked Notes"))))
            .andExpect(content().string(not(containsString("Orphaned Waste"))))
    }
}
