package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.PatternReconcilePlan
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
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
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.util.UUID

@Controller
class NonAdminSampleDialogController {
    @GetMapping("/test/shared-confirm-dialog")
    fun sampleDialog(model: Model): String {
        model.addAttribute("dialogTitle", "Delete Shared Collection?")
        model.addAttribute("dialogMessage", "This will remove the collection without affecting individual materials.")
        model.addAttribute("confirmLabel", "Delete Collection")
        model.addAttribute("confirmUrl", "/lists/123/delete")
        model.addAttribute("hxTarget", "#collectionsList")
        model.addAttribute("hxSwap", "outerHTML")
        model.addAttribute("cancelLabel", "Keep Collection")
        return "fragments/confirm-dialog :: confirmModal"
    }

    @GetMapping("/test/bulk-confirm-dialog")
    fun bulkDialog(model: Model): String {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        model.addAttribute("dialogTitle", "Delete Sessions")
        model.addAttribute("dialogMessage", "This will delete 2 sessions.")
        model.addAttribute("confirmLabel", "Delete 2 Sessions")
        model.addAttribute("confirmUrl", "/training-events/bulk-delete")
        model.addAttribute("hxTarget", "#events-list")
        model.addAttribute("hxSwap", "outerHTML")
        model.addAttribute("hxInclude", "#filterForm")
        model.addAttribute("selectedCount", 2)
        model.addAttribute("sessionIds", listOf(id1, id2))
        return "fragments/confirm-dialog :: confirmModal"
    }

    @GetMapping("/test/scope-confirm-dialog")
    fun scopeDialog(model: Model): String {
        val id = UUID.randomUUID()
        model.addAttribute("dialogTitle", "Delete Recurring Session")
        model.addAttribute("dialogMessage", "This session is part of a repeating series. How far should this delete reach?")
        model.addAttribute("confirmLabel", "Delete")
        model.addAttribute("confirmUrl", "/training-events/$id/delete")
        model.addAttribute("scopeOptions", listOf(
            com.jankowski.rafal.dancebook.dto.ScopeOption(
                scope = com.jankowski.rafal.dancebook.model.SeriesScope.THIS_EVENT,
                count = 1,
                outcomeCount = 0
            ),
            com.jankowski.rafal.dancebook.dto.ScopeOption(
                scope = com.jankowski.rafal.dancebook.model.SeriesScope.THIS_AND_FOLLOWING,
                count = 4,
                outcomeCount = 1
            ),
            com.jankowski.rafal.dancebook.dto.ScopeOption(
                scope = com.jankowski.rafal.dancebook.model.SeriesScope.ALL_EVENTS,
                count = 3,
                outcomeCount = 2
            )
        ))
        return "fragments/confirm-dialog :: confirmModal"
    }

    @GetMapping("/test/pattern-reconcile-dialog")
    fun patternReconcileDialog(model: Model): String {
        val id = UUID.randomUUID()
        model.addAttribute("dialogTitle", "Edit Recurring Series")
        model.addAttribute("dialogMessage", "Updating this repeating series will recompute session dates:")
        model.addAttribute("reconcilePlan", PatternReconcilePlan(
            createdCount = 2,
            movedCount = 3,
            removedCount = 1,
            droppedRecordedCount = 1,
            targetTotalCount = 5
        ))
        model.addAttribute("confirmLabel", "Apply Changes")
        model.addAttribute("confirmUrl", "/training-events/$id")
        model.addAttribute("confirmFormId", "trainingEventForm")
        return "fragments/confirm-dialog :: confirmModal"
    }
}

