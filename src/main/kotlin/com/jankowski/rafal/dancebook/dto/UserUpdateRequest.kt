package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class UserUpdateRequest(
    @field:NotBlank
    var username: String,
    @field:Email @field:NotBlank
    var email: String,
    @field:NotBlank
    var displayName: String,

    @field:NotNull
    var role: Role,
    var newPassword: String? = null
)