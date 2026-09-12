package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingHistoryMonth
import com.jankowski.rafal.dancebook.dto.TrainingHistoryRow
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingHistoryService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renders the history page for real.
 *
 * TrainingHistoryServiceTest checks the grouping and the removal rules going into the model;
 * neither notices a template that asks the model for something it does not have, or an
 * orphaned row that forgets to say so. These do.
 *
 * Mirrors the TrainingTimelineViewRenderingTest harness: same WebMvcTest slice shape, same
 * NavbarAdvice mocks.
 */
@WebMvcTest(
    controllers = [TrainingHistoryWebController::class],
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
class TrainingHistoryViewRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingHistoryService: TrainingHistoryService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    private fun record(
        title: String,
        outcome: TrainingOutcome,
        minutes: Int,
        categoryLabel: String? = null,
        orphaned: Boolean = false
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = UUID.randomUUID()
        occurredAt = LocalDateTime.of(2026, 9, 10, 18, 0)
        durationMinutes = minutes
        this.outcome = outcome
        this.title = title
        eventType = TrainingEventType.TRAINING
        if (orphaned) orphanedAt = LocalDateTime.of(2026, 9, 11, 9, 0)
        categoryLabel?.let {
            segments = mutableListOf(
                TrainingRecordSegment().apply {
                    categoryName = it
                    durationMinutes = minutes
                    sortOrder = 0
                }
            )
        }
    }

    private fun row(record: TrainingRecord) =
        TrainingHistoryRow(record, TrainingEventPalette.swatchFor(record.outcome))

    @Test
    fun `renders months, totals, style badges and the palette colour of each outcome`() {
        val attended = record("Monday practice", TrainingOutcome.ATTENDED, 90, categoryLabel = "Standard")
        val skipped = record("Missed lesson", TrainingOutcome.SKIPPED, 60)
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(
            TrainingHistory(
                listOf(
                    TrainingHistoryMonth("September 2026", listOf(row(attended), row(skipped)), attendedMinutes = 90)
                )
            )
        )

        mockMvc.perform(get("/training-events/history").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/history"))
            .andExpect(content().string(containsString("September 2026")))
            .andExpect(content().string(containsString("2 sessions")))
            .andExpect(content().string(containsString("1h 30m")))
            .andExpect(content().string(containsString("Monday practice")))
            .andExpect(content().string(containsString("Missed lesson")))
            .andExpect(content().string(containsString("Standard 90min")))
            .andExpect(content().string(containsString(TrainingEventPalette.ATTENDED.color)))
            .andExpect(content().string(containsString(TrainingEventPalette.SKIPPED.color)))
    }

    @Test
    fun `an orphaned record says its session is gone and offers to remove it`() {
        val orphan = record("Deleted session", TrainingOutcome.ATTENDED, 60, orphaned = true)
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(
            TrainingHistory(
                listOf(TrainingHistoryMonth("September 2026", listOf(row(orphan)), attendedMinutes = 60))
            )
        )

        mockMvc.perform(get("/training-events/history").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Session deleted")))
            .andExpect(content().string(containsString("/training-events/history/${orphan.id}/delete")))
    }

    @Test
    fun `an empty history explains itself`() {
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(TrainingHistory(emptyList()))

        mockMvc.perform(get("/training-events/history").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No confirmed training yet")))
    }

    @Test
    fun `removing a record goes to the service and returns to the page`() {
        val recordId = UUID.randomUUID()

        mockMvc.perform(post("/training-events/history/$recordId/delete").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-events/history"))

        verify(trainingHistoryService).deleteOrphanedRecord(recordId)
    }
}
