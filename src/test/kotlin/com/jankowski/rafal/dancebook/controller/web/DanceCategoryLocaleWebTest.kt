package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.Locale
import java.util.UUID

@WebMvcTest(
    controllers = [DanceCategoryWebController::class, DanceTypeWebController::class],
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
@Import(DanceCategoryLocaleWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class DanceCategoryLocaleWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService

    // NavbarAdvice dependencies
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val polishLocale = Locale.forLanguageTag("pl")
    private val englishLocale = Locale.ENGLISH
    private lateinit var testUser: AppUser

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "admin"
            displayName = "Admin User"
            email = "admin@example.com"
            role = Role.ADMIN
            locale = null
        }
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)
    }

    private fun createCategoryWithStyle(): DanceCategory {
        val categoryId = UUID.randomUUID()
        val style = DanceType().apply {
            id = UUID.randomUUID()
            name = "Standard Waltz"
        }
        val category = DanceCategory().apply {
            id = categoryId
            name = "Ballroom"
            danceTypes = mutableListOf(style)
        }
        style.category = category
        return category
    }

    @Test
    fun `dance categories list renders localized delete confirmations in Polish when locale is pl`() {
        val category = createCategoryWithStyle()
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))

        mockMvc.perform(
            get("/dance-categories")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-confirm=\"Czy usunąć tę kategorię i wszystkie jej style?\"")))
            .andExpect(content().string(containsString("data-confirm=\"Czy usunąć ten styl?\"")))
            .andExpect(content().string(not(containsString("onsubmit="))))
            .andExpect(content().string(not(containsString("Delete this category and all its styles?"))))
    }

    @Test
    fun `dance categories list renders localized delete confirmations in English when locale is en`() {
        val category = createCategoryWithStyle()
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))

        mockMvc.perform(
            get("/dance-categories")
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-confirm=\"Delete this category and all its styles?\"")))
            .andExpect(content().string(containsString("data-confirm=\"Delete this style?\"")))
            .andExpect(content().string(not(containsString("onsubmit="))))
            .andExpect(content().string(not(containsString("Czy usunąć tę kategorię"))))
    }

    @Test
    fun `dance types list renders localized delete confirmation in Polish when locale is pl`() {
        val category = createCategoryWithStyle()
        val style = category.danceTypes.first()
        `when`(danceTypeService.findAll()).thenReturn(listOf(style))

        mockMvc.perform(
            get("/dance-types")
                .locale(polishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-confirm=\"Czy usunąć ten styl?\"")))
            .andExpect(content().string(not(containsString("onsubmit="))))
            .andExpect(content().string(not(containsString("Delete this style?"))))
    }

    @Test
    fun `dance types list renders localized delete confirmation in English when locale is en`() {
        val category = createCategoryWithStyle()
        val style = category.danceTypes.first()
        `when`(danceTypeService.findAll()).thenReturn(listOf(style))

        mockMvc.perform(
            get("/dance-types")
                .locale(englishLocale)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-confirm=\"Delete this style?\"")))
            .andExpect(content().string(not(containsString("onsubmit="))))
            .andExpect(content().string(not(containsString("Czy usunąć ten styl?"))))
    }
}
