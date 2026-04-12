package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Makes the current user and their visible lists available 
 * to ALL Thymeleaf templates (for the navbar).
 */
@ControllerAdvice
class NavbarAdvice(
    private val customListService: CustomListService,
    private val appUserService: AppUserService
) {

    @ModelAttribute("navLists")
    fun navLists(): List<Any> {
        val auth = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth.principal == "anonymousUser") return emptyList()
        return customListService.findVisibleByCurrentUser()
    }

    @ModelAttribute("currentUser")
    fun currentUser(): Any? {
        val auth = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth.principal == "anonymousUser") return null
        return appUserService.getCurrentUser()
    }
}
