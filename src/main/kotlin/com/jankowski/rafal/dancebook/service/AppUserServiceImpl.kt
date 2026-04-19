package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import jakarta.persistence.EntityNotFoundException
import com.jankowski.rafal.dancebook.dto.UserCreateRequest
import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserServiceImpl(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder
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

    override fun createUser(request: UserCreateRequest): AppUser {
        log.debug("Creating new user with username {}", request.username)
        
        if (appUserRepository.findByUsername(request.username) != null) {
             throw IllegalArgumentException("Username ${request.username} is already taken")
        }
        if (appUserRepository.findByEmailIgnoreCase(request.email) != null) {
             throw IllegalArgumentException("Email ${request.email} is already in use")
        }

        val newUser = AppUser().apply {
            this.username = request.username
            this.email = request.email
            this.displayName = request.displayName
            this.password = passwordEncoder.encode(request.password)
            this.role = request.role
        }

        return appUserRepository.save(newUser)
    }

    override fun changePassword(userId: UUID, request: PasswordChangeRequest) {
        log.debug("Changing password for user id {}", userId)
        if (request.newPassword != request.confirmNewPassword) {
            throw IllegalArgumentException("New passwords do not match")
        }

        val user = findById(userId)
        
        if (user.password != null && !passwordEncoder.matches(request.currentPassword, user.password)) {
            throw IllegalArgumentException("Current password is incorrect")
        }

        user.password = passwordEncoder.encode(request.newPassword)
        appUserRepository.save(user)
    }
}