package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarMemberService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.stereotype.Component

@Component
class CalendarInviteLoginListener(
    private val appUserService: AppUserService,
    private val calendarMemberService: CalendarMemberService
) : ApplicationListener<AuthenticationSuccessEvent> {

    companion object {
        private val log = LoggerFactory.getLogger(CalendarInviteLoginListener::class.java)
    }

    override fun onApplicationEvent(event: AuthenticationSuccessEvent) {
        val user = try {
            appUserService.getCurrentUserOrNull()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            calendarMemberService.claimInvitesFor(user)
        } catch (e: Exception) {
            log.error("Failed to claim calendar invites on login for user '{}'", user.username, e)
        }
    }
}
