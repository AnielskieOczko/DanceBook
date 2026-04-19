package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PasswordChangeRequest(
    @field:NotBlank(message = "Current password cannot be empty")
    val currentPassword: String,

    @field:NotBlank(message = "New password cannot be empty")
    @field:Size(min = 8, message = "New password must be at least 8 characters long")
    val newPassword: String,

    @field:NotBlank(message = "Please confirm the new password")
    val confirmNewPassword: String
)
