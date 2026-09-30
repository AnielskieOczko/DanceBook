package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Share
import com.jankowski.rafal.dancebook.model.Visibility
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

import jakarta.persistence.criteria.From

object MaterialSpecification {

    fun visibleTo(user: AppUser?): Specification<Material> {
        return Specification { root, query, cb ->
            visibilityPredicate(root, query, cb, user)
        }
    }

    fun byId(id: UUID): Specification<Material> {
        return Specification { root, _, cb ->
            cb.equal(root.get<UUID>("id"), id)
        }
    }

    fun visibilityPredicate(
        from: From<*, Material>,
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
                cb.equal(shareRoot.get<String>("itemType"), "MATERIAL"),
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

    fun filterCriteria(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        nameSearch: String? = null
    ): Specification<Material> {
        return Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()

            if (!typeIds.isNullOrEmpty()) {
                predicates.add(root.get<DanceType>("danceType").get<UUID>("id").`in`(typeIds))
            }

            if (!categoryIds.isNullOrEmpty()) {
                predicates.add(root.get<DanceType>("danceType").get<DanceCategory>("category").get<UUID>("id").`in`(categoryIds))
            }

            minRating?.let {
                predicates.add(cb.greaterThanOrEqualTo(root.get("rating"), it))
            }

            if (!nameSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%${nameSearch.lowercase()}%"))
            }

            if (predicates.isEmpty()) cb.conjunction() else cb.and(*predicates.toTypedArray())
        }
    }

    /**
     * Free-text search over a note's title and text: every word must appear in one of the two.
     * [figureId] keeps only notes that pin that catalog figure, [danceTypeId] only that style.
     * Combine with [visibleTo]; this alone applies no access rule.
     */
    fun textSearch(query: String?, figureId: UUID?, danceTypeId: UUID?): Specification<Material> {
        return Specification { root, criteria, cb ->
            val predicates = mutableListOf<Predicate>()

            query.orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).forEach { term ->
                val like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                predicates.add(
                    cb.or(
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get<String>("description"), "")), like, '\\')
                    )
                )
            }

            danceTypeId?.let {
                predicates.add(cb.equal(root.get<DanceType>("danceType").get<UUID>("id"), it))
            }

            figureId?.let {
                val pins = criteria!!.subquery(Long::class.java)
                val pin = pins.from(Figure::class.java)
                pins.select(cb.literal(1L)).where(
                    cb.equal(pin.get<Material>("material"), root),
                    cb.equal(pin.get<DanceFigure>("danceFigure").get<UUID>("id"), it)
                )
                predicates.add(cb.exists(pins))
            }

            cb.and(*predicates.toTypedArray())
        }
    }

    fun withTextSearch(user: AppUser?, query: String?, figureId: UUID?, danceTypeId: UUID?): Specification<Material> {
        return visibleTo(user).and(textSearch(query, figureId, danceTypeId))
    }

    fun withFilters(
        user: AppUser? = null,
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        nameSearch: String? = null
    ): Specification<Material> {
        return visibleTo(user).and(filterCriteria(typeIds, categoryIds, minRating, nameSearch))
    }
}
