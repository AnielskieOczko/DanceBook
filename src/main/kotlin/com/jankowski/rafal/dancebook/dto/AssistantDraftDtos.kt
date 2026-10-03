package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import java.util.UUID

/** One labelled line of a draft card, for example "Session" / "Standard group class". */
data class DraftField(val label: String, val value: String)

/** A figure on a note draft: its name, its timing ("S Q Q") when it has one, and its page. */
data class DraftFigureLine(val name: String, val timing: String?, val url: String)

/**
 * Everything a draft card shows. [savedUrl] is set once the draft is `SAVED`. [notice] is the
 * error of a failed Save (draft still `PENDING`) or a caveat of a Save that went through.
 */
data class DraftView(
    val id: UUID,
    val kind: DraftKind,
    val status: DraftStatus,
    val heading: String,
    val fields: List<DraftField>,
    val figures: List<DraftFigureLine>,
    val savedUrl: String?,
    val notice: String?
)
