package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object MaterialSpecification {

    fun withFilters(
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
                predicates.add(root.get<DanceCategory>("danceCategory").get<UUID>("id").`in`(categoryIds))
            }

            minRating?.let {
                predicates.add(cb.greaterThanOrEqualTo(root.get("rating"), it))
            }

            if (!nameSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%${nameSearch.lowercase()}%"))
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
