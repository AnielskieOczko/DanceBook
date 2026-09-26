package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Attendance
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingCalendar
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
        awaitingConfirmation: Boolean? = null,
        calendarId: UUID? = null
    ): Specification<TrainingEvent> {
        return Specification { root, query, cb ->
            val predicates = mutableListOf<Predicate>()

            val attendance = if (createdBy != null && (!attendanceStatuses.isNullOrEmpty() || awaitingConfirmation == true)) {
                val att = root.join<TrainingEvent, Attendance>("attendances", JoinType.LEFT)
                att.on(cb.equal(att.get<AppUser>("user"), createdBy))
                query?.distinct(true)
                att
            } else null

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

            if (!attendanceStatuses.isNullOrEmpty() && attendance != null) {
                val statusPath = attendance.get<AttendanceStatus>("status")
                if (AttendanceStatus.PLANNED in attendanceStatuses) {
                    predicates.add(cb.or(statusPath.`in`(attendanceStatuses), cb.isNull(statusPath)))
                } else {
                    predicates.add(statusPath.`in`(attendanceStatuses))
                }
            }

            if (!titleSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%${titleSearch.lowercase()}%"))
            }

            if (awaitingConfirmation == true) {
                // Mirrors TrainingEvent.isAwaitingConfirmationFor, expressed in SQL because a
                // Specification filters at the database rather than on loaded entities.
                if (attendance != null) {
                    val statusPath = attendance.get<AttendanceStatus>("status")
                    predicates.add(cb.or(cb.equal(statusPath, AttendanceStatus.PLANNED), cb.isNull(statusPath)))
                }
                predicates.add(cb.lessThan(root.get("endTime"), LocalDateTime.now()))
            }

            if (calendarId != null) {
                // Null means "All calendars" and adds no predicate at all, so the common case
                // keeps its existing query plan.
                predicates.add(cb.equal(root.get<TrainingCalendar>("calendar").get<UUID>("id"), calendarId))
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
