package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.util.UUID

@Controller
class NotificationController(
    private val activityEventService: ActivityEventService,
    private val appUserService: AppUserService
) {

    @GetMapping("/notifications")
    fun notificationDropdown(model: Model): String {
        val user = appUserService.getCurrentUser()
        val unread = activityEventService.getUnreadEvents(user.id!!)
        model.addAttribute("unreadEvents", unread.take(10))
        model.addAttribute("unreadCount", unread.size)
        return "notifications/dropdown :: notificationPanel"
    }

    @GetMapping("/notifications/count")
    @ResponseBody
    fun unreadCount(): String {
        val user = appUserService.getCurrentUser()
        val count = activityEventService.getUnreadCount(user.id!!)
        return if (count > 0) {
            """<span class="absolute -top-1 -right-1 min-w-[18px] h-[18px] flex items-center justify-center bg-error text-on-error text-[10px] font-bold rounded-full px-1">$count</span>"""
        } else {
            ""
        }
    }

    @PostMapping("/notifications/mark-read")
    @ResponseBody
    fun markAllAsRead(): String {
        val user = appUserService.getCurrentUser()
        activityEventService.markAllAsRead(user.id!!)
        return ""
    }

    @PostMapping("/notifications/{id}/mark-read")
    @ResponseBody
    fun markAsRead(@PathVariable id: UUID): String {
        val user = appUserService.getCurrentUser()
        activityEventService.markAsRead(id, user.id!!)
        return ""
    }

    @GetMapping("/activity-history")
    fun activityHistory(model: Model): String {
        val events = activityEventService.getAllEvents(PageRequest.of(0, 50))
        model.addAttribute("events", events.content)
        model.addAttribute("pageTitle", "Activity History")
        return "notifications/history"
    }
}
