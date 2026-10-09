package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [NotificationController::class],
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
    NotificationLocaleWebTest.SecurityTestConfig::class
)
class NotificationLocaleWebTest {

    @org.springframework.boot.test.context.TestConfiguration
    class SecurityTestConfig {
        @org.springframework.context.annotation.Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var appUserService: AppUserService

    // NavbarAdvice and Interceptor dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: com.jankowski.rafal.dancebook.service.CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: com.jankowski.rafal.dancebook.service.TrainingCalendarService

    private val polish = Locale.forLanguageTag("pl")
    private val english = Locale.ENGLISH
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

    private fun createEvent(
        eventType: EventType,
        targetType: TargetType,
        targetName: String,
        metadata: String? = null
    ): ActivityEvent {
        return ActivityEvent().apply {
            id = UUID.randomUUID()
            this.eventType = eventType
            this.targetType = targetType
            this.targetId = UUID.randomUUID()
            this.targetName = targetName
            this.metadata = metadata
            this.actor = currentUser
            this.createdAt = LocalDateTime.now().minusHours(1)
        }
    }

    @Test
    fun `activity history empty state renders in Polish when locale is pl`() {
        `when`(activityEventService.getAllEvents(PageRequest.of(0, 50)))
            .thenReturn(PageImpl(emptyList()))

        mockMvc.perform(get("/activity-history").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Historia aktywności")))
            .andExpect(content().string(containsString("Kompletna oś czasu wszystkich działań w Twoim obszarze roboczym.")))
            .andExpect(content().string(containsString("Brak aktywności")))
            .andExpect(content().string(containsString("Zdarzenia pojawią się tutaj, gdy utworzysz materiały, dodasz komentarze i zaczniesz zarządzać kolekcjami.")))
            .andExpect(content().string(not(containsString("No activity yet"))))
            .andExpect(content().string(not(containsString("A complete timeline of all actions across your workspace."))))
    }

    @Test
    fun `activity history empty state renders in English when locale is en`() {
        `when`(activityEventService.getAllEvents(PageRequest.of(0, 50)))
            .thenReturn(PageImpl(emptyList()))

        mockMvc.perform(get("/activity-history").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Activity History · DanceBook")))
            .andExpect(content().string(containsString("Activity History")))
            .andExpect(content().string(containsString("A complete timeline of all actions across your workspace.")))
            .andExpect(content().string(containsString("No activity yet")))
            .andExpect(content().string(containsString("Events will appear here as you create materials, add comments, and manage collections.")))
            .andExpect(content().string(not(containsString("Historia aktywności"))))
            .andExpect(content().string(not(containsString("Brak aktywności"))))
    }

    @Test
    fun `activity history with events renders action descriptions in Polish when locale is pl`() {
        val events = listOf(
            createEvent(EventType.MATERIAL_CREATED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.COMMENT_ADDED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.TRAINING_EVENT_CREATED, TargetType.TRAINING_EVENT, "Practice session"),
            createEvent(EventType.TRAINING_BULK_ATTENDANCE_UPDATED, TargetType.TRAINING_EVENT, "attended", metadata = "5")
        )
        `when`(activityEventService.getAllEvents(PageRequest.of(0, 50)))
            .thenReturn(PageImpl(events))

        mockMvc.perform(get("/activity-history").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("utworzył(a) materiał")))
            .andExpect(content().string(containsString("dodał(a) komentarz do")))
            .andExpect(content().string(containsString("zaplanował(a) trening")))
            .andExpect(content().string(containsString("oznaczył(a)")))
            .andExpect(content().string(containsString("treningi: 5")))
            .andExpect(content().string(containsString("jako attended")))
            .andExpect(content().string(not(containsString("created material"))))
            .andExpect(content().string(not(containsString("commented on"))))
            .andExpect(content().string(not(containsString("scheduled training"))))
    }

    @Test
    fun `activity history with events renders action descriptions in English when locale is en`() {
        val events = listOf(
            createEvent(EventType.MATERIAL_CREATED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.COMMENT_ADDED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.TRAINING_EVENT_CREATED, TargetType.TRAINING_EVENT, "Practice session"),
            createEvent(EventType.TRAINING_BULK_ATTENDANCE_UPDATED, TargetType.TRAINING_EVENT, "attended", metadata = "5")
        )
        `when`(activityEventService.getAllEvents(PageRequest.of(0, 50)))
            .thenReturn(PageImpl(events))

        mockMvc.perform(get("/activity-history").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("created material")))
            .andExpect(content().string(containsString("commented on")))
            .andExpect(content().string(containsString("scheduled training")))
            .andExpect(content().string(containsString("marked")))
            .andExpect(content().string(containsString("5 sessions")))
            .andExpect(content().string(containsString("as attended")))
            .andExpect(content().string(not(containsString("utworzył(a) materiał"))))
            .andExpect(content().string(not(containsString("dodał(a) komentarz do"))))
            .andExpect(content().string(not(containsString("zaplanował(a) trening"))))
    }

    @Test
    fun `notification dropdown with events renders in Polish when locale is pl`() {
        val events = listOf(
            createEvent(EventType.MATERIAL_CREATED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.DANCE_FIGURE_CREATED, TargetType.DANCE_FIGURE, "Natural Turn")
        )
        `when`(activityEventService.getUnreadEvents(currentUser.id!!)).thenReturn(events)
        `when`(activityEventService.getUnreadCount(currentUser.id!!)).thenReturn(events.size.toLong())

        mockMvc.perform(get("/notifications").header("HX-Request", "true").with(csrf()).locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Powiadomienia")))
            .andExpect(content().string(containsString("Oznacz wszystkie jako przeczytane")))
            .andExpect(content().string(containsString("utworzył(a)")))
            .andExpect(content().string(containsString("utworzył(a) figurę")))
            .andExpect(content().string(containsString("Zobacz całą aktywność")))
            .andExpect(content().string(not(containsString("Mark all read"))))
            .andExpect(content().string(not(containsString("View all activity"))))
    }

    @Test
    fun `notification dropdown with events renders in English when locale is en`() {
        val events = listOf(
            createEvent(EventType.MATERIAL_CREATED, TargetType.MATERIAL, "Waltz Routine"),
            createEvent(EventType.DANCE_FIGURE_CREATED, TargetType.DANCE_FIGURE, "Natural Turn")
        )
        `when`(activityEventService.getUnreadEvents(currentUser.id!!)).thenReturn(events)
        `when`(activityEventService.getUnreadCount(currentUser.id!!)).thenReturn(events.size.toLong())

        mockMvc.perform(get("/notifications").header("HX-Request", "true").with(csrf()).locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Notifications")))
            .andExpect(content().string(containsString("Mark all read")))
            .andExpect(content().string(containsString("created")))
            .andExpect(content().string(containsString("created figure")))
            .andExpect(content().string(containsString("View all activity")))
            .andExpect(content().string(not(containsString("Oznacz wszystkie jako przeczytane"))))
            .andExpect(content().string(not(containsString("Zobacz całą aktywność"))))
    }
}
