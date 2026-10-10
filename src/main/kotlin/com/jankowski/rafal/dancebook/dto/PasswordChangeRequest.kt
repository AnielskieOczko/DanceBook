package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PasswordChangeRequest(
    @field:NotBlank(message = "{validation.password.current_empty}")
    val currentPassword: String = "",

    @field:NotBlank(message = "{validation.password.new_empty}")
    @field:Size(min = 8, message = "{validation.password.new_length}")
    val newPassword: String = "",

    @field:NotBlank(message = "{validation.password.confirm_empty}")
    val confirmNewPassword: String = ""
)
