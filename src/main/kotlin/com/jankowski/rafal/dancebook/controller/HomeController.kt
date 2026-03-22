package com.jankowski.rafal.dancebook.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.jdbc.core.JdbcTemplate

@Controller
class HomeController {

    @GetMapping("/")
    fun home(model: Model): String {
        // Simple query to prove the DB connection works
        val count = 10

        // Pass the count to the Thymeleaf template
        model.addAttribute("danceTypeCount", count)

        // Return the name of the template (index.html)
        return "index"
    }

    // The HTMX endpoint
    @GetMapping("/ping")
    @org.springframework.web.bind.annotation.ResponseBody
    fun ping(): String {
        return "<span class=\"text-green-600 font-bold\">HTMX is working! 🎉</span>"
    }
}