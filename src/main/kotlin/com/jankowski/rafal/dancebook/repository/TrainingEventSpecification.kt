package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object TrainingEventSpecification {

    fun withFilters(
        createdBy: AppUser? = null,
        eventTypes: List<TrainingEventType>? = null,
        categoryIds: List<UUID>? = null,
        attendanceStatuses: List<AttendanceStatus>? = null,
        titleSearch: String? = null
    ): Specification<TrainingEvent> {
        return Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()

            if (createdBy != null) {
                predicates.add(cb.equal(root.get<AppUser>("createdBy"), createdBy))
            }

            if (!eventTypes.isNullOrEmpty()) {
                predicates.add(root.get<TrainingEventType>("eventType").`in`(eventTypes))
            }

            if (!categoryIds.isNullOrEmpty()) {
                predicates.add(root.get<DanceCategory>("danceCategory").get<UUID>("id").`in`(categoryIds))
            }

            if (!attendanceStatuses.isNullOrEmpty()) {
                predicates.add(root.get<AttendanceStatus>("attendanceStatus").`in`(attendanceStatuses))
            }

            if (!titleSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%${titleSearch.lowercase()}%"))
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
