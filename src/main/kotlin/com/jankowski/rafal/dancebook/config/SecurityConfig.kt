package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.security.CustomOAuth2UserService
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customOAuth2UserService: CustomOAuth2UserService
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

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/images/**", permitAll)
                authorize("/login", permitAll)
                authorize(anyRequest, authenticated)
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
                defaultSuccessUrl("/", true)
            }
            logout {
                logoutSuccessUrl = "/login?logout"
            }
        }

        return http.build()
    }
}