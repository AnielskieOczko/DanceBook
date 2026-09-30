package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import jakarta.persistence.EntityNotFoundException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns "the user is on this page" into a name the model can be told.
 *
 * The widget sends a type and an id. The name is always looked up here, through the service,
 * so it passes the same access rule as the page itself and a client cannot put words in the
 * prompt by inventing a name.
 */
@Component
class AssistantPageContextResolver(
    private val materialService: MaterialService,
    private val danceFigureService: DanceFigureService,
    private val trainingEventService: TrainingEventService,
    private val choreographyService: ChoreographyService
) {

    private val uuid = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    private val routes = listOf(
        Regex("^/materials/$uuid(?:/.*)?$") to PageContextType.NOTE,
        Regex("^/dance-figures/$uuid(?:/.*)?$") to PageContextType.FIGURE,
        Regex("^/training-events/$uuid(?:/.*)?$") to PageContextType.SESSION,
        Regex("^/choreographies/$uuid(?:/.*)?$") to PageContextType.CHOREOGRAPHY
    )

    fun fromPath(path: String): PageContext {
        if (path == "/") return PageContext(PageContextType.HOME)
        for ((regex, type) in routes) {
            val match = regex.matchEntire(path) ?: continue
            return PageContext(type, UUID.fromString(match.groupValues[1]))
        }
        return PageContext(PageContextType.OTHER)
    }

    fun resolve(page: PageContext): ResolvedPage {
        val id = page.id ?: return ResolvedPage(page.type, null, null)
        val name = try {
            when (page.type) {
                PageContextType.NOTE -> materialService.findById(id).name
                PageContextType.FIGURE -> danceFigureService.findById(id).name
                PageContextType.SESSION -> trainingEventService.findById(id).title
                PageContextType.CHOREOGRAPHY -> choreographyService.findById(id).name
                PageContextType.HOME, PageContextType.OTHER -> null
            }
        } catch (e: EntityNotFoundException) {
            null
        } catch (e: AccessDeniedException) {
            null
        }
        return ResolvedPage(page.type, id, name?.takeIf { it.isNotBlank() })
    }
}
