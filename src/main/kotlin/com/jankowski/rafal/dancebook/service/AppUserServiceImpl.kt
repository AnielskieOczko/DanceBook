package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserServiceImpl(
    private val appUserRepository: AppUserRepository,
) : AppUserService {

    companion object {
        private const val DEFAULT_USERNAME = "rafal"
        private val log = LoggerFactory.getLogger(AppUserServiceImpl::class.java)
    }

    override fun findAll(): List<AppUser> {
        log.debug("Retrieving all users")
        return appUserRepository.findAll()
    }

    override fun findById(id: UUID): AppUser {
        log.debug("Retrieving user by id {}", id)
        return appUserRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find user with id $id")
        }
    }

    override fun findByUsername(username: String): AppUser? {
        log.debug("Retrieving user by username {}", username)
        return appUserRepository.findByUsername(username)
    }

    override fun getCurrentUser(): AppUser {
        val authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication.principal == "anonymousUser") {
            throw EntityNotFoundException("No user is currently logged in")
        }

        val principal = authentication.principal
        return if (principal is org.springframework.security.oauth2.core.user.OAuth2User) {
            val email = principal.getAttribute<String>("email") ?: throw EntityNotFoundException("OAuth2 user has no email")
            appUserRepository.findByEmailIgnoreCase(email)
        } else {
            val username = authentication.name
            appUserRepository.findByUsername(username)
        } ?: throw EntityNotFoundException("User not found in underlying database")
    }
}