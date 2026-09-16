package com.jankowski.rafal.dancebook.dto

data class BulkDeleteResult(
    val deletedCount: Int,
    val failedCount: Int = 0,
    val errorMessage: String? = null
) {
    val message: String
        get() {
            if (errorMessage != null) return errorMessage
            val deletedPart = when (deletedCount) {
                0 -> if (failedCount > 0) "No sessions were deleted" else "No sessions were selected"
                1 -> "Deleted 1 session"
                else -> "Deleted $deletedCount sessions"
            }
            return if (failedCount > 0) {
                val failedPart = when (failedCount) {
                    1 -> "1 session could not be deleted"
                    else -> "$failedCount sessions could not be deleted"
                }
                "$deletedPart ($failedPart)."
            } else {
                "$deletedPart."
            }
        }
}
