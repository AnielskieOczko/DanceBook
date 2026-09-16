package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class BulkSegmentsRequest(
    var sessionIds: List<UUID> = emptyList(),
    var segments: MutableList<TrainingEventSegmentRequest> = mutableListOf()
)
