package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DashboardView

/** Builds the signed-in user's home page. */
interface DashboardService {

    fun dashboardForCurrentUser(): DashboardView
}
