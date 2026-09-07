package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.LocalDateTime
import java.util.UUID

object TrainingEventSpecification {

    fun withFilters(
        createdBy: AppUser? = null,
        eventTypes: List<TrainingEventType>? = null,
        categoryIds: List<UUID>? = null,
        attendanceStatuses: List<AttendanceStatus>? = null,
        titleSearch: String? = null,
        awaitingConfirmation: Boolean? = null
    ): Specification<TrainingEvent> {
        return Specification { root, query, cb ->
            val predicates = mutableListOf<Predicate>()

            if (createdBy != null) {
                predicates.add(cb.equal(root.get<AppUser>("createdBy"), createdBy))
            }

            if (!eventTypes.isNullOrEmpty()) {
                predicates.add(root.get<TrainingEventType>("eventType").`in`(eventTypes))
            }

            if (!categoryIds.isNullOrEmpty()) {
                // Styles live on the segment child rows now, so this has to join. The join
                // multiplies rows, so a mixed-style event matching two selected categories
                // would otherwise come back once per matching segment.
                val segments = root.join<TrainingEvent, TrainingEventSegment>("segments", JoinType.INNER)
                predicates.add(segments.get<DanceCategory>("danceCategory").get<UUID>("id").`in`(categoryIds))
                query?.distinct(true)
            }

            if (!attendanceStatuses.isNullOrEmpty()) {
                predicates.add(root.get<AttendanceStatus>("attendanceStatus").`in`(attendanceStatuses))
            }

            if (!titleSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%${titleSearch.lowercase()}%"))
            }

            if (awaitingConfirmation == true) {
                // Mirrors TrainingEvent.isAwaitingConfirmation, expressed in SQL because a
                // Specification filters at the database rather than on loaded entities. A
                // change to that rule needs the same change here.
                predicates.add(cb.equal(root.get<AttendanceStatus>("attendanceStatus"), AttendanceStatus.PLANNED))
                predicates.add(cb.lessThan(root.get("endTime"), LocalDateTime.now()))
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
