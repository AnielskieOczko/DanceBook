package com.jankowski.rafal.dancebook.frontend

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.NotificationController
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.*
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.time.LocalDateTime
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
    NotificationDropdownLayoutTest.SecurityTestConfig::class
)
class NotificationDropdownLayoutTest {

    @org.springframework.boot.test.context.TestConfiguration
    class SecurityTestConfig {
        @org.springframework.context.annotation.Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var appUserService: AppUserService

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: TrainingCalendarService

    private val dropdownTemplateFile = File("src/main/resources/templates/notifications/dropdown.html")
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

    @Test
    fun `static template guard asserts no unwrappable truncate on inline elements and proper wrapping classes`() {
        assertTrue(dropdownTemplateFile.isFile, "dropdown.html must exist")
        val doc = Jsoup.parse(dropdownTemplateFile.readText())

        // Ensure no truncate class exists on inline elements inside the notification panel
        val truncatedElements = doc.select("a.truncate, span.truncate")
        assertTrue(
            truncatedElements.isEmpty(),
            "Inline elements (a, span) must not use 'truncate' because it forces white-space: nowrap without clipping, causing horizontal scroll. Found: ${truncatedElements.map { it.outerHtml() }}"
        )

        // Event list container must prevent horizontal overflow
        val listContainer = doc.selectFirst(".max-h-80")
        assertNotNull(listContainer, "List container with max-h-80 must exist")
        assertTrue(
            listContainer!!.hasClass("overflow-y-auto"),
            "List container must have overflow-y-auto for vertical scrolling"
        )
        assertTrue(
            listContainer.hasClass("overflow-x-hidden"),
            "List container must have overflow-x-hidden to prevent horizontal scrolling"
        )
    }

    @Test
    fun `notification dropdown renders long target names with line-clamp-2 and break-words without horizontal overflow classes`() {
        val longTitle = "Very Long Routine Name That Spans Multiple Lines And Explains Detailed Technique For Quickstep Natural Turns"
        val event = ActivityEvent().apply {
            id = UUID.randomUUID()
            eventType = EventType.MATERIAL_CREATED
            targetType = TargetType.MATERIAL
            targetId = UUID.randomUUID()
            targetName = longTitle
            actor = currentUser
            createdAt = LocalDateTime.now().minusHours(1)
        }
        `when`(activityEventService.getUnreadEvents(currentUser.id!!)).thenReturn(listOf(event))
        `when`(activityEventService.getUnreadCount(currentUser.id!!)).thenReturn(1L)

        val result = mockMvc.perform(get("/notifications").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Find notification message paragraph
        val paragraphs = doc.select(".flex-1 p")
        assertFalse(paragraphs.isEmpty(), "Notification content paragraphs must exist")

        val eventParagraph = paragraphs.first()!!
        assertTrue(
            eventParagraph.hasClass("line-clamp-2"),
            "Notification paragraph must have line-clamp-2 so long titles wrap within 2 lines or truncate with ellipsis"
        )
        assertTrue(
            eventParagraph.hasClass("break-words"),
            "Notification paragraph must have break-words to prevent unbroken text overflow"
        )

        // Ensure link for target does not have truncate
        val targetLink = doc.selectFirst("a[href*='/materials/']")
        assertNotNull(targetLink, "Target link must be present")
        assertFalse(
            targetLink!!.hasClass("truncate"),
            "Target link must not have truncate class"
        )
    }

    @Test
    fun `badge earned notification renders title and description with line-clamp-2 and break-words`() {
        val badgeEvent = ActivityEvent().apply {
            id = UUID.randomUUID()
            eventType = EventType.BADGE_EARNED
            targetType = TargetType.BADGE
            targetId = UUID.randomUUID()
            targetName = "STREAK_10"
            actor = currentUser
            createdAt = LocalDateTime.now().minusHours(1)
        }
        `when`(activityEventService.getUnreadEvents(currentUser.id!!)).thenReturn(listOf(badgeEvent))
        `when`(activityEventService.getUnreadCount(currentUser.id!!)).thenReturn(1L)

        val result = mockMvc.perform(get("/notifications").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)

        val badgeTitleP = doc.selectFirst(".flex-1 p:has(a[href*='/profile#badges-section'])")
        assertNotNull(badgeTitleP, "Badge title paragraph must exist")
        assertTrue(
            badgeTitleP!!.hasClass("line-clamp-2"),
            "Badge title paragraph must have line-clamp-2"
        )
        assertTrue(
            badgeTitleP.hasClass("break-words"),
            "Badge title paragraph must have break-words"
        )

        val badgeDescP = doc.select(".flex-1 p").getOrNull(1)
        assertNotNull(badgeDescP, "Badge description paragraph must exist")
        assertTrue(
            badgeDescP!!.hasClass("line-clamp-2"),
            "Badge description paragraph must have line-clamp-2"
        )
        assertTrue(
            badgeDescP.hasClass("break-words"),
            "Badge description paragraph must have break-words"
        )
    }
}
