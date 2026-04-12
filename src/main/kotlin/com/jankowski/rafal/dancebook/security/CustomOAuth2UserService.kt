package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.repository.AppUserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service

@Service
class CustomOAuth2UserService(
    private val appUserRepository: AppUserRepository
): DefaultOAuth2UserService() {


    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val oauth2User = super.loadUser(userRequest)

        val email = oauth2User.attributes["email"] as String? ?: throw OAuth2AuthenticationException(
            OAuth2Error("missing_email"), "No email found from OAuth provider")

        val appUser = appUserRepository.findByEmail(email)
            ?: throw OAuth2AuthenticationException(OAuth2Error("unauthorized_user"), "User email not registered in system: $email")

        val authority = SimpleGrantedAuthority("ROLE_${appUser.role.name}")

        return DefaultOAuth2User(
            listOf(authority),
            oauth2User.attributes,
            "email"
        )
    }



}