package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DriveInspection
import com.jankowski.rafal.dancebook.dto.LogPresentation

interface AdminStorageService {
    /** Lists the Drive folder and marks which files no note points at. Calls Google. */
    fun inspectDrive(): DriveInspection

    /** Cleanup runs, newest first; at most [limit] of them when given. */
    fun cleanupHistory(limit: Int? = null): List<LogPresentation>

    companion object {
        const val PREVIEW_ROWS = 5
    }
}
