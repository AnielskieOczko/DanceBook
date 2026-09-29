package com.jankowski.rafal.dancebook.dto

import java.util.UUID

/** Where the assistant widget was opened. The client sends this pair and nothing else about the page. */
enum class PageContextType { HOME, NOTE, FIGURE, SESSION, CHOREOGRAPHY, OTHER }

data class PageContext(
    val type: PageContextType = PageContextType.OTHER,
    val id: UUID? = null
)

/** A [PageContext] whose name the server has looked up itself. */
data class ResolvedPage(
    val type: PageContextType,
    val id: UUID?,
    val name: String?
) {
    /** The words after "Looking at:". Null when there is nothing worth saying. */
    val label: String?
        get() = name ?: when (type) {
            PageContextType.HOME -> "Home"
            PageContextType.NOTE -> "this note"
            PageContextType.FIGURE -> "this figure"
            PageContextType.SESSION -> "this session"
            PageContextType.CHOREOGRAPHY -> "this choreography"
            PageContextType.OTHER -> null
        }
}
