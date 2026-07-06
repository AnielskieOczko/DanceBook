package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object DanceFigureSpecification {

    fun withFilters(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        danceClass: DanceClass? = null,
        nameSearch: String? = null,
        hasSteps: Boolean? = null
    ): Specification<DanceFigure> {
        return Specification { root, query, cb ->
            val predicates = mutableListOf<Predicate>()

            if (!typeIds.isNullOrEmpty()) {
                predicates.add(root.get<DanceType>("danceType").get<UUID>("id").`in`(typeIds))
            }

            if (!categoryIds.isNullOrEmpty()) {
                predicates.add(root.get<DanceType>("danceType").get<DanceCategory>("category").get<UUID>("id").`in`(categoryIds))
            }

            danceClass?.let {
                predicates.add(cb.equal(root.get<DanceClass>("danceClass"), it))
            }

            if (!nameSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%${nameSearch.lowercase()}%"))
            }

            hasSteps?.let {
                val subquery = query!!.subquery(Long::class.java)
                val subRoot = subquery.from(com.jankowski.rafal.dancebook.model.DanceFigureStep::class.java)
                subquery.select(cb.count(subRoot))
                subquery.where(cb.equal(subRoot.get<com.jankowski.rafal.dancebook.model.DanceFigureVariation>("danceFigureVariation").get<DanceFigure>("danceFigure"), root))
                if (it) {
                    predicates.add(cb.gt(subquery, 0L))
                } else {
                    predicates.add(cb.equal(subquery, 0L))
                }
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
