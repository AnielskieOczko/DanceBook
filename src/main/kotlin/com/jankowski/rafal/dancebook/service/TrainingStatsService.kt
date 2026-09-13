package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import java.util.UUID

/** Read-only projection of the signed-in user's training history. */
interface TrainingStatsService {

    fun statsForCurrentUser(period: StatsPeriod, calendarId: UUID? = null): TrainingStats
}
