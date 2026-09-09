package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingTimeline

/** One window of the signed-in user's training history, newest first. */
interface TrainingTimelineService {

    /** @param page zero-based; page 0 is the window that straddles today. */
    fun timelineForCurrentUser(page: Int): TrainingTimeline
}
