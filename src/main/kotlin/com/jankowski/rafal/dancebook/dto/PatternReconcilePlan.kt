package com.jankowski.rafal.dancebook.dto

/**
 * Details of how an edit to a repeating series' recurrence pattern will affect its occurrences.
 */
data class PatternReconcilePlan(
    val createdCount: Int,
    val movedCount: Int,
    val removedCount: Int,
    val droppedRecordedCount: Int,
    val targetTotalCount: Int
) {
    val hasPatternChanges: Boolean
        get() = createdCount > 0 || movedCount > 0 || removedCount > 0
}
