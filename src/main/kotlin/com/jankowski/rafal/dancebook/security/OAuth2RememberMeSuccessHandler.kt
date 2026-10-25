package com.jankowski.rafal.dancebook.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.RememberMeServices
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler

class OAuth2RememberMeSuccessHandler(
    private val rememberMeServices: RememberMeServices,
    defaultTargetUrl: String = "/"
) : SavedRequestAwareAuthenticationSuccessHandler() {

    init {
        setDefaultTargetUrl(defaultTargetUrl)
        setAlwaysUseDefaultTargetUrl(true)
    }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        rememberMeServices.loginSuccess(request, response, authentication)
        super.onAuthenticationSuccess(request, response, authentication)
    }
}
