package com.jankowski.rafal.dancebook.dto

data class BulkEditResult(
    val updatedCount: Int,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val actionDescription: String = "Updated",
    val errorMessage: String? = null
) {
    val message: String
        get() {
            if (errorMessage != null) return errorMessage

            val updatedPart = when (updatedCount) {
                0 -> if (failedCount > 0 || skippedCount > 0) "No sessions were updated" else "No sessions were selected"
                1 -> "$actionDescription 1 session"
                else -> "$actionDescription $updatedCount sessions"
            }

            val notes = mutableListOf<String>()
            if (failedCount > 0) {
                notes.add(if (failedCount == 1) "1 session could not be updated" else "$failedCount sessions could not be updated")
            }
            if (skippedCount > 0) {
                notes.add(
                    if (skippedCount == 1) "1 session was skipped because the style breakdown exceeds its duration"
                    else "$skippedCount sessions were skipped because the style breakdown exceeds their duration"
                )
            }

            return if (notes.isNotEmpty()) {
                "$updatedPart (${notes.joinToString(", ")})."
            } else {
                "$updatedPart."
            }
        }
}
