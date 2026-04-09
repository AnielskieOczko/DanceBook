package com.jankowski.rafal.dancebook.controller.web

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

@Controller
class UserSwitchController {

    @PostMapping("/switch-user")
    fun switchUser(
        @RequestParam username: String,
        response: HttpServletResponse,
        @RequestHeader(value = "Referer", required = false) referer: String?
    ): String {
        val cookie = Cookie("CURRENT_USER", username).apply {
            path = "/"
            maxAge = 3600 * 24 * 30 // 30 days
        }
        response.addCookie(cookie)
        return "redirect:${referer ?: "/"}"
    }
}
