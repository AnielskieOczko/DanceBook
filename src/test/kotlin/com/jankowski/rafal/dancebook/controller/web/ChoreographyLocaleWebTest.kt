package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.*
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
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
    controllers = [ChoreographyWebController::class],
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
    ChoreographyLocaleWebTest.SecurityTestConfig::class
)
class ChoreographyLocaleWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var choreographyService: ChoreographyService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var appUserService: AppUserService

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService

    private val polishLocale = Locale.forLanguageTag("pl")
    private val englishLocale = Locale.ENGLISH
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

    private fun createTestChoreography(
        name: String = "Bronze Routine",
        description: String? = "Test description",
        isPublic: Boolean = true,
        withFigures: Boolean = true
    ): Pair<Choreography, DanceType> {
        val style = DanceType().apply {
            id = UUID.randomUUID()
            this.name = "Waltz"
        }
        val choreo = Choreography().apply {
            id = UUID.randomUUID()
            this.name = name
            this.description = description
            this.danceType = style
            this.owner = currentUser
            this.visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }

        if (withFigures) {
            val figure = DanceFigure().apply {
                id = UUID.randomUUID()
                this.name = "Natural Turn"
                this.danceType = style
                this.alternativeTiming = "123"
            }
            val stepSet = DanceFigureStepSet().apply {
                id = UUID.randomUUID()
                this.danceFigure = figure
                this.name = "Default"
                this.isDefault = true
                this.steps = mutableListOf(
                    DanceFigureStep().apply {
                        id = UUID.randomUUID()
                        stepNumber = 1
                        role = "LEADER"
                        timing = "1"
                        foot = "RF"
                        action = "Forward right foot"
                        footwork = "HT"
                        alignment = "LOD"
                        amountOfTurn = "1/4 R"
                    },
                    DanceFigureStep().apply {
                        id = UUID.randomUUID()
                        stepNumber = 1
                        role = "FOLLOWER"
                        timing = "1"
                        foot = "LF"
                        action = "Back left foot"
                    }
                )
            }
            figure.stepSets = mutableListOf(stepSet)

            val sectionEntry = ChoreographyEntry().apply {
                id = UUID.randomUUID()
                this.choreography = choreo
                this.entryType = EntryType.SECTION_LABEL
                this.sectionLabel = "Opening Sequence"
                this.sortOrder = 0
            }
            val figureEntry = ChoreographyEntry().apply {
                id = UUID.randomUUID()
                this.choreography = choreo
                this.entryType = EntryType.FIGURE
                this.danceFigure = figure
                this.lineIndicator = LineIndicator.LONG_WALL
                this.notes = "Start diagonal wall"
                this.sortOrder = 1
            }
            choreo.entries = mutableListOf(sectionEntry, figureEntry)
        }

        return Pair(choreo, style)
    }

    @Test
    fun `choreographies empty index renders in Polish when locale is pl`() {
        `when`(choreographyService.findByCurrentUser()).thenReturn(emptyList())

        mockMvc.perform(
            get("/choreographies")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Choreografie")))
            .andExpect(content().string(containsString("0 utworzonych układów")))
            .andExpect(content().string(containsString("Nowa choreografia")))
            .andExpect(content().string(containsString("Moje układy")))
            .andExpect(content().string(containsString("Brak choreografii")))
            .andExpect(content().string(containsString("Utwórz swój pierwszy układ taneczny, łącząc figury ze sobą!")))
            .andExpect(content().string(containsString("Utwórz choreografię")))
            .andExpect(content().string(not(containsString("0 choreography routines created"))))
            .andExpect(content().string(not(containsString("New Choreography"))))
            .andExpect(content().string(not(containsString("My Routines"))))
            .andExpect(content().string(not(containsString("No choreographies yet"))))
            .andExpect(content().string(not(containsString("Create Choreography"))))
    }

    @Test
    fun `choreographies empty index renders in English when locale is en`() {
        `when`(choreographyService.findByCurrentUser()).thenReturn(emptyList())

        mockMvc.perform(
            get("/choreographies")
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Choreographies")))
            .andExpect(content().string(containsString("0 choreography routines created")))
            .andExpect(content().string(containsString("New Choreography")))
            .andExpect(content().string(containsString("My Routines")))
            .andExpect(content().string(containsString("No choreographies yet")))
            .andExpect(content().string(containsString("Create your first dance choreography routine by chaining figures together!")))
            .andExpect(content().string(containsString("Create Choreography")))
    }

    @Test
    fun `choreographies index with items renders in Polish when locale is pl`() {
        val (choreo, _) = createTestChoreography()
        `when`(choreographyService.findByCurrentUser()).thenReturn(listOf(choreo))

        mockMvc.perform(
            get("/choreographies")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("1 utworzonych układów")))
            .andExpect(content().string(containsString("2 pozycji")))
            .andExpect(content().string(containsString("aria-label=\"Więcej opcji\"")))
            .andExpect(content().string(containsString("Otwórz")))
            .andExpect(content().string(containsString("Edytor")))
            .andExpect(content().string(containsString("Duplikuj")))
            .andExpect(content().string(containsString("Usuń")))
            .andExpect(content().string(containsString("data-confirm=\"Czy na pewno chcesz usunąć tę choreografię?\"")))
            .andExpect(content().string(not(containsString("1 choreography routines created"))))
            .andExpect(content().string(not(containsString("2 entries"))))
            .andExpect(content().string(not(containsString("Are you sure you want to delete this choreography?"))))
    }

    @Test
    fun `choreographies index with items renders in English when locale is en`() {
        val (choreo, _) = createTestChoreography()
        `when`(choreographyService.findByCurrentUser()).thenReturn(listOf(choreo))

        mockMvc.perform(
            get("/choreographies")
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("1 choreography routines created")))
            .andExpect(content().string(containsString("2 entries")))
            .andExpect(content().string(containsString("aria-label=\"More actions\"")))
            .andExpect(content().string(containsString("Open")))
            .andExpect(content().string(containsString("Build")))
            .andExpect(content().string(containsString("Duplicate")))
            .andExpect(content().string(containsString("Delete")))
            .andExpect(content().string(containsString("data-confirm=\"Are you sure you want to delete this choreography?\"")))
    }

    @Test
    fun `choreographies htmx request returns fragment in Polish when locale is pl`() {
        val (choreo, _) = createTestChoreography()
        `when`(choreographyService.findByCurrentUser()).thenReturn(listOf(choreo))

        mockMvc.perform(
            get("/choreographies")
                .header("HX-Request", "true")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"choreographies-list\"")))
            .andExpect(content().string(containsString("2 pozycji")))
            .andExpect(content().string(containsString("Otwórz")))
            .andExpect(content().string(not(containsString("2 entries"))))
    }

    @Test
    fun `choreography create form renders in Polish when locale is pl and English when locale is en`() {
        val style = DanceType().apply { id = UUID.randomUUID(); name = "Waltz" }
        `when`(danceTypeService.findAll()).thenReturn(listOf(style))

        // Polish
        mockMvc.perform(
            get("/choreographies/new")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nowa choreografia")))
            .andExpect(content().string(containsString("Ustaw dane choreografii. W kolejnym kroku możesz dodać figury do układu.")))
            .andExpect(content().string(containsString("Nazwa choreografii")))
            .andExpect(content().string(containsString("np. Brązowy układ walca")))
            .andExpect(content().string(containsString("Opis / Notatki")))
            .andExpect(content().string(containsString("Dodaj szczegóły, poziom zaawansowania lub cel treningowy...")))
            .andExpect(content().string(containsString("Styl tańca")))
            .andExpect(content().string(containsString("Wybierz styl tańca...")))
            .andExpect(content().string(containsString("Publiczna choreografia")))
            .andExpect(content().string(containsString("Widoczna i możliwa do skopiowania przez innych tancerzy")))
            .andExpect(content().string(containsString("Anuluj")))
            .andExpect(content().string(containsString("Zapisz szczegóły")))
            .andExpect(content().string(not(containsString("New Choreography"))))
            .andExpect(content().string(not(containsString("Choreography Name"))))

        // English
        mockMvc.perform(
            get("/choreographies/new")
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("New Choreography")))
            .andExpect(content().string(containsString("Set up your choreography metadata. You can add figures to the routine in the next step.")))
            .andExpect(content().string(containsString("Choreography Name")))
            .andExpect(content().string(containsString("e.g. Bronze Waltz Routine")))
            .andExpect(content().string(containsString("Description / Notes")))
            .andExpect(content().string(containsString("Select a dance style...")))
            .andExpect(content().string(containsString("Public Choreography")))
            .andExpect(content().string(containsString("Visible and cloneable by other dancers")))
            .andExpect(content().string(containsString("Cancel")))
            .andExpect(content().string(containsString("Save Details")))
    }

    @Test
    fun `choreography edit metadata form renders in Polish when locale is pl`() {
        val (choreo, style) = createTestChoreography()
        `when`(choreographyService.findById(choreo.id!!)).thenReturn(choreo)
        `when`(danceTypeService.findAll()).thenReturn(listOf(style))

        mockMvc.perform(
            get("/choreographies/{id}/metadata", choreo.id)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Edytuj szczegóły choreografii")))
            .andExpect(content().string(containsString("Styl tańca nie może zostać zmieniony po utworzeniu choreografii.")))
            .andExpect(content().string(not(containsString("Edit Choreography Details"))))
            .andExpect(content().string(not(containsString("Dance style cannot be changed after choreography has been created."))))
    }

    @Test
    fun `choreography builder edit view renders in Polish when locale is pl and English when locale is en`() {
        val (choreo, style) = createTestChoreography()
        `when`(choreographyService.findById(choreo.id!!)).thenReturn(choreo)
        `when`(danceFigureService.findByDanceType(style.id!!)).thenReturn(listOf(choreo.entries[1].danceFigure!!))

        // Polish
        mockMvc.perform(
            get("/choreographies/{id}/edit", choreo.id)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Powrót do choreografii")))
            .andExpect(content().string(containsString("Edytuj szczegóły")))
            .andExpect(content().string(containsString("Zobacz układ")))
            .andExpect(content().string(containsString("Sekwencja układu")))
            .andExpect(content().string(containsString("placeholder=\"np. Sekwencja początkowa\"")))
            .andExpect(content().string(containsString("title=\"Usuń sekcję\"")))
            .andExpect(content().string(containsString("placeholder=\"Dodaj wskazówki treningowe...\"")))
            .andExpect(content().string(containsString("Kierunek tańca")))
            .andExpect(content().string(containsString("title=\"Usuń pozycję\"")))
            .andExpect(content().string(containsString("value=\"Nowa sekcja\"")))
            .andExpect(content().string(containsString("Dodaj podział sekcji")))
            .andExpect(content().string(containsString("Wybór figur")))
            .andExpect(content().string(containsString("Kliknij ikonę „+” obok figury, aby dodać ją do sekwencji układu.")))
            .andExpect(content().string(containsString("placeholder=\"Szukaj figur...\"")))
            .andExpect(content().string(containsString("Rytm: 123")))
            .andExpect(content().string(containsString("aria-label=\"Dodaj figurę\"")))
            .andExpect(content().string(containsString("Zobacz ogólną bazę figur")))
            .andExpect(content().string(not(containsString("Back to Choreographies"))))
            .andExpect(content().string(not(containsString("Routine Sequence"))))
            .andExpect(content().string(not(containsString("View Routine"))))
            .andExpect(content().string(not(containsString("Figure Selector"))))

        // English
        mockMvc.perform(
            get("/choreographies/{id}/edit", choreo.id)
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Back to Choreographies")))
            .andExpect(content().string(containsString("Edit Details")))
            .andExpect(content().string(containsString("View Routine")))
            .andExpect(content().string(containsString("Routine Sequence")))
            .andExpect(content().string(containsString("placeholder=\"e.g. Opening Sequence\"")))
            .andExpect(content().string(containsString("title=\"Delete section\"")))
            .andExpect(content().string(containsString("placeholder=\"Add practice details...\"")))
            .andExpect(content().string(containsString("Line Indicator")))
            .andExpect(content().string(containsString("title=\"Delete entry\"")))
            .andExpect(content().string(containsString("value=\"New Section\"")))
            .andExpect(content().string(containsString("Add Section Divider")))
            .andExpect(content().string(containsString("Figure Selector")))
            .andExpect(content().string(containsString("Click the &quot;+&quot; icon next to a figure to add it to your routine sequence.")))
            .andExpect(content().string(containsString("placeholder=\"Search figures...\"")))
            .andExpect(content().string(containsString("Timing: 123")))
            .andExpect(content().string(containsString("aria-label=\"Add figure\"")))
            .andExpect(content().string(containsString("View global figures list")))
    }

    @Test
    fun `choreography builder empty sequence renders in Polish when locale is pl`() {
        val (choreo, style) = createTestChoreography(withFigures = false)
        `when`(choreographyService.findById(choreo.id!!)).thenReturn(choreo)
        `when`(danceFigureService.findByDanceType(style.id!!)).thenReturn(emptyList())

        mockMvc.perform(
            get("/choreographies/{id}/edit", choreo.id)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Sekwencja jest pusta")))
            .andExpect(content().string(containsString("Rozpocznij układanie, wybierając figury z listy po prawej stronie lub dodając podział sekcji.")))
            .andExpect(content().string(containsString("Nie znaleziono figur dla tego stylu.")))
            .andExpect(content().string(not(containsString("Sequence is empty"))))
            .andExpect(content().string(not(containsString("No figures found for this style."))))
    }

    @Test
    fun `choreography view page renders in Polish when locale is pl and English when locale is en`() {
        val (choreo, _) = createTestChoreography()
        `when`(choreographyService.findById(choreo.id!!)).thenReturn(choreo)

        // Polish
        mockMvc.perform(
            get("/choreographies/{id}", choreo.id)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Powrót do listy")))
            .andExpect(content().string(containsString("Drukuj / PDF")))
            .andExpect(content().string(containsString("Układaj sekwencję")))
            .andExpect(content().string(containsString("Edytuj szczegóły")))
            .andExpect(content().string(containsString("Klonuj układ")))
            .andExpect(content().string(containsString("Usuń układ")))
            .andExpect(content().string(containsString("Właściciel:")))
            .andExpect(content().string(containsString("Styl tańca:")))
            .andExpect(content().string(containsString("Zaktualizowano:")))
            .andExpect(content().string(containsString("Legenda kolorów")))
            .andExpect(content().string(containsString("Linia tańca")))
            .andExpect(content().string(containsString("Długa ściana")))
            .andExpect(content().string(containsString("Krótka ściana")))
            .andExpect(content().string(containsString("Przekątna")))
            .andExpect(content().string(containsString("Narożnik")))
            .andExpect(content().string(containsString("Partner:")))
            .andExpect(content().string(containsString("Partnerka:")))
            .andExpect(content().string(containsString("Szczegóły")))
            .andExpect(content().string(containsString("Zobacz figurę w syllabusie")))
            .andExpect(content().string(containsString("Szczegóły techniczne")))
            .andExpect(content().string(containsString("Praca stóp:")))
            .andExpect(content().string(containsString("Ustawienie:")))
            .andExpect(content().string(containsString("Obrót:")))
            .andExpect(content().string(containsString("Szczegółowy podział sekwencji")))
            .andExpect(content().string(not(containsString("Back to List"))))
            .andExpect(content().string(not(containsString("Print / PDF"))))
            .andExpect(content().string(not(containsString("Build Sequence"))))
            .andExpect(content().string(not(containsString("Detailed Sequence Breakdown"))))

        // English
        mockMvc.perform(
            get("/choreographies/{id}", choreo.id)
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Back to List")))
            .andExpect(content().string(containsString("Print / PDF")))
            .andExpect(content().string(containsString("Build Sequence")))
            .andExpect(content().string(containsString("Edit Details")))
            .andExpect(content().string(containsString("Clone Routine")))
            .andExpect(content().string(containsString("Delete Routine")))
            .andExpect(content().string(containsString("Owner:")))
            .andExpect(content().string(containsString("Dance Style:")))
            .andExpect(content().string(containsString("Updated:")))
            .andExpect(content().string(containsString("Color Guide")))
            .andExpect(content().string(containsString("Line of Dance")))
            .andExpect(content().string(containsString("Long Wall")))
            .andExpect(content().string(containsString("Short Wall")))
            .andExpect(content().string(containsString("Diagonal")))
            .andExpect(content().string(containsString("Corner")))
            .andExpect(content().string(containsString("Man:")))
            .andExpect(content().string(containsString("Lady:")))
            .andExpect(content().string(containsString("Details")))
            .andExpect(content().string(containsString("View Figure in Syllabus")))
            .andExpect(content().string(containsString("Technical details")))
            .andExpect(content().string(containsString("Footwork:")))
            .andExpect(content().string(containsString("Alignment:")))
            .andExpect(content().string(containsString("Turn:")))
            .andExpect(content().string(containsString("Detailed Sequence Breakdown")))
    }

    @Test
    fun `choreography empty view page renders in Polish when locale is pl`() {
        val (choreo, _) = createTestChoreography(withFigures = false)
        `when`(choreographyService.findById(choreo.id!!)).thenReturn(choreo)

        mockMvc.perform(
            get("/choreographies/{id}", choreo.id)
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Ta choreografia nie zawiera jeszcze żadnych figur.")))
            .andExpect(content().string(containsString("Otwórz edytor")))
            .andExpect(content().string(not(containsString("This choreography doesn't have any figures yet."))))
            .andExpect(content().string(not(containsString("Open Builder"))))
    }
}
