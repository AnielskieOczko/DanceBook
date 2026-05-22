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
        nameSearch: String? = null
    ): Specification<DanceFigure> {
        return Specification { root, _, cb ->
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

            cb.and(*predicates.toTypedArray())
        }
    }
}
