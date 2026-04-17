package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.repository.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service


@Service
class CustomUserDetailsService(
    private val appUserRepository: AppUserRepository,
): UserDetailsService {

    companion object {
        private val logger = LoggerFactory.getLogger(CustomUserDetailsService::class.java)
    }

    override fun loadUserByUsername(username: String): UserDetails? {
        val appUser = appUserRepository.findByUsername(username) ?: throw UsernameNotFoundException("User not found: $username")
        
        return User(
            appUser.username,
            appUser.password ?: "",
            listOf(SimpleGrantedAuthority("ROLE_${appUser.role.name}"))
        )
    }


}