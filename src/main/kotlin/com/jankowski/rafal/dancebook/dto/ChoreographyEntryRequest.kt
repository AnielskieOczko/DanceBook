package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Size
import java.util.UUID

data class ChoreographyEntryRequest(
    val entryType: String = "FIGURE",
    val danceFigureId: UUID? = null,
    val sectionLabel: String? = null,
    val lineIndicator: String? = null,
    @field:Size(max = 500, message = "{validation.choreography_entry.notes_max}")
    val notes: String? = null
)
