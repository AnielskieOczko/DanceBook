package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [CustomListWebController::class],
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
    CustomListLocaleWebTest.SecurityTestConfig::class
)
class CustomListLocaleWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: com.jankowski.rafal.dancebook.service.CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: com.jankowski.rafal.dancebook.service.TrainingCalendarService

    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "dancer"
            displayName = "Dancer"
            role = Role.USER
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)
        `when`(customListService.findVisibleByCurrentUser()).thenReturn(emptyList())

        TestSecurityContextHolder.setContext(
            SecurityContextImpl(UsernamePasswordAuthenticationToken("dancer", "x", listOf(SimpleGrantedAuthority("ROLE_USER"))))
        )
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `collections empty index renders in Polish when locale is pl`() {
        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(
            get("/lists")
                .locale(Locale.forLanguageTag("pl"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Moje kolekcje")))
            .andExpect(content().string(containsString("0 skatalogowanych kolekcji")))
            .andExpect(content().string(containsString("Nowa kolekcja")))
            .andExpect(content().string(containsString("placeholder=\"Szukaj kolekcji...\"")))
            .andExpect(content().string(containsString("Filtry")))
            .andExpect(content().string(containsString("Kategorie")))
            .andExpect(content().string(containsString("Style tańca")))
            .andExpect(content().string(containsString("Sortuj według")))
            .andExpect(content().string(containsString("Nazwa (A-Z)")))
            .andExpect(content().string(containsString("Nazwa (Z-A)")))
            .andExpect(content().string(containsString("Najnowsze")))
            .andExpect(content().string(containsString("Najstarsze")))
            .andExpect(content().string(containsString("Nie znaleziono kolekcji")))
            .andExpect(content().string(containsString("Żadne kolekcje nie pasują do wybranych filtrów ani kryteriów wyszukiwania.")))
            .andExpect(content().string(containsString("Utwórz kolekcję")))
            .andExpect(content().string(not(containsString("My Collections"))))
            .andExpect(content().string(not(containsString("collections curated"))))
            .andExpect(content().string(not(containsString("No collections found"))))
            .andExpect(content().string(not(containsString("Create collection"))))
    }

    @Test
    fun `collections index with items renders card and list views in Polish when locale is pl`() {
        val cat = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
        val style = DanceType().apply { id = UUID.randomUUID(); name = "Walc"; category = cat }
        val list = CustomList().apply {
            id = UUID.randomUUID()
            name = "Moja ulubiona kolekcja"
            owner = currentUser
            isPublic = true
            minRating = 4
            danceCategories = mutableSetOf(cat)
            danceTypes = mutableSetOf(style)
            createdAt = LocalDateTime.now()
        }

        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(listOf(list))
        `when`(danceTypeService.findAll()).thenReturn(listOf(style))
        `when`(danceCategoryService.findAll()).thenReturn(listOf(cat))

        mockMvc.perform(
            get("/lists")
                .locale(Locale.forLanguageTag("pl"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("1 skatalogowanych kolekcji")))
            .andExpect(content().string(containsString("Publiczna")))
            .andExpect(content().string(containsString("4+ gwiazdek")))
            .andExpect(content().string(containsString("Zobacz Moja ulubiona kolekcja")))
            .andExpect(content().string(containsString("aria-label=\"Więcej opcji\"")))
            .andExpect(content().string(containsString("Otwórz")))
            .andExpect(content().string(containsString("Edytuj")))
            .andExpect(content().string(containsString("Usuń")))
            .andExpect(content().string(containsString("data-confirm=\"Czy na pewno chcesz usunąć tę kolekcję?\"")))
            .andExpect(content().string(not(containsString("1 collections curated"))))
            .andExpect(content().string(not(containsString("4+ stars"))))
            .andExpect(content().string(not(containsString("View Moja ulubiona kolekcja"))))
            .andExpect(content().string(not(containsString("Are you sure you want to delete this collection?"))))
    }

    @Test
    fun `collections view page renders in Polish when locale is pl`() {
        val listId = UUID.randomUUID()
        val list = CustomList().apply {
            id = listId
            name = "Kombinacje turniejowe"
            owner = currentUser
            isPublic = true
            minRating = 3
            nameFilter = "obrót"
            createdAt = LocalDateTime.now()
        }

        `when`(customListService.findById(listId)).thenReturn(list)
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged()))).thenReturn(PageImpl(emptyList()))
        `when`(materialService.hasPrivateNotesMatchingFilter(anyNonNull(currentUser), any(), any(), any(), any())).thenReturn(true)

        mockMvc.perform(
            get("/lists/$listId")
                .locale(Locale.forLanguageTag("pl"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Kolekcje")))
            .andExpect(content().string(containsString("Ostrzeżenie o prywatnych notatkach")))
            .andExpect(content().string(containsString("Część Twoich notatek pasujących do tej kolekcji jest prywatna")))
            .andExpect(content().string(containsString("3+ gwiazdek")))
            .andExpect(content().string(containsString("Szukaj: obrót")))
            .andExpect(content().string(containsString("Publiczna")))
            .andExpect(content().string(containsString("title=\"Edytuj kolekcję\"")))
            .andExpect(content().string(containsString("title=\"Usuń kolekcję\"")))
            .andExpect(content().string(containsString("Usunąć kolekcję?")))
            .andExpect(content().string(containsString("Czy na pewno chcesz usunąć „Kombinacje turniejowe”? Tej operacji nie można cofnąć, ale materiały w niej zawarte nie zostaną usunięte.")))
            .andExpect(content().string(containsString("Anuluj")))
            .andExpect(content().string(containsString("Usuń kolekcję")))
            .andExpect(content().string(not(containsString("Private Notes Warning"))))
            .andExpect(content().string(not(containsString("Delete Collection?"))))
            .andExpect(content().string(not(containsString("3+ stars"))))
    }

    @Test
    fun `collections new form renders in Polish when locale is pl`() {
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())

        mockMvc.perform(
            get("/lists/new")
                .locale(Locale.forLanguageTag("pl"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nowa kolekcja")))
            .andExpect(content().string(containsString("Utwórz przefiltrowaną kolekcję materiałów tanecznych z estetyczną okładką.")))
            .andExpect(content().string(containsString("Nazwa kolekcji")))
            .andExpect(content().string(containsString("placeholder=\"np. Moja kolekcja walca\"")))
            .andExpect(content().string(containsString("Okładka kolekcji")))
            .andExpect(content().string(containsString("Prześlij plik")))
            .andExpect(content().string(containsString("lub kliknij tutaj, aby wybrać")))
            .andExpect(content().string(containsString("PNG, JPG, GIF do 10MB")))
            .andExpect(content().string(containsString("Zmień zdjęcie")))
            .andExpect(content().string(containsString("Filtry kolekcji")))
            .andExpect(content().string(containsString("Nazwa zawiera")))
            .andExpect(content().string(containsString("placeholder=\"Filtruj materiały według nazwy...\"")))
            .andExpect(content().string(containsString("Minimalna ocena")))
            .andExpect(content().string(containsString("Style tańca")))
            .andExpect(content().string(containsString("Kategorie")))
            .andExpect(content().string(containsString("Przytrzymaj Ctrl/Cmd, aby zaznaczyć kilka")))
            .andExpect(content().string(containsString("Kolekcja publiczna")))
            .andExpect(content().string(containsString("Widoczna dla wszystkich użytkowników platformy")))
            .andExpect(content().string(containsString("Anuluj")))
            .andExpect(content().string(containsString("Zapisz kolekcję")))
            .andExpect(content().string(not(containsString("New Collection"))))
            .andExpect(content().string(not(containsString("Collection Name"))))
            .andExpect(content().string(not(containsString("Save Collection"))))
    }

    @Test
    fun `collections pages render in English when locale is en`() {
        val listId = UUID.randomUUID()
        val list = CustomList().apply {
            id = listId
            name = "Waltz Collection"
            owner = currentUser
            isPublic = false
            minRating = 3
            nameFilter = "spin"
            createdAt = LocalDateTime.now()
        }

        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(listOf(list))
        `when`(customListService.findById(listId)).thenReturn(list)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(danceCategoryService.findAll()).thenReturn(emptyList())
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged()))).thenReturn(PageImpl(emptyList()))

        // Index page in EN
        mockMvc.perform(
            get("/lists")
                .locale(Locale.forLanguageTag("en"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("My Collections")))
            .andExpect(content().string(containsString("1 collections curated")))
            .andExpect(content().string(containsString("New Collection")))
            .andExpect(content().string(containsString("Private")))
            .andExpect(content().string(containsString("3+ stars")))
            .andExpect(content().string(containsString("Open")))
            .andExpect(content().string(containsString("Edit")))
            .andExpect(content().string(containsString("Delete")))
            .andExpect(content().string(containsString("data-confirm=\"Are you sure you want to delete this collection?\"")))

        // View page in EN
        mockMvc.perform(
            get("/lists/$listId")
                .locale(Locale.forLanguageTag("en"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Collections")))
            .andExpect(content().string(containsString("Private")))
            .andExpect(content().string(containsString("3+ stars")))
            .andExpect(content().string(containsString("Search: spin")))
            .andExpect(content().string(containsString("Delete Collection?")))
            .andExpect(content().string(containsString("Are you sure you want to delete &quot;Waltz Collection&quot;? This action cannot be undone, but the materials inside will not be deleted.")))
            .andExpect(content().string(containsString("Cancel")))

        // Form page in EN
        mockMvc.perform(
            get("/lists/new")
                .locale(Locale.forLanguageTag("en"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("New Collection")))
            .andExpect(content().string(containsString("Collection Name")))
            .andExpect(content().string(containsString("Collection Cover Image")))
            .andExpect(content().string(containsString("Save Collection")))
    }
}
