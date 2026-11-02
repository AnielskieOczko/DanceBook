package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.security.CustomOAuth2UserService
import com.jankowski.rafal.dancebook.security.DurableRememberMeServices
import com.jankowski.rafal.dancebook.security.HtmxAuthenticationEntryPoint
import com.jankowski.rafal.dancebook.security.OAuth2RememberMeSuccessHandler
import jakarta.servlet.DispatcherType
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.Ordered
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customOAuth2UserService: CustomOAuth2UserService,
    @Lazy private val assistantConversationCleanup: com.jankowski.rafal.dancebook.service.AssistantConversationCleanup,
    private val durableRememberMeServices: DurableRememberMeServices
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * Cloud Run terminates TLS at its load balancer and forwards HTTP internally.
     * This filter reads X-Forwarded-Proto and X-Forwarded-For headers to make
     * the request appear as HTTPS to Spring Security.
     * Registered with HIGHEST_PRECEDENCE to run before all Security filters.
     */
    @Bean
    fun forwardedHeaderFilter(): FilterRegistrationBean<ForwardedHeaderFilter> {
        val registration = FilterRegistrationBean(ForwardedHeaderFilter())
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }

    /**
     * Extracts Cloud Run trace context or request ID into MDC so logs can be correlated.
     * Registered right after ForwardedHeaderFilter before security filters.
     */
    @Bean
    fun traceContextFilter(): FilterRegistrationBean<com.jankowski.rafal.dancebook.logging.TraceContextFilter> {
        val registration = FilterRegistrationBean(com.jankowski.rafal.dancebook.logging.TraceContextFilter())
        registration.order = Ordered.HIGHEST_PRECEDENCE + 1
        return registration
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/error", permitAll)
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/images/**", permitAll)
                authorize("/login", permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/**", hasRole("ADMIN"))
                authorize("/admin/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = HtmxAuthenticationEntryPoint()
            }
            formLogin {
                loginPage = "/login"
                defaultSuccessUrl("/", true)
            }
            oauth2Login {
                loginPage = "/login"
                userInfoEndpoint {
                    userService = customOAuth2UserService
                }
                authenticationSuccessHandler = OAuth2RememberMeSuccessHandler(durableRememberMeServices, "/")
            }
            rememberMe {
                key = durableRememberMeServices.key
                rememberMeServices = durableRememberMeServices
            }
            logout {
                logoutSuccessUrl = "/login?logout"
                addLogoutHandler(assistantConversationCleanup)
                addLogoutHandler(durableRememberMeServices)
            }
            headers {
                contentSecurityPolicy {
                    policyDirectives = "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; script-src 'self' https://unpkg.com; connect-src 'self' https://www.googleapis.com; frame-src 'self'"
                }
            }
        }

        return http.build()
    }
}