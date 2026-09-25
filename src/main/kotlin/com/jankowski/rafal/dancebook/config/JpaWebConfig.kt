package com.jankowski.rafal.dancebook.config

import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Open-in-view for every request except video streaming.
 *
 * Open-in-view keeps the request's database connection until the response is complete, and a
 * video response lasts as long as the stream, so each viewer would pin a pooled connection.
 * Boot's own registration is switched off (`spring.jpa.open-in-view=false`) because it cannot
 * exclude a path; this one does. The factory is optional so `@WebMvcTest` slices, which have
 * no JPA, still load this configuration.
 */
@Configuration
class JpaWebConfig(
    private val entityManagerFactory: ObjectProvider<EntityManagerFactory>
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        entityManagerFactory.ifAvailable { emf ->
            val interceptor = OpenEntityManagerInViewInterceptor().apply { entityManagerFactory = emf }
            registry.addWebRequestInterceptor(interceptor)
                .excludePathPatterns("/materials/*/video")
        }
    }
}
