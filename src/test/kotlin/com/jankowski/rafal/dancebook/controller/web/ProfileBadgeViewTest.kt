package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.BadgeType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.UserAssistantPreferences
import com.jankowski.rafal.dancebook.model.UserBadge
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import com.jankowski.rafal.dancebook.repository.UserAssistantPreferencesRepository
import com.jankowski.rafal.dancebook.repository.UserBadgeRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class ProfileBadgeViewTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var userBadgeRepository: UserBadgeRepository
    @Autowired private lateinit var trainingRecordRepository: TrainingRecordRepository
    @Autowired private lateinit var preferencesRepository: UserAssistantPreferencesRepository

    @MockitoBean private lateinit var googleDriveService: GoogleDriveService
    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var testUser: AppUser
    private val polish = Locale.forLanguageTag("pl")

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())

        val existing = appUserRepository.findByUsername("profile_badge_user")
        testUser = existing ?: appUserRepository.save(
            AppUser().apply {
                username = "profile_badge_user"
                displayName = "Badge Dancer"
                role = Role.USER
            }
        )
        testUser.locale = null
        appUserRepository.save(testUser)

        userBadgeRepository.findAllByUserOrderByEarnedAtAsc(testUser).forEach { userBadgeRepository.delete(it) }
        trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(testUser).forEach { trainingRecordRepository.delete(it) }
        preferencesRepository.deleteById(testUser.id!!)
    }

    private fun asUser() = user(testUser.username).roles("USER")

    @Test
    fun `profile renders earned badges medallions count pill and locked targets with progress in English and Polish`() {
        val baseTime = LocalDateTime.of(2026, 10, 12, 18, 0)

        // 12 consecutive attended sessions of 180 min each (36 hours total)
        (1L..12L).forEach { idx ->
            trainingRecordRepository.save(
                TrainingRecord().apply {
                    trainingEventId = UUID.randomUUID()
                    occurredAt = baseTime.minusDays(idx)
                    durationMinutes = 180
                    outcome = TrainingOutcome.ATTENDED
                    title = "Session $idx"
                    eventType = TrainingEventType.TRAINING
                    createdBy = testUser
                }
            )
        }

        // Earned badges: STREAK_5, STREAK_10, HOURS_50, and two PERFECT_MONTH awards (August and September)
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.STREAK_5
                monthKey = ""
                earnedAt = LocalDateTime.of(2026, 9, 18, 12, 0)
            }
        )
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.HOURS_50
                monthKey = ""
                earnedAt = LocalDateTime.of(2026, 9, 2, 12, 0)
            }
        )
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.PERFECT_MONTH
                monthKey = "2026-08"
                earnedAt = LocalDateTime.of(2026, 9, 1, 9, 0)
            }
        )
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.PERFECT_MONTH
                monthKey = "2026-09"
                earnedAt = LocalDateTime.of(2026, 10, 1, 9, 0)
            }
        )
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.STREAK_10
                monthKey = ""
                earnedAt = LocalDateTime.of(2026, 10, 3, 19, 0)
            }
        )

        mockMvc.perform(get("/profile").with(asUser()).locale(Locale.ENGLISH))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"badges-section\"")))
            .andExpect(content().string(containsString("4 of 8 earned, latest: Streak of 10")))
            .andExpect(content().string(containsString("Streak of 10")))
            .andExpect(content().string(containsString("Streak of 5")))
            .andExpect(content().string(containsString("50 hours")))
            .andExpect(content().string(containsString("Perfect month")))
            .andExpect(content().string(containsString("×2")))
            .andExpect(content().string(containsString("last September")))
            .andExpect(content().string(containsString("Streak of 20")))
            .andExpect(content().string(containsString("12 of 20 sessions")))
            .andExpect(content().string(containsString("100 hours")))
            .andExpect(content().string(containsString("36 of 100 hours")))
            .andExpect(content().string(containsString("Style balance")))
            .andExpect(content().string(not(containsString("Coming soon"))))

        mockMvc.perform(get("/profile").with(asUser()).locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Zdobyto: 4 z 8, ostatnia: Seria 10 treningów")))
            .andExpect(content().string(containsString("Seria 10 treningów")))
            .andExpect(content().string(containsString("Seria 5 treningów")))
            .andExpect(content().string(containsString("50 godzin")))
            .andExpect(content().string(containsString("Idealny miesiąc")))
            .andExpect(content().string(containsString("×2")))
            .andExpect(content().string(containsString("Seria 20 treningów")))
            .andExpect(content().string(containsString("Treningi: 12 z 20")))
            .andExpect(content().string(containsString("Godziny: 36 z 100")))
            .andExpect(content().string(containsString("Równowaga stylów")))
            .andExpect(content().string(not(containsString("Wkrótce"))))
    }

    @Test
    fun `earned badges stay stored when showStreaksAndBadges is toggled off and reappear when toggled back on`() {
        userBadgeRepository.save(
            UserBadge().apply {
                user = testUser
                badgeType = BadgeType.STREAK_5
                monthKey = ""
                earnedAt = LocalDateTime.of(2026, 9, 18, 12, 0)
            }
        )

        preferencesRepository.save(
            UserAssistantPreferences().apply {
                userId = testUser.id
                showStreaksAndBadges = false
            }
        )

        mockMvc.perform(get("/profile").with(asUser()).locale(Locale.ENGLISH))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("id=\"badges-section\""))))
            .andExpect(content().string(not(containsString("1 of 8 earned"))))

        preferencesRepository.save(
            UserAssistantPreferences().apply {
                userId = testUser.id
                showStreaksAndBadges = true
            }
        )

        mockMvc.perform(get("/profile").with(asUser()).locale(Locale.ENGLISH))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"badges-section\"")))
            .andExpect(content().string(containsString("1 of 8 earned, latest: Streak of 5")))
    }
}
