package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.service.AppUserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

class UserLocaleResolver(
    private val appUserServiceProvider: ObjectProvider<AppUserService>? = null,
    private val defaultFallbackResolver: LocaleResolver = AcceptHeaderLocaleResolver().apply {
        setDefaultLocale(AppLocales.DEFAULT)
        supportedLocales = AppLocales.ALL
    }
) : LocaleResolver {

    companion object {
        private val log = LoggerFactory.getLogger(UserLocaleResolver::class.java)
        val DEFAULT_LOCALE: Locale = AppLocales.DEFAULT
        val SUPPORTED_LOCALES: List<Locale> = AppLocales.ALL
    }

    constructor(
        appUserService: AppUserService?,
        defaultFallbackResolver: LocaleResolver = AcceptHeaderLocaleResolver().apply {
            setDefaultLocale(AppLocales.DEFAULT)
            supportedLocales = AppLocales.ALL
        }
    ) : this(
        appUserServiceProvider = appUserService?.let { StaticProvider(it) },
        defaultFallbackResolver = defaultFallbackResolver
    )

    private val appUserService: AppUserService?
        get() = appUserServiceProvider?.ifAvailable

    override fun resolveLocale(request: HttpServletRequest): Locale {
        val user = try {
            appUserService?.getCurrentUserOrNull()
        } catch (e: Exception) {
            log.trace("Could not resolve current user for locale: {}", e.message)
            null
        }

        if (user != null && !user.locale.isNullOrBlank()) {
            val userLocale = AppLocales.parseLocale(user.locale)
            if (userLocale != null) {
                return userLocale
            }
        }

        return defaultFallbackResolver.resolveLocale(request)
    }

    override fun setLocale(request: HttpServletRequest, response: HttpServletResponse?, locale: Locale?) {
        val user = try {
            appUserService?.getCurrentUserOrNull()
        } catch (e: Exception) {
            log.trace("Could not resolve current user to set locale: {}", e.message)
            null
        }

        if (user != null && user.id != null) {
            val lang = locale?.language?.takeIf { it.isNotBlank() }
            appUserService?.updateLocale(user.id!!, lang)
        } else {
            defaultFallbackResolver.setLocale(request, response, locale)
        }
    }

    private class StaticProvider<T : Any>(private val instance: T) : ObjectProvider<T> {
        override fun getObject(vararg args: Any?): T = instance
        override fun getObject(): T = instance
        override fun getIfAvailable(): T = instance
        override fun getIfUnique(): T = instance
    }
}
