package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import java.util.UUID

enum class SearchHitType { NOTE, FIGURE }

/** How a result was found. SEMANTIC means the words did not match, only the meaning did. */
enum class SearchMatch { KEYWORD, SEMANTIC, BOTH }

/** Exactly one of [note] and [figure] is set, matching [type]. */
data class SearchHit(
    val type: SearchHitType,
    val id: UUID,
    val match: SearchMatch,
    val note: Material? = null,
    val figure: DanceFigure? = null
)

interface HybridSearchService {
    companion object {
        /** Cap on keyword results per kind (notes, figures). */
        const val DEFAULT_LIMIT = 20
        /** Cap on results added purely because they are semantically related. */
        const val SEMANTIC_LIMIT = 5
    }

    /**
     * One ranked list of notes and catalog figures the current user can see. Keyword matches come
     * from the existing note and figure searches; semantically related items from the knowledge
     * index are added without duplicates. If embeddings are unavailable, rate limited or failing,
     * the result is the keyword results alone.
     */
    fun search(query: String?, danceTypeId: UUID? = null, limit: Int = DEFAULT_LIMIT): List<SearchHit>
}
