package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserCreateRequest(
    @field:NotBlank(message = "{validation.user.username_empty}")
    val username: String = "",

    @field:NotBlank(message = "{validation.user.email_empty}")
    @field:Email(message = "{validation.user.email_invalid}")
    val email: String = "",

    @field:NotBlank(message = "{validation.user.display_name_empty}")
    val displayName: String = "",

    @field:NotBlank(message = "{validation.user.password_empty}")
    @field:Size(min = 8, message = "{validation.user.password_length}")
    val password: String = "",

    val role: Role = Role.USER
)
