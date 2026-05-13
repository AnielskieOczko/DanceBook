package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Makes the current user and their visible lists available 
 * to ALL Thymeleaf templates (for the navbar).
 */
@ControllerAdvice
class NavbarAdvice(
    private val customListService: CustomListService,
    private val appUserService: AppUserService,
    private val activityEventService: ActivityEventService
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

    @ModelAttribute("unreadNotificationCount")
    fun unreadNotificationCount(): Long {
        val auth = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth.principal == "anonymousUser") return 0
        val user = appUserService.getCurrentUser()
        return activityEventService.getUnreadCount(user.id!!)
    }

    @ModelAttribute("activeNav")
    fun activeNav(request: HttpServletRequest): String {
        val path = request.requestURI
        return when {
            path == "/" -> "home"
            path.startsWith("/materials") -> "materials"
            path.startsWith("/lists") -> "lists"
            path.startsWith("/dance-types") -> "dance-types"
            path.startsWith("/dance-categories") -> "dance-categories"
            path.startsWith("/admin") -> "admin"
            path.startsWith("/profile") -> "profile"
            else -> ""
        }
    }
}

