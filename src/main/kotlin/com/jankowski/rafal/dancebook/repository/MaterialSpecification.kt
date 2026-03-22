package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Material
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object MaterialSpecification {

    fun withFilters(
        typeId: UUID?,
        categoryId: UUID?,
        rating: Short?
    ): Specification<Material> {
        return Specification { root, _, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()

            typeId?.let {
                predicates.add(cb.equal(root.get<UUID>("danceType").get<UUID>("id"), it))
            }

            categoryId?.let {
                predicates.add(cb.equal(root.get<UUID>("danceCategory").get<UUID>("id"), it))
            }

            rating?.let {
                predicates.add(cb.equal(root.get<Short>("rating"), it))
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
