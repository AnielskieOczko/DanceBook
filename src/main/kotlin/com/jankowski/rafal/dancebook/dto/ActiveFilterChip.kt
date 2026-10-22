package com.jankowski.rafal.dancebook.dto

/**
 * Represents an active filter displayed as a removable chip on mobile lists (Issue #366).
 */
data class ActiveFilterChip(
    val filterName: String,
    val filterValue: String,
    val label: String
)
