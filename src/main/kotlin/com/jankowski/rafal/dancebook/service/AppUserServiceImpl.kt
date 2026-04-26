package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import jakarta.persistence.EntityNotFoundException
import com.jankowski.rafal.dancebook.dto.UserCreateRequest
import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import com.jankowski.rafal.dancebook.dto.UserUpdateRequest
import com.jankowski.rafal.dancebook.model.Role
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserServiceImpl(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${dancebook.security.root-admin}")
    private val rootAdminUsername: String,
    @Value("\${dancebook.security.root-password}")
    private val rootAdminPassword: String
) : AppUserService, CommandLineRunner {

    companion object {
        private val log = LoggerFactory.getLogger(AppUserServiceImpl::class.java)
    }

    override fun run(vararg args: String?) {
        bootstrapRootUser()
    }

    private fun bootstrapRootUser() {
        if (appUserRepository.findByUsername(rootAdminUsername) == null) {
            log.info("Root administrator '{}' not found. Bootstrapping initial account...", rootAdminUsername)
            val rootUser = AppUser().apply {
                this.username = rootAdminUsername
                this.displayName = "System Administrator"
                this.email = "admin@dancebook.local"
                this.password = passwordEncoder.encode(rootAdminPassword)
                this.role = Role.ADMIN
            }
            appUserRepository.save(rootUser)
            log.info("Root administrator account '{}' created successfully.", rootAdminUsername)
        }
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

    override fun findByUsername(username: String): AppUser {
        log.debug("Retrieving user by username {}", username)
        val user = appUserRepository.findByUsername(username) ?: throw EntityNotFoundException("Could not find user with username: $username")
        return user
    }

    override fun getCurrentUser(): AppUser {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication.principal == "anonymousUser") {
            throw EntityNotFoundException("No user is currently logged in")
        }

        val principal = authentication.principal
        return if (principal is OAuth2User) {
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

    override fun updateUser(
        id: UUID,
        request: UserUpdateRequest
    ): AppUser {
        log.debug("Updating user with id {}", id)
        var user = findById(id)

        val existingEmailUser = appUserRepository.findByEmailIgnoreCase(request.email)
        if (existingEmailUser != null && existingEmailUser.id != id) {
            throw IllegalArgumentException("Email ${request.email} is already in use")
        }

        val existingUsernameUser = appUserRepository.findByUsername(request.username)
        if (existingUsernameUser != null && existingUsernameUser.id != id) {
            throw IllegalArgumentException("Username ${request.username} is already taken")
        }

        val oldRole = user.role
        val oldUsername = user.username

        user.apply {
            this.username = request.username
            this.email = request.email
            this.displayName = request.displayName
            
            // Security Check: Prevent removing the last admin or demoting the root user
            if (oldRole == Role.ADMIN && request.role == Role.USER) {
                if (oldUsername == rootAdminUsername) {
                    throw IllegalArgumentException("The root administrator account cannot be demoted.")
                }
                if (appUserRepository.countByRole(Role.ADMIN) <= 1) {
                    throw IllegalArgumentException("Cannot demote the last remaining administrator.")
                }
            }
            
            this.role = request.role
        }

        if (!request.newPassword.isNullOrBlank()) {
            user.password = passwordEncoder.encode(request.newPassword)
        }

        return appUserRepository.save(user)
    }
}