@WebMvcTest(
    controllers = [AdminCalendarController::class, NonAdminSampleDialogController::class],
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
class ConfirmDialogRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockBean private lateinit var googleCalendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    // Pulled in by NavbarAdvice and TrainingCalendarContextAdvice
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        val user = com.jankowski.rafal.dancebook.model.AppUser().apply { id = UUID.randomUUID() }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
    }

    @Test
    fun `confirm dialog is usable from outside the admin screen`() {
        mockMvc.perform(get("/test/shared-confirm-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Delete Shared Collection?")))
            .andExpect(content().string(containsString("This will remove the collection without affecting individual materials.")))
            .andExpect(content().string(containsString("Delete Collection")))
            .andExpect(content().string(containsString("Keep Collection")))
            .andExpect(content().string(containsString("action=\"/lists/123/delete\"")))
            .andExpect(content().string(containsString("hx-post=\"/lists/123/delete\"")))
            .andExpect(content().string(containsString("hx-target=\"#collectionsList\"")))
            .andExpect(content().string(containsString("<dialog")))
            .andExpect(content().string(not(containsString("js-modal-backdrop"))))
            .andExpect(content().string(not(containsString("onclick="))))
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `calendar delete dialog renders consequences message stating session count and untouched Google`() {
        val id = UUID.randomUUID()
        val calendar = TrainingCalendar().apply {
            this.id = id
            displayName = "Club Calendar"
        }
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar)
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(calendar, TrainingCalendar()))
        `when`(trainingCalendarService.countSessions(id)).thenReturn(5L)

        mockMvc.perform(get("/admin/calendars/$id/delete-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Delete Training Calendar")))
            .andExpect(content().string(containsString("This will remove 5 training sessions from DanceBook")))
            .andExpect(content().string(containsString("orphaned records")))
            .andExpect(content().string(containsString("Events in Google Calendar will not be touched")))
            .andExpect(content().string(containsString("Delete Calendar")))
            .andExpect(content().string(containsString("action=\"/admin/calendars/$id/delete\"")))
            .andExpect(content().string(not(containsString("onclick="))))
    }

    @Test
    fun `confirm dialog carries selection and hx-include when specified`() {
        mockMvc.perform(get("/test/bulk-confirm-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Delete Sessions")))
            .andExpect(content().string(containsString("2 selected")))
            .andExpect(content().string(containsString("hx-include=\"#filterForm\"")))
            .andExpect(content().string(containsString("name=\"sessionIds\"")))
    }

    @Test
    fun `confirm dialog renders series scope radio options with counts and history descriptions`() {
        mockMvc.perform(get("/test/scope-confirm-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Delete Recurring Session")))
            .andExpect(content().string(containsString("This session is part of a repeating series. How far should this delete reach?")))
            .andExpect(content().string(containsString("name=\"scope\"")))
            .andExpect(content().string(containsString("value=\"THIS_EVENT\"")))
            .andExpect(content().string(containsString("value=\"THIS_AND_FOLLOWING\"")))
            .andExpect(content().string(containsString("value=\"ALL_EVENTS\"")))
            .andExpect(content().string(containsString("This event")))
            .andExpect(content().string(containsString("This and following")))
            .andExpect(content().string(containsString("All events")))
            .andExpect(content().string(containsString("1 session")))
            .andExpect(content().string(containsString("4 sessions (1 with recorded outcome)")))
            .andExpect(content().string(containsString("3 sessions (2 with recorded outcomes)")))
            .andExpect(content().string(containsString("Delete")))
    }

    @Test
    fun `confirm dialog renders reconcile plan with created moved removed counts and dropped recorded warning`() {
        mockMvc.perform(get("/test/pattern-reconcile-dialog").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Edit Recurring Series")))
            .andExpect(content().string(containsString("Updating this repeating series will recompute session dates:")))
            .andExpect(content().string(containsString("2 sessions will be created")))
            .andExpect(content().string(containsString("3 sessions will be moved")))
            .andExpect(content().string(containsString("1 session will be removed")))
            .andExpect(content().string(containsString("1 past session with recorded attendance will be dropped from the series and kept as a standalone session.")))
            .andExpect(content().string(containsString("Apply Changes")))
            .andExpect(content().string(containsString("id=\"modalConfirmBtn\"")))
    }
}
