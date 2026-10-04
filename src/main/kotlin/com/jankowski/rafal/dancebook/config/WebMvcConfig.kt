package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.service.AppUserService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
class WebMvcConfig(
    @Value("\${app.storage.upload-dir:uploads}")
    private val uploadDir: String,
    private val calendarSyncInterceptor: CalendarSyncInterceptor
) : WebMvcConfigurer {

    @Bean
    fun localeResolver(appUserService: ObjectProvider<AppUserService>): LocaleResolver {
        return UserLocaleResolver(appUserService)
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val uploadPath = Paths.get(uploadDir).toAbsolutePath().toUri().toString()
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations(uploadPath)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(calendarSyncInterceptor)
            .addPathPatterns("/training-events", "/training-events/**")
    }
}
