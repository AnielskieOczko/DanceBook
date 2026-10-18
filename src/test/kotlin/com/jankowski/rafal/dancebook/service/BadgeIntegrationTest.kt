package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.dto.TrainingCalendarRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.EventType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.ActivityEventRepository
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingCalendarRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import com.jankowski.rafal.dancebook.repository.UserBadgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class BadgeIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var trainingCalendarService: TrainingCalendarService
    @Autowired private lateinit var trainingCalendarRepository: TrainingCalendarRepository
    @Autowired private lateinit var trainingEventService: TrainingEventService
    @Autowired private lateinit var trainingEventPersistence: TrainingEventPersistence
    @Autowired private lateinit var trainingEventRepository: TrainingEventRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var userBadgeRepository: UserBadgeRepository
    @Autowired private lateinit var activityEventRepository: ActivityEventRepository
    @Autowired private lateinit var activityEventService: ActivityEventService
    @Autowired private lateinit var assistantPreferencesService: AssistantPreferencesService
    @Autowired private lateinit var preferencesRepository: UserAssistantPreferencesRepository
    @Autowired private lateinit var badgeService: BadgeService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser
    private lateinit var adminUser: AppUser

    @BeforeEach
    fun setUp() {
        activityEventRepository.deleteAll()
        userBadgeRepository.deleteAll()
        trainingRecordRepository.deleteAll()
        trainingEventRepository.deleteAll()
        trainingCalendarRepository.deleteAll()

        userA = appUserRepository.save(AppUser().apply {
            username = "badge-a-${UUID.randomUUID()}"
            displayName = "User A"
            password = "pwd"
            role = Role.USER
        })
        userB = appUserRepository.save(AppUser().apply {
            username = "badge-b-${UUID.randomUUID()}"
            displayName = "User B"
            password = "pwd"
            role = Role.USER
        })
        adminUser = appUserRepository.save(AppUser().apply {
            username = "badge-admin-${UUID.randomUUID()}"
            displayName = "Admin User"
            password = "pwd"
            role = Role.ADMIN
        })

        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)
        `when`(appUserService.findById(userA.id!!)).thenReturn(userA)
        `when`(appUserService.findById(userB.id!!)).thenReturn(userB)
        `when`(appUserService.findById(adminUser.id!!)).thenReturn(adminUser)
        trainingCalendarService.add(
            TrainingCalendarRequest(
                googleCalendarId = "cal-badges@group.calendar.google.com",
                displayName = "Badge Calendar"
            )
        )
    }

    @Test
    fun `marking 5th attended session awards STREAK_5 once creates earner-only notification and never revokes on later skip`() {
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)

        // Create 5 planned sessions in the past, shared with userB
        val events = (5L downTo 1L).map { daysAgo ->
            val created = trainingEventService.create(
                TrainingEventRequest(
                    title = "Session $daysAgo",
                    date = LocalDate.now().minusDays(daysAgo),
                    startTime = LocalTime.of(18, 0),
                    endTime = LocalTime.of(19, 0),
                    eventType = "TRAINING"
                )
            )
            val loaded = trainingEventRepository.findById(created.id!!).get()
            loaded.setAttendance(userB, AttendanceStatus.PLANNED)
            trainingEventRepository.save(loaded)
        }

        // User B already has 4 attended sessions of their own, waiting on 1 more
        (10L downTo 7L).forEach { daysAgo ->
            val bEvent = trainingEventRepository.save(
                com.jankowski.rafal.dancebook.model.TrainingEvent().apply {
                    title = "B Session $daysAgo"
                    startTime = LocalDate.now().minusDays(daysAgo).atTime(18, 0)
                    endTime = startTime.plusMinutes(60)
                    createdBy = userB
                    setAttendance(userB, AttendanceStatus.ATTENDED)
                }
            )
            trainingEventPersistence.updateAttendance(bEvent, userB, AttendanceStatus.ATTENDED)
        }
        assertTrue(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userB).isEmpty())

        // User A marks the first 4 attended -> no badge yet
        events.take(4).forEach { ev ->
            trainingEventService.updateAttendance(ev.id!!, AttendanceStatus.ATTENDED)
        }
        assertTrue(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA).isEmpty())

        // User A marks the 5th session attended -> earns STREAK_5
        val fifthEvent = events.last()
        trainingEventService.updateAttendance(fifthEvent.id!!, AttendanceStatus.ATTENDED)

        val badgesA = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA)
        assertEquals(1, badgesA.size)
        assertEquals(BadgeType.STREAK_5, badgesA.single().badgeType)
        assertEquals(fifthEvent.id, badgesA.single().trainingEventId)

        // User B was on the same shared sessions, but marking for User A never awards or notifies User B
        assertTrue(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userB).isEmpty())

        // Re-marking the 5th session attended does not duplicate the badge or notification
        trainingEventService.updateAttendance(fifthEvent.id!!, AttendanceStatus.ATTENDED)
        assertEquals(1, userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA).size)

        // User A sees the BADGE_EARNED notification
        val unreadForA = activityEventService.getUnreadEvents(userA.id!!)
        val badgeNotificationsForA = unreadForA.filter { it.eventType == EventType.BADGE_EARNED }
        assertEquals(1, badgeNotificationsForA.size)
        assertEquals("STREAK_5", badgeNotificationsForA.single().targetName)

        // User B cannot see User A's badge notification
        `when`(appUserService.getCurrentUser()).thenReturn(userB)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userB)
        val unreadForB = activityEventService.getUnreadEvents(userB.id!!)
        assertTrue(unreadForB.none { it.eventType == EventType.BADGE_EARNED })
        val historyForB = activityEventService.getAllEvents(PageRequest.of(0, 50)).content
        assertTrue(historyForB.none { it.eventType == EventType.BADGE_EARNED })

        // Even Admin cannot see User A's badge notification
        `when`(appUserService.getCurrentUser()).thenReturn(adminUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(adminUser)
        val unreadForAdmin = activityEventService.getUnreadEvents(adminUser.id!!)
        assertTrue(unreadForAdmin.none { it.eventType == EventType.BADGE_EARNED })
        val historyForAdmin = activityEventService.getAllEvents(PageRequest.of(0, 50)).content
        assertTrue(historyForAdmin.none { it.eventType == EventType.BADGE_EARNED })

        // User A later skips a session and deletes the 5th session -> earned badge stays earned
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)
        val laterSession = trainingEventService.create(
            TrainingEventRequest(
                title = "Skipped session",
                date = LocalDate.now(),
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(11, 0),
                eventType = "TRAINING",
                attendanceStatus = "SKIPPED"
            )
        )
        trainingEventService.delete(fifthEvent.id!!)

        val badgesAfterSkipAndDelete = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA)
        assertEquals(1, badgesAfterSkipAndDelete.size)
        assertEquals(BadgeType.STREAK_5, badgesAfterSkipAndDelete.single().badgeType)
        assertFalse(laterSession.id == null)
    }

    @Test
    fun `opting out of showStreaksAndBadges prevents earning and notifications while preserving previously earned badges`() {
        `when`(appUserService.getCurrentUser()).thenReturn(userA)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(userA)

        // User A opts out of streaks and badges
        assistantPreferencesService.updatePreferences(
            userA,
            AssistantPreferencesUpdateRequest(showStreaksAndBadges = false)
        )

        // User A attends 5 sessions totaling 60 hours -> nothing is evaluated, awarded, or notified
        (5L downTo 1L).forEach { daysAgo ->
            trainingEventService.create(
                TrainingEventRequest(
                    title = "Long camp $daysAgo",
                    date = LocalDate.now().minusDays(daysAgo),
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(20, 0),
                    eventType = "CAMP",
                    attendanceStatus = "ATTENDED"
                )
            )
        }

        assertTrue(userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA).isEmpty())
        val unreadWhileOff = activityEventService.getUnreadEvents(userA.id!!)
        assertTrue(unreadWhileOff.none { it.eventType == EventType.BADGE_EARNED })

        // Turning preference back on and recording attendance awards the qualified badges
        assistantPreferencesService.updatePreferences(
            userA,
            AssistantPreferencesUpdateRequest(showStreaksAndBadges = true)
        )
        badgeService.evaluateForUser(userA)
        val earned = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA).map { it.badgeType }.toSet()
        assertTrue(BadgeType.STREAK_5 in earned)
        assertTrue(BadgeType.HOURS_50 in earned)

        // Turning preference off again leaves already-earned badges in the database
        assistantPreferencesService.updatePreferences(
            userA,
            AssistantPreferencesUpdateRequest(showStreaksAndBadges = false)
        )
        val stillInDb = userBadgeRepository.findAllByUserOrderByEarnedAtAsc(userA).map { it.badgeType }.toSet()
        assertTrue(BadgeType.STREAK_5 in stillInDb)
        assertTrue(BadgeType.HOURS_50 in stillInDb)
    }
}
