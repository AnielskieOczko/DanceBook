package com.jankowski.rafal.dancebook.controller

import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(
    private val materialRepository: MaterialRepository,
    private val figureRepository: FigureRepository,
    private val danceTypeService: DanceTypeService,
    private val commentRepository: CommentRepository,
    private val appUserService: AppUserService
) {

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("materialCount", materialRepository.count())
        model.addAttribute("figureCount", figureRepository.count())
        model.addAttribute("danceTypeCount", danceTypeService.findAll().size)
        model.addAttribute("commentCount", commentRepository.count())

        // Current user display name for greeting
        val auth = SecurityContextHolder.getContext().authentication
        if (auth != null && auth.isAuthenticated && auth.principal != "anonymousUser") {
            val user = appUserService.getCurrentUser()
            model.addAttribute("displayName", user.displayName ?: user.username)
        }

        return "index"
    }

    @GetMapping("/ping")
    @org.springframework.web.bind.annotation.ResponseBody
    fun ping(): String {
        return "<span class=\"text-success font-bold\">HTMX is working!</span>"
    }
}