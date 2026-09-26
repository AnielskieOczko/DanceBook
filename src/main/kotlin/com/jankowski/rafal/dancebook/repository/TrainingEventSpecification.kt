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
import com.jankowski.rafal.dancebook.model.CalendarMember
import com.jankowski.rafal.dancebook.model.CalendarMemberState
import com.jankowski.rafal.dancebook.model.Role
import java.time.LocalDateTime
import java.util.UUID

object TrainingEventSpecification {

    fun withFilters(
        user: AppUser? = null,
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

            val attendanceUser = user ?: createdBy
            val attendance = if (attendanceUser != null && (!attendanceStatuses.isNullOrEmpty() || awaitingConfirmation == true)) {
                val att = root.join<TrainingEvent, Attendance>("attendances", JoinType.LEFT)
                att.on(cb.equal(att.get<AppUser>("user"), attendanceUser))
                query?.distinct(true)
                att
            } else null

            if (calendarId != null) {
                // Scoped to a specific calendar
                predicates.add(cb.equal(root.get<TrainingCalendar>("calendar").get<UUID>("id"), calendarId))
            }

            if (user != null) {
                // Personal view for this user: calendars they own or actively belong to
                val cal = root.get<TrainingCalendar>("calendar")
                val calOwnerPredicate = cb.equal(cal.get<AppUser>("owner"), user)

                val memberSubquery = query?.subquery(Long::class.java)
                val memberPredicate = if (memberSubquery != null) {
                    val memberRoot = memberSubquery.from(CalendarMember::class.java)
                    memberSubquery.select(cb.literal(1L))
                    memberSubquery.where(
                        cb.equal(memberRoot.get<TrainingCalendar>("calendar"), cal),
                        cb.equal(memberRoot.get<AppUser>("user"), user),
                        cb.equal(memberRoot.get<CalendarMemberState>("state"), CalendarMemberState.ACTIVE)
                    )
                    cb.exists(memberSubquery)
                } else cb.disjunction()

                val legacyNullCalPredicate = cb.and(cb.isNull(cal), cb.equal(root.get<AppUser>("createdBy"), user))
                predicates.add(cb.or(calOwnerPredicate, memberPredicate, legacyNullCalPredicate))
            } else if (createdBy != null) {
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

            cb.and(*predicates.toTypedArray())
        }
    }
}
