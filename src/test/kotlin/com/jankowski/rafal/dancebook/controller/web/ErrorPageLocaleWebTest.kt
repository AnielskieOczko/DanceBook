package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
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
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.util.Locale

@WebMvcTest(
    controllers = [ErrorPageLocaleWebTest.TestErrorViewController::class],
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
    ErrorPageLocaleWebTest.TestErrorViewController::class,
    ErrorPageLocaleWebTest.SecurityTestConfig::class
)
class ErrorPageLocaleWebTest {

    @org.springframework.boot.test.context.TestConfiguration
    class SecurityTestConfig {
        @org.springframework.context.annotation.Bean
        fun webSecurityExpressionHandler() = org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Controller
    class TestErrorViewController {
        @GetMapping("/test-error/404")
        fun notFound() = "error/404"

        @GetMapping("/test-error/500")
        fun serverError() = "error/500"

        @GetMapping("/test-error/generic")
        fun generic(model: Model): String {
            model.addAttribute("status", 403)
            return "error"
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc

    // NavbarAdvice and Interceptor dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: com.jankowski.rafal.dancebook.service.CalendarSyncService
    @MockBean private lateinit var activeCalendarService: com.jankowski.rafal.dancebook.service.ActiveCalendarService
    @MockBean private lateinit var trainingCalendarService: com.jankowski.rafal.dancebook.service.TrainingCalendarService

    private val polish = Locale.forLanguageTag("pl")
    private val english = Locale.ENGLISH

    @Test
    fun `404 error page renders in Polish when locale is pl`() {
        mockMvc.perform(get("/test-error/404").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nie znaleziono strony")))
            .andExpect(content().string(containsString("Strona, której szukasz, nie istnieje, została przeniesiona lub jest chwilowo niedostępna.")))
            .andExpect(content().string(containsString("Wróć do panelu")))
            .andExpect(content().string(not(containsString("Page Not Found"))))
            .andExpect(content().string(not(containsString("The page you are looking for doesn't exist"))))
            .andExpect(content().string(not(containsString("Back to Dashboard"))))
    }

    @Test
    fun `404 error page renders in English when locale is en`() {
        mockMvc.perform(get("/test-error/404").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Page Not Found")))
            .andExpect(content().string(containsString("The page you are looking for doesn&#39;t exist, has been moved, or is temporarily unavailable.")))
            .andExpect(content().string(containsString("Back to Dashboard")))
            .andExpect(content().string(not(containsString("Nie znaleziono strony"))))
            .andExpect(content().string(not(containsString("Wróć do panelu"))))
    }

    @Test
    fun `500 error page renders in Polish when locale is pl`() {
        mockMvc.perform(get("/test-error/500").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Coś poszło nie tak")))
            .andExpect(content().string(containsString("Wystąpił nieoczekiwany błąd podczas przetwarzania Twojego żądania. Spróbuj ponownie lub wróć do panelu.")))
            .andExpect(content().string(containsString("Wróć do panelu")))
            .andExpect(content().string(not(containsString("Something Went Wrong"))))
            .andExpect(content().string(not(containsString("An unexpected error occurred"))))
            .andExpect(content().string(not(containsString("Back to Dashboard"))))
    }

    @Test
    fun `500 error page renders in English when locale is en`() {
        mockMvc.perform(get("/test-error/500").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Something Went Wrong")))
            .andExpect(content().string(containsString("An unexpected error occurred while processing your request. Please try again or return to the dashboard.")))
            .andExpect(content().string(containsString("Back to Dashboard")))
            .andExpect(content().string(not(containsString("Coś poszło nie tak"))))
            .andExpect(content().string(not(containsString("Wróć do panelu"))))
    }

    @Test
    fun `generic error page renders in Polish when locale is pl`() {
        mockMvc.perform(get("/test-error/generic").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Błąd 403")))
            .andExpect(content().string(containsString("Żądana akcja nie mogła zostać ukończona. Sprawdź swoje żądanie lub wróć do panelu.")))
            .andExpect(content().string(containsString("Wróć do panelu")))
            .andExpect(content().string(not(containsString("Error 403"))))
            .andExpect(content().string(not(containsString("The requested action could not be completed"))))
            .andExpect(content().string(not(containsString("Back to Dashboard"))))
    }

    @Test
    fun `generic error page renders in English when locale is en`() {
        mockMvc.perform(get("/test-error/generic").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Error 403")))
            .andExpect(content().string(containsString("The requested action could not be completed. Please check your request or return to the dashboard.")))
            .andExpect(content().string(containsString("Back to Dashboard")))
            .andExpect(content().string(not(containsString("Błąd 403"))))
            .andExpect(content().string(not(containsString("Wróć do panelu"))))
    }
}
