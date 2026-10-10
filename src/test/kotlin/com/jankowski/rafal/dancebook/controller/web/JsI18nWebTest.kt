package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.I18nJsBundleServiceImpl
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Controller
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import java.util.Locale

@WebMvcTest(
    controllers = [JsI18nWebTest.TestI18nViewController::class],
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
    I18nJsBundleServiceImpl::class,
    JsI18nWebTest.TestI18nViewController::class,
    JsI18nWebTest.SecurityTestConfig::class
)
class JsI18nWebTest {

    @org.springframework.boot.test.context.TestConfiguration
    class SecurityTestConfig {
        @org.springframework.context.annotation.Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Controller
    class TestI18nViewController {
        @GetMapping("/test-i18n-page")
        fun testPage(): String = "error/404"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: com.jankowski.rafal.dancebook.service.CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockitoBean private lateinit var trainingCalendarService: com.jankowski.rafal.dancebook.service.TrainingCalendarService

    private val polish = Locale.forLanguageTag("pl")
    private val english = Locale.ENGLISH

    @Test
    fun `page renders English i18n-bundle in head when locale is en`() {
        mockMvc.perform(get("/test-i18n-page").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<script id="i18n-bundle" type="application/json"""")))
            .andExpect(content().string(containsString("The assistant couldn&#39;t answer just now. Try again.")))
            .andExpect(content().string(containsString("&quot;common.dismiss&quot;:&quot;Dismiss&quot;")))
            .andExpect(content().string(containsString("&quot;js.error.unexpected&quot;:&quot;An unexpected error occurred. Please try again.&quot;")))
            .andExpect(content().string(containsString("&quot;js.error.network&quot;:&quot;Network error. Please check your connection and try again.&quot;")))
            .andExpect(content().string(not(containsString("Asystent nie mógł teraz odpowiedzieć"))))
            .andExpect(content().string(not(containsString("&quot;common.dismiss&quot;:&quot;Zamknij&quot;"))))
    }

    @Test
    fun `page renders Polish i18n-bundle in head when locale is pl`() {
        mockMvc.perform(get("/test-i18n-page").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<script id="i18n-bundle" type="application/json"""")))
            .andExpect(content().string(containsString("Asystent nie mógł teraz odpowiedzieć. Spróbuj ponownie.")))
            .andExpect(content().string(containsString("&quot;common.dismiss&quot;:&quot;Zamknij&quot;")))
            .andExpect(content().string(containsString("&quot;js.error.unexpected&quot;:&quot;Wystąpił nieoczekiwany błąd. Spróbuj ponownie.&quot;")))
            .andExpect(content().string(containsString("&quot;js.error.network&quot;:&quot;Błąd sieci. Sprawdź połączenie i spróbuj ponownie.&quot;")))
            .andExpect(content().string(not(containsString("The assistant couldn't answer just now"))))
            .andExpect(content().string(not(containsString("&quot;common.dismiss&quot;:&quot;Dismiss&quot;"))))
    }
}
