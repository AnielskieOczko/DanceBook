package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.AppLocales
import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import com.jankowski.rafal.dancebook.service.AppUserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/profile")
@PreAuthorize("isAuthenticated()")
class ProfileController(
    private val appUserService: AppUserService,
    private val messageSource: MessageSource
) {
    @GetMapping
    fun showProfile(model: Model): String {
        val user = appUserService.getCurrentUser()
        model.addAttribute("appUser", user)
        model.addAttribute("passwordChangeRequest", PasswordChangeRequest())
        model.addAttribute("languageOptions", AppLocales.options())
        return "profile/index"
    }

    @PostMapping("/locale", "/language")
    fun updateLocale(
        @RequestParam(required = false) locale: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = appUserService.getCurrentUser()
        val trimmed = locale?.trim()

        if (!trimmed.isNullOrEmpty() && !AppLocales.isSupported(trimmed)) {
            redirectAttributes.addFlashAttribute("localeErrorMsg", true)
            if (request.getHeader("HX-Request") != null) {
                response.setHeader("HX-Redirect", "/profile")
            }
            return "redirect:/profile"
        }

        try {
            appUserService.updateLocale(user.id!!, trimmed)
            redirectAttributes.addFlashAttribute("localeSuccessMsg", true)
        } catch (e: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute("localeErrorMsg", true)
        }

        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", "/profile")
        }
        return "redirect:/profile"
    }

    @PostMapping("/password")
    fun changePassword(
        @Valid @ModelAttribute("passwordChangeRequest") request: PasswordChangeRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        val user = appUserService.getCurrentUser()
        model.addAttribute("appUser", user)
        model.addAttribute("languageOptions", AppLocales.options())

        if (bindingResult.hasErrors()) {
            return "profile/index :: passwordSection"
        }

        try {
            appUserService.changePassword(user.id!!, request)
            val successMsg = messageSource.getMessage(
                "profile.password_updated",
                null,
                "Password successfully updated! It will be used on your next login.",
                LocaleContextHolder.getLocale()
            )
            model.addAttribute("passwordSuccessMsg", successMsg)
            model.addAttribute("passwordChangeRequest", PasswordChangeRequest())
        } catch (e: Exception) {
            bindingResult.reject("passwordError", e.message ?: "Failed to update password")
        }

        return "profile/index :: passwordSection"
    }
}
