package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class ChoreographyEntryRequest(
    val entryType: String = "FIGURE",
    val danceFigureId: UUID? = null,
    val sectionLabel: String? = null,
    val lineIndicator: String? = null,
    val notes: String? = null
)
