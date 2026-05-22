package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object DanceFigureSpecification {

    fun withFilters(
        danceTypeId: UUID? = null,
        danceClass: DanceClass? = null,
        nameSearch: String? = null
    ): Specification<DanceFigure> {
        return Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()

            danceTypeId?.let {
                predicates.add(cb.equal(root.get<DanceType>("danceType").get<UUID>("id"), it))
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
