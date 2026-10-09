package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Locale

@WebMvcTest(
    controllers = [LoginController::class],
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
class AuthLocaleWebTest {

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
    fun `login page renders in Polish when locale is pl`() {
        mockMvc.perform(get("/login").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Logowanie · DanceBook")))
            .andExpect(content().string(containsString("Twoja osobista biblioteka taneczna")))
            .andExpect(content().string(containsString("Zaloguj się")))
            .andExpect(content().string(containsString("Nazwa użytkownika")))
            .andExpect(content().string(containsString("placeholder=\"Wpisz nazwę użytkownika\"")))
            .andExpect(content().string(containsString("Hasło")))
            .andExpect(content().string(containsString("placeholder=\"Wpisz hasło\"")))
            .andExpect(content().string(containsString("Zapamiętaj mnie")))
            .andExpect(content().string(containsString("Lub kontynuuj przez")))
            .andExpect(content().string(containsString("Zaloguj się przez Google")))
            .andExpect(content().string(containsString("Stworzone z")))
            .andExpect(content().string(not(containsString("Your personal dance library"))))
            .andExpect(content().string(not(containsString("placeholder=\"Enter your username\""))))
            .andExpect(content().string(not(containsString("placeholder=\"Enter your password\""))))
            .andExpect(content().string(not(containsString("Remember me"))))
            .andExpect(content().string(not(containsString("Or continue with"))))
            .andExpect(content().string(not(containsString("Sign in with Google"))))
            .andExpect(content().string(not(containsString("Made with"))))
    }

    @Test
    fun `login page renders error message in Polish`() {
        mockMvc.perform(get("/login").param("error", "true").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nieprawidłowa nazwa użytkownika lub hasło")))
            .andExpect(content().string(not(containsString("Invalid username or password"))))
    }

    @Test
    fun `login page renders logout message in Polish`() {
        mockMvc.perform(get("/login").param("logout", "true").locale(polish))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Wylogowano pomyślnie")))
            .andExpect(content().string(not(containsString("You have been logged out"))))
    }

    @Test
    fun `login page renders in English when locale is en`() {
        mockMvc.perform(get("/login").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Sign in · DanceBook")))
            .andExpect(content().string(containsString("Your personal dance library")))
            .andExpect(content().string(containsString("Sign in")))
            .andExpect(content().string(containsString("Username")))
            .andExpect(content().string(containsString("placeholder=\"Enter your username\"")))
            .andExpect(content().string(containsString("Password")))
            .andExpect(content().string(containsString("placeholder=\"Enter your password\"")))
            .andExpect(content().string(containsString("Remember me")))
            .andExpect(content().string(containsString("Or continue with")))
            .andExpect(content().string(containsString("Sign in with Google")))
            .andExpect(content().string(containsString("Made with")))
            .andExpect(content().string(not(containsString("Twoja osobista biblioteka taneczna"))))
            .andExpect(content().string(not(containsString("Zaloguj się"))))
            .andExpect(content().string(not(containsString("Nazwa użytkownika"))))
            .andExpect(content().string(not(containsString("Zapamiętaj mnie"))))
            .andExpect(content().string(not(containsString("Stworzone z"))))
    }

    @Test
    fun `login page renders error and logout messages in English`() {
        mockMvc.perform(get("/login").param("error", "true").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Invalid username or password")))
            .andExpect(content().string(not(containsString("Nieprawidłowa nazwa użytkownika lub hasło"))))

        mockMvc.perform(get("/login").param("logout", "true").locale(english))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("You have been logged out")))
            .andExpect(content().string(not(containsString("Wylogowano pomyślnie"))))
    }
}
