package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.AssistantFeature
import com.jankowski.rafal.dancebook.dto.PageContext
import org.springframework.stereotype.Component

/** What the layout needs to draw the assistant: the page it is on and the "Looking at" label. */
data class AssistantNav(val page: PageContext, val label: String?)

/**
 * The single question `NavbarAdvice` asks. It returns null when the feature is off or nobody
 * is signed in, and `NavbarAdvice` reaches it through an `ObjectProvider`, so a `@WebMvcTest`
 * slice that never loads this bean simply gets no assistant.
 */
@Component
class AssistantNavSupport(
    private val feature: AssistantFeature,
    private val pageContexts: AssistantPageContextResolver,
    private val appUserService: AppUserService
) {
    fun forPath(path: String): AssistantNav? {
        if (!feature.enabled) return null
        if (appUserService.getCurrentUserOrNull() == null) return null
        val page = pageContexts.fromPath(path)
        return AssistantNav(page, pageContexts.resolve(page).label)
    }
}
