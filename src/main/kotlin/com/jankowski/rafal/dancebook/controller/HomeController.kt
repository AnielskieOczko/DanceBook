package com.jankowski.rafal.dancebook.controller

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.CommentSpecification
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.MaterialSpecification
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(
    private val materialRepository: MaterialRepository,
    private val danceFigureRepository: DanceFigureRepository,
    private val danceTypeService: DanceTypeService,
    private val commentRepository: CommentRepository,
    private val appUserService: AppUserService,
    private val activityEventService: ActivityEventService
) {

    @GetMapping("/")
    fun home(model: Model): String {
        val currentUser = appUserService.getCurrentUserOrNull()

        model.addAttribute("materialCount", materialRepository.count(MaterialSpecification.visibleTo(currentUser)))
        model.addAttribute("figureCount", danceFigureRepository.count())
        model.addAttribute("danceTypeCount", danceTypeService.findAll().size)
        model.addAttribute("commentCount", commentRepository.count(CommentSpecification.visibleTo(currentUser)))

        if (currentUser != null) {
            model.addAttribute("displayName", currentUser.displayName.ifBlank { currentUser.username })
        }

        // Recent activity events for timeline
        model.addAttribute("recentEvents", activityEventService.getRecentEvents(10))

        return "index"
    }

    @GetMapping("/ping")
    @org.springframework.web.bind.annotation.ResponseBody
    fun ping(): String {
        return "<span class=\"text-success font-bold\">HTMX is working!</span>"
    }
}