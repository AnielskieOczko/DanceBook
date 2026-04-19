package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.security.CustomOAuth2UserService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val clientRegistrationRepository: ClientRegistrationRepository,
    @Value("\${APP_BASE_URL:}") private val appBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    init {
        log.info("SecurityConfig initialized with APP_BASE_URL='{}', isBlank={}", appBaseUrl, appBaseUrl.isBlank())
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

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
                if (appBaseUrl.isNotBlank()) {
                    authorizationEndpoint {
                        authorizationRequestResolver = cloudRunOAuth2RequestResolver()
                    }
                }
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

    /**
     * Cloud Run terminates TLS at its load balancer and forwards HTTP internally.
     * Spring's ForwardedHeaderFilter does not reliably process X-Forwarded-Proto on Cloud Run,
     * causing redirect URIs to use http:// instead of https://.
     *
     * This resolver uses the APP_BASE_URL env var (set in deploy.yml) to construct
     * the correct https:// redirect URI. Only active when APP_BASE_URL is set (production).
     */
    private fun cloudRunOAuth2RequestResolver(): OAuth2AuthorizationRequestResolver {
        val defaultResolver = DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization"
        )
        defaultResolver.setAuthorizationRequestCustomizer { builder ->
            val redirectUri = "$appBaseUrl/login/oauth2/code/google"
            log.info("OAuth2 customizer invoked. Setting redirectUri to: {}", redirectUri)
            builder.redirectUri(redirectUri)
        }
        return defaultResolver
    }
}