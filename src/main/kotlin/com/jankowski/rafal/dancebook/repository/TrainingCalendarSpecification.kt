package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Share
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.Visibility
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object TrainingCalendarSpecification {

    fun visibleTo(user: AppUser?): Specification<TrainingCalendar> {
        return Specification { root, query, cb ->
            visibilityPredicate(root, query, cb, user)
        }
    }

    fun byId(id: UUID): Specification<TrainingCalendar> {
        return Specification { root, _, cb ->
            cb.equal(root.get<UUID>("id"), id)
        }
    }

    fun visibilityPredicate(
        from: From<*, TrainingCalendar>,
        query: CriteriaQuery<*>?,
        cb: CriteriaBuilder,
        user: AppUser?
    ): Predicate {
        if (user?.role == Role.ADMIN) {
            return cb.conjunction()
        }
        if (user == null) {
            return cb.equal(from.get<Visibility>("visibility"), Visibility.PUBLIC)
        }

        val shareSubquery = query?.subquery(Long::class.java)
        val sharePredicate = if (shareSubquery != null) {
            val shareRoot = shareSubquery.from(Share::class.java)
            shareSubquery.select(cb.literal(1L))
            shareSubquery.where(
                cb.equal(shareRoot.get<String>("itemType"), "TRAINING_CALENDAR"),
                cb.equal(shareRoot.get<UUID>("itemId"), from.get<UUID>("id")),
                cb.equal(shareRoot.get<AppUser>("granteeUser"), user)
            )
            cb.exists(shareSubquery)
        } else cb.disjunction()

        return cb.or(
            cb.equal(from.get<AppUser>("owner"), user),
            cb.equal(from.get<Visibility>("visibility"), Visibility.PUBLIC),
            sharePredicate
        )
    }
}
