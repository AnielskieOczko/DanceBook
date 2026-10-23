package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
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
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [TrainingCalendarWebController::class],
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
@Import(TrainingCalendarWebTest.SecurityTestConfig::class, RichTextServiceImpl::class)
class TrainingCalendarWebTest {

    @TestConfiguration
    class SecurityTestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockitoBean private lateinit var googleCalendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var calendarMemberService: CalendarMemberService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService

    private val polish = Locale.forLanguageTag("pl")
    private val english = Locale.ENGLISH
    private lateinit var testUser: AppUser
    private lateinit var publicCal: TrainingCalendar
    private lateinit var privateCal: TrainingCalendar

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "testuser"
            displayName = "Test User"
            email = "user@example.com"
            role = Role.USER
        }
        val auth = UsernamePasswordAuthenticationToken(
            testUser, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
        )
        SecurityContextHolder.getContext().authentication = auth

        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)
        `when`(customListService.findVisibleByCurrentUser()).thenReturn(emptyList())

        publicCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Club Calendar"
            owner = testUser
            visibility = Visibility.PUBLIC
            enabled = true
            color = "#10b981"
            sources = mutableListOf(CalendarSource().apply {
                id = UUID.randomUUID()
                googleCalendarId = "club@google.com"
                displayName = "Club"
                isWriteTarget = true
            })
        }

        privateCal = TrainingCalendar().apply {
            id = UUID.randomUUID()
            displayName = "Personal Calendar"
            owner = testUser
            visibility = Visibility.PRIVATE
            enabled = true
            color = "#3b82f6"
            sources = mutableListOf(CalendarSource().apply {
                id = UUID.randomUUID()
                googleCalendarId = "personal@google.com"
                displayName = "Personal"
                isWriteTarget = true
            })
        }

        `when`(trainingCalendarService.findAllVisibleTo(testUser)).thenReturn(listOf(publicCal, privateCal))
        `when`(calendarMemberService.getPendingInvitesFor(testUser)).thenReturn(emptyList())
        `when`(trainingCalendarService.findAllPublic()).thenReturn(listOf(publicCal))
    }

    @Test
    fun `training calendars list renders in English with correct visibility badges and no missing message keys`() {
        mockMvc.perform(get("/training-calendars").locale(english).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Public")))
            .andExpect(content().string(containsString("Private")))
            .andExpect(content().string(not(containsString("??"))))
    }

    @Test
    fun `training calendars list renders in Polish with correct visibility badges and no missing message keys`() {
        mockMvc.perform(get("/training-calendars").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Publiczny")))
            .andExpect(content().string(containsString("Prywatny")))
            .andExpect(content().string(not(containsString("??"))))
    }

    @Test
    fun `new calendar form renders without missing message keys`() {
        mockMvc.perform(get("/training-calendars/new").locale(english).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Connect Training Calendar")))
            .andExpect(content().string(not(containsString("??"))))
    }

    @Test
    fun `edit calendar form renders member badges in English and Polish with no missing message keys`() {
        val memberOwner = CalendarMember().apply {
            id = UUID.randomUUID()
            calendar = privateCal
            user = testUser
            role = CalendarMemberRole.OWNER
            state = CalendarMemberState.ACTIVE
        }
        val invitedUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "invited"
            displayName = "Invited User"
            email = "invited@example.com"
        }
        val memberViewer = CalendarMember().apply {
            id = UUID.randomUUID()
            calendar = privateCal
            user = invitedUser
            role = CalendarMemberRole.VIEWER
            state = CalendarMemberState.INVITED
        }

        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)
        `when`(calendarMemberService.findMembers(privateCal.id!!, testUser)).thenReturn(listOf(memberOwner, memberViewer))

        // English render check
        mockMvc.perform(get("/training-calendars/${privateCal.id}/edit").locale(english).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Owner")))
            .andExpect(content().string(containsString("Viewer")))
            .andExpect(content().string(containsString("Active")))
            .andExpect(content().string(containsString("Invited")))
            .andExpect(content().string(containsString("type=\"hidden\"")))
            .andExpect(content().string(containsString("name=\"googleCalendarId\"")))
            .andExpect(content().string(containsString("value=\"personal@google.com\"")))
            .andExpect(content().string(not(containsString("??"))))

        // Polish render check
        mockMvc.perform(get("/training-calendars/${privateCal.id}/edit").locale(polish).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Właściciel")))
            .andExpect(content().string(containsString("Widz")))
            .andExpect(content().string(containsString("Aktywny")))
            .andExpect(content().string(containsString("Zaproszony")))
            .andExpect(content().string(not(containsString("??"))))
    }

    @Test
    fun `create calendar succeeds and redirects on valid input`() {
        mockMvc.perform(
            post("/training-calendars")
                .with(csrf())
                .param("displayName", "New Calendar")
                .param("googleCalendarId", "new@google.com")
                .param("color", "#10b981")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).add(
            TrainingCalendarRequest(googleCalendarId = "new@google.com", displayName = "New Calendar", color = "#10b981"),
            testUser,
            true
        )
    }

    @Test
    fun `create calendar shows validation error without Whitelabel 400 when fields are invalid`() {
        mockMvc.perform(
            post("/training-calendars")
                .with(csrf())
                .param("displayName", "")
                .param("googleCalendarId", "")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("training-calendars/form"))
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("form-input-invalid")))
    }

    @Test
    fun `edit calendar update succeeds and redirects on valid input`() {
        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)

        mockMvc.perform(
            post("/training-calendars/${privateCal.id}")
                .with(csrf())
                .param("displayName", "Updated Name")
                .param("color", "#ff0000")
                .param("googleCalendarId", "personal@google.com")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).update(
            privateCal.id!!,
            TrainingCalendarRequest(googleCalendarId = "personal@google.com", displayName = "Updated Name", color = "#ff0000"),
            testUser
        )
    }

    @Test
    fun `edit calendar update shows validation error when displayName is blank`() {
        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)
        `when`(calendarMemberService.findMembers(privateCal.id!!, testUser)).thenReturn(emptyList())

        mockMvc.perform(
            post("/training-calendars/${privateCal.id}")
                .with(csrf())
                .param("displayName", "")
                .param("googleCalendarId", "personal@google.com")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("training-calendars/form"))
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("form-input-invalid")))
            .andExpect(content().string(containsString("Display name cannot be empty")))
    }

    @Test
    fun `set default calendar updates default and redirects`() {
        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)

        mockMvc.perform(
            post("/training-calendars/${privateCal.id}/default")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).setDefault(privateCal.id!!, testUser)
    }

    @Test
    fun `publish dialog renders confirmation modal fragment and publish updates visibility`() {
        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)

        mockMvc.perform(
            get("/training-calendars/${privateCal.id}/publish-dialog")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Publish Calendar")))
            .andExpect(content().string(containsString("/training-calendars/${privateCal.id}/publish")))
            .andExpect(content().string(not(containsString("??"))))

        mockMvc.perform(
            post("/training-calendars/${privateCal.id}/publish")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).setVisibility(privateCal.id!!, Visibility.PUBLIC, testUser)
    }

    @Test
    fun `unpublish dialog renders confirmation modal fragment and unpublish updates visibility`() {
        `when`(trainingCalendarService.findByIdVisibleTo(publicCal.id!!, testUser)).thenReturn(publicCal)

        mockMvc.perform(
            get("/training-calendars/${publicCal.id}/unpublish-dialog")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Make Calendar Private")))
            .andExpect(content().string(containsString("/training-calendars/${publicCal.id}/unpublish")))
            .andExpect(content().string(not(containsString("??"))))

        mockMvc.perform(
            post("/training-calendars/${publicCal.id}/unpublish")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).setVisibility(publicCal.id!!, Visibility.PRIVATE, testUser)
    }

    @Test
    fun `delete dialog renders confirmation modal fragment and delete removes calendar`() {
        `when`(trainingCalendarService.findByIdVisibleTo(privateCal.id!!, testUser)).thenReturn(privateCal)
        `when`(trainingCalendarService.countSessions(privateCal.id!!)).thenReturn(3L)

        mockMvc.perform(
            get("/training-calendars/${privateCal.id}/delete-dialog")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Delete Training Calendar")))
            .andExpect(content().string(containsString("This will remove 3 training sessions")))
            .andExpect(content().string(containsString("/training-calendars/${privateCal.id}/delete")))
            .andExpect(content().string(not(containsString("??"))))

        mockMvc.perform(
            post("/training-calendars/${privateCal.id}/delete")
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-calendars"))
            .andExpect(flash().attributeExists("calendarSuccess"))

        verify(trainingCalendarService).delete(privateCal.id!!, testUser)
    }
}
