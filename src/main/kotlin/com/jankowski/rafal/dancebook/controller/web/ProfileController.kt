package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import com.jankowski.rafal.dancebook.service.AppUserService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/profile")
@PreAuthorize("isAuthenticated()")
class ProfileController(
    private val appUserService: AppUserService
) {
    @GetMapping
    fun showProfile(model: Model): String {
        val user = appUserService.getCurrentUser()
        model.addAttribute("appUser", user)
        return "profile/index"
    }

    @PostMapping("/password")
    fun changePassword(
        @jakarta.validation.Valid request: PasswordChangeRequest,
        model: Model
    ): String {
        val user = appUserService.getCurrentUser()
        model.addAttribute("appUser", user)
        
        try {
            appUserService.changePassword(user.id!!, request)
            model.addAttribute("passwordSuccessMsg", "Password successfully updated! It will be used on your next login.")
        } catch (e: Exception) {
            model.addAttribute("passwordErrorMsg", e.message ?: "Failed to update password")
        }
        
        return "profile/index :: passwordSection"
    }
}
