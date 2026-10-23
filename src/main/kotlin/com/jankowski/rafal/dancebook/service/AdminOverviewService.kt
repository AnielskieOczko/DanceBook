package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AdminOverview
import com.jankowski.rafal.dancebook.dto.AdminUsersView
import com.jankowski.rafal.dancebook.model.AppUser

interface AdminOverviewService {
    /** Counts and current values for the admin dashboard. [viewer] decides which calendar shows as the default. */
    fun overview(viewer: AppUser?): AdminOverview

    /** When the last real (not simulated) Drive cleanup ran, or null if none has. */
    fun lastCleanupAt(): java.time.LocalDateTime?

    /** The first [limit] users, or every user when [all] is set. */
    fun users(all: Boolean, limit: Int = PREVIEW_USER_ROWS): AdminUsersView

    companion object {
        const val PREVIEW_USER_ROWS = 5
    }
}
