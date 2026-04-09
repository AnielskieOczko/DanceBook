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
    fun navLists() = customListService.findVisibleByCurrentUser()

    @ModelAttribute("currentUser")
    fun currentUser() = appUserService.getCurrentUser()

    @ModelAttribute("allUsers")
    fun allUsers() = appUserService.findAll()
}
