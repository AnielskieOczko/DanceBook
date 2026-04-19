package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserCreateRequest(
    @field:NotBlank(message = "Username cannot be empty")
    val username: String,

    @field:NotBlank(message = "Email cannot be empty")
    @field:Email(message = "Must be a valid email format")
    val email: String,

    @field:NotBlank(message = "Display name cannot be empty")
    val displayName: String,

    @field:NotBlank(message = "Password cannot be empty")
    @field:Size(min = 8, message = "Password must be at least 8 characters long")
    val password: String,

    val role: Role = Role.USER
)
