package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingTimeline
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingCalendarService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import com.jankowski.rafal.dancebook.service.TrainingSeriesService
import com.jankowski.rafal.dancebook.service.TrainingTimelineService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
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
import org.springframework.data.domain.PageImpl
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view

@WebMvcTest(
    controllers = [
        TrainingEventWebController::class,
        DanceFigureWebController::class,
        MaterialWebController::class,
        CustomListWebController::class,
        ChoreographyWebController::class,
        TrainingTimelineWebController::class,
        NotificationController::class
    ],
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
class HtmxFragmentRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    // TrainingEventWebController
    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var trainingSeriesService: TrainingSeriesService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var trainingCalendarService: TrainingCalendarService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService

    // DanceFigureWebController
    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var danceTypeService: DanceTypeService

    // MaterialWebController
    @MockBean private lateinit var commentService: CommentService

    // CustomListWebController
    @MockBean private lateinit var customListService: CustomListService

    // ChoreographyWebController
    @MockBean private lateinit var choreographyService: ChoreographyService

    // TrainingTimelineWebController
    @MockBean private lateinit var trainingTimelineService: TrainingTimelineService

    // NotificationController & NavbarAdvice
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    @Test
    fun `training-events list htmx returns eventsList fragment with id events-list`() {
        `when`(trainingEventService.findByCurrentUser(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

        mockMvc.perform(get("/training-events").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/list :: eventsList"))
            .andExpect(content().string(containsString("id=\"events-list\"")))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `dance-figures list htmx returns figuresTable fragment with id figures-grid`() {
        `when`(danceFigureService.findAll(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

        mockMvc.perform(get("/dance-figures").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/list :: figuresTable"))
            .andExpect(content().string(containsString("id=\"figures-grid\"")))
            .andExpect(content().string(startsWith("<div")))
    }

    private fun <T> anyNonNull(dummy: T): T {
        org.mockito.Mockito.any<T>()
        return dummy
    }

    @Test
    fun `materials list htmx returns materialsTable fragment with id materials-grid`() {
        `when`(materialService.findAll(any(), any(), any(), any(), anyNonNull(org.springframework.data.domain.Pageable.unpaged())))
            .thenReturn(PageImpl(emptyList()))

        mockMvc.perform(get("/materials").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("materials/list :: materialsTable"))
            .andExpect(content().string(containsString("id=\"materials-grid\"")))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `custom-lists index htmx returns collectionsTable fragment with id collections-grid`() {
        `when`(customListService.findVisibleByCurrentUser(any(), any(), any(), any())).thenReturn(emptyList())

        mockMvc.perform(get("/lists").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("lists/index :: collectionsTable"))
            .andExpect(content().string(containsString("id=\"collections-grid\"")))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `choreographies index htmx returns choreographyList fragment with id choreographies-list`() {
        `when`(choreographyService.findByCurrentUser()).thenReturn(emptyList())

        mockMvc.perform(get("/choreographies").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("choreographies/index :: choreographyList"))
            .andExpect(content().string(containsString("id=\"choreographies-list\"")))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `training timeline htmx returns timelinePage fragment`() {
        `when`(trainingTimelineService.timelineForCurrentUser(anyInt(), any())).thenReturn(
            TrainingTimeline(months = emptyList(), hasMore = false, nextPage = 1, lastMonthLabel = null, isFirstPage = true)
        )

        mockMvc.perform(get("/training-events/timeline").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/timeline :: timelinePage"))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `notifications dropdown htmx returns notificationPanel fragment`() {
        val testUser = com.jankowski.rafal.dancebook.model.AppUser().apply {
            id = java.util.UUID.randomUUID()
            email = "test@example.com"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(activityEventService.getUnreadEvents(testUser.id!!)).thenReturn(emptyList())
        `when`(activityEventService.getUnreadCount(testUser.id!!)).thenReturn(0)

        mockMvc.perform(get("/notifications").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("notifications/dropdown :: notificationPanel"))
            .andExpect(content().string(startsWith("<div")))
    }

    @Test
    fun `dance-figures new form renders chevron with rotating classes passed via cls`() {
        `when`(danceTypeService.findAll()).thenReturn(emptyList())

        mockMvc.perform(get("/dance-figures/new").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(view().name("dance-figures/form"))
            .andExpect(content().string(containsString("transform transition-transform group-open:rotate-90")))
            .andExpect(content().string(containsString("transform transition-transform group-open:rotate-180")))
    }
}
