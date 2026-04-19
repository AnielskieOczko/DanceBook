package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.security.CustomOAuth2UserService
import jakarta.servlet.http.HttpServletRequest
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
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val clientRegistrationRepository: ClientRegistrationRepository
) {
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
                authorizationEndpoint {
                    authorizationRequestResolver = httpsOAuth2RequestResolver()
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
     * Despite configuring proxy headers, Spring Security still generates http://
     * redirect URIs. This resolver patches them to https:// for production URLs.
     */
    private fun httpsOAuth2RequestResolver(): OAuth2AuthorizationRequestResolver {
        val defaultResolver = DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization"
        )
        return object : OAuth2AuthorizationRequestResolver {
            override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
                defaultResolver.resolve(request)?.forceHttps()

            override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? =
                defaultResolver.resolve(request, clientRegistrationId)?.forceHttps()

            private fun OAuth2AuthorizationRequest.forceHttps(): OAuth2AuthorizationRequest {
                val uri = this.redirectUri
                if (uri.startsWith("http://") && !uri.contains("localhost")) {
                    return OAuth2AuthorizationRequest.from(this)
                        .redirectUri(uri.replaceFirst("http://", "https://"))
                        .authorizationRequestUri(null as String?)
                        .build()
                }
                return this
            }
        }
    }
}