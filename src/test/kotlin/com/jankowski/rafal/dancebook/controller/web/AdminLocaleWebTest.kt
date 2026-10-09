package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingCalendar
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
        `when`(appUserRepository.count()).thenReturn(10L)
        `when`(materialRepository.count()).thenReturn(25L)
        `when`(systemSettingService.getIntSetting("polling_interval_minutes", 5)).thenReturn(5)
        `when`(systemSettingService.getIntSetting("auto_logout_minutes", 10)).thenReturn(15)
        `when`(appUserRepository.findAll()).thenReturn(listOf(adminUser))
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(emptyList())
        `when`(trainingCalendarService.findAll()).thenReturn(emptyList())
    }

    @Test
    fun `admin dashboard renders in Polish when locale is Polish`() {
        mockMvc.perform(get("/admin").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            // Page Header
            .andExpect(content().string(containsString("Panel administratora")))
            .andExpect(content().string(containsString("Przegląd systemu i konserwacja magazynu")))
            .andExpect(content().string(not(containsString("Admin Dashboard"))))
            // Stats cards
            .andExpect(content().string(containsString("Użytkownicy")))
            .andExpect(content().string(containsString("Materiały")))
            // Section headings
            .andExpect(content().string(containsString("Konfiguracja aplikacji")))
            .andExpect(content().string(containsString("Import figur z programu (syllabus)")))
            .andExpect(content().string(containsString("Indeks wiedzy (RAG)")))
            .andExpect(content().string(containsString("Ustawienia systemowe i domyślne")))
            .andExpect(content().string(containsString("Konserwacja Dysku Google")))
            .andExpect(content().string(containsString("Pliki na Dysku Google")))
            .andExpect(content().string(containsString("Kalendarze treningowe")))
            .andExpect(content().string(containsString("Zarejestrowani użytkownicy")))
            .andExpect(content().string(containsString("Historia zadań czyszczenia")))
            // Buttons and labels
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
            .andExpect(content().string(not(containsString(">Admin Dashboard<"))))
            .andExpect(content().string(not(containsString(">Application Configuration<"))))
            .andExpect(content().string(not(containsString(">Syllabus Figures Import<"))))
            .andExpect(content().string(not(containsString(">System Settings &amp; Defaults<"))))
            .andExpect(content().string(not(containsString(">Google Drive Maintenance<"))))
            .andExpect(content().string(not(containsString(">Registered Users<"))))
            .andExpect(content().string(not(containsString(">Cleanup Job History<"))))
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
            .andExpect(content().string(containsString("Admin Dashboard")))
            .andExpect(content().string(containsString("System overview and storage maintenance")))
            .andExpect(content().string(containsString("Users")))
            .andExpect(content().string(containsString("Materials")))
            .andExpect(content().string(containsString("Application Configuration")))
            .andExpect(content().string(containsString("Syllabus Figures Import")))
            .andExpect(content().string(containsString("Knowledge Index (RAG)")))
            .andExpect(content().string(containsString("System Settings &amp; Defaults")))
            .andExpect(content().string(containsString("Google Drive Maintenance")))
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
        `when`(googleDriveService.listFilesInFolder()).thenReturn(listOf(testFile))
        `when`(materialRepository.findAllDriveFileIds()).thenReturn(emptySet())

        mockMvc.perform(get("/admin/storage/files").with(csrf()).locale(polishLocale))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Całkowita przestrzeń w chmurze")))
            .andExpect(content().string(containsString("Powiązane materiały")))
            .andExpect(content().string(containsString("Osierocone pliki")))
            .andExpect(content().string(containsString("Nazwa pliku")))
            .andExpect(content().string(containsString("Rozmiar")))
            .andExpect(content().string(containsString("Status")))
            .andExpect(content().string(containsString("Osierocony")))
            .andExpect(content().string(not(containsString("Total Cloud Space"))))
            .andExpect(content().string(not(containsString("Linked Materials"))))
            .andExpect(content().string(not(containsString("Orphaned Waste"))))
    }
}
