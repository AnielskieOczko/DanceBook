package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.UserUpdateRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional
import java.util.UUID

class AppUserServiceTest {

    private lateinit var appUserRepository: AppUserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var appUserService: AppUserServiceImpl
    private val rootAdminUsername = "system_root"
    private val rootAdminPassword = "secret_password"

    @BeforeEach
    fun setUp() {
        appUserRepository = mock(AppUserRepository::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        appUserService = AppUserServiceImpl(appUserRepository, passwordEncoder, rootAdminUsername, rootAdminPassword)
    }

    @Test
    fun `should prevent demoting root user`() {
        val userId = UUID.randomUUID()
        val rootUser = AppUser().apply {
            this.id = userId
            this.username = rootAdminUsername
            this.role = Role.ADMIN
        }

        `when`(appUserRepository.findById(userId)).thenReturn(Optional.of(rootUser))
        
        val request = UserUpdateRequest(
            username = rootAdminUsername,
            email = "root@example.com",
            displayName = "Root",
            role = Role.USER,
            newPassword = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            appUserService.updateUser(userId, request)
        }

        assertEquals("The root administrator account cannot be demoted.", exception.message)
    }

    @Test
    fun `should prevent demoting last admin`() {
        val userId = UUID.randomUUID()
        val lastAdmin = AppUser().apply {
            this.id = userId
            this.username = "another_admin"
            this.role = Role.ADMIN
        }

        `when`(appUserRepository.findById(userId)).thenReturn(Optional.of(lastAdmin))
        `when`(appUserRepository.countByRole(Role.ADMIN)).thenReturn(1L)
        
        val request = UserUpdateRequest(
            username = "another_admin",
            email = "admin@example.com",
            displayName = "Admin",
            role = Role.USER,
            newPassword = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            appUserService.updateUser(userId, request)
        }

        assertEquals("Cannot demote the last remaining administrator.", exception.message)
    }

    @Test
    fun `should allow demoting admin if another admin exists`() {
        val userId = UUID.randomUUID()
        val adminToDemote = AppUser().apply {
            this.id = userId
            this.username = "secondary_admin"
            this.role = Role.ADMIN
        }

        `when`(appUserRepository.findById(userId)).thenReturn(Optional.of(adminToDemote))
        `when`(appUserRepository.countByRole(Role.ADMIN)).thenReturn(2L)
        `when`(appUserRepository.save(adminToDemote)).thenReturn(adminToDemote)
        
        val request = UserUpdateRequest(
            username = "secondary_admin",
            email = "admin2@example.com",
            displayName = "Secondary Admin",
            role = Role.USER,
            newPassword = null
        )

        val updatedUser = appUserService.updateUser(userId, request)

        assertEquals(Role.USER, updatedUser.role)
    }
}
