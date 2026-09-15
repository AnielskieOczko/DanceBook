package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.service.CalendarSyncService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class CalendarSyncInterceptor(
    private val calendarSyncService: CalendarSyncService
) : HandlerInterceptor {

    companion object {
        private val log = LoggerFactory.getLogger(CalendarSyncInterceptor::class.java)
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if ("GET".equals(request.method, ignoreCase = true)) {
            try {
                calendarSyncService.syncIfDue()
            } catch (e: Exception) {
                log.error("Automatic calendar sync failed; continuing with request", e)
            }
        }
        return true
    }
}
