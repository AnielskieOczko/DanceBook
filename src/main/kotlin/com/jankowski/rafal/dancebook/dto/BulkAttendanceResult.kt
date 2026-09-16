package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.AttendanceStatus

data class BulkAttendanceResult(
    val updatedCount: Int,
    val futureSkippedCount: Int,
    val status: AttendanceStatus
) {
    val message: String
        get() {
            val statusLabel = if (status == AttendanceStatus.ATTENDED) "attended" else "skipped"
            val updatedPart = when (updatedCount) {
                0 -> "No sessions were updated"
                1 -> "Marked 1 session as $statusLabel"
                else -> "Marked $updatedCount sessions as $statusLabel"
            }
            return if (futureSkippedCount > 0) {
                val skippedPart = when (futureSkippedCount) {
                    1 -> "1 future session was skipped because it has not happened yet"
                    else -> "$futureSkippedCount future sessions were skipped because they have not happened yet"
                }
                "$updatedPart ($skippedPart)."
            } else {
                "$updatedPart."
            }
        }
}
