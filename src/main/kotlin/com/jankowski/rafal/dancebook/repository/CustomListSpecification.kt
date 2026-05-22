package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import jakarta.persistence.criteria.Join
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object CustomListSpecification {

    fun withFilters(
        owner: AppUser,
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        nameSearch: String? = null
    ): Specification<CustomList> {
        return Specification { root, query, cb ->
            val predicates = mutableListOf<Predicate>()

            // 1. Visibility: owner == owner OR isPublic == true
            val visibilityPredicate = cb.or(
                cb.equal(root.get<AppUser>("owner"), owner),
                cb.equal(root.get<Boolean>("isPublic"), true)
            )
            predicates.add(visibilityPredicate)

            // 2. Filter by Dance Types
            if (!typeIds.isNullOrEmpty()) {
                val danceTypesJoin: Join<CustomList, DanceType> = root.join("danceTypes")
                predicates.add(danceTypesJoin.get<UUID>("id").`in`(typeIds))
            }

            // 3. Filter by Categories
            if (!categoryIds.isNullOrEmpty()) {
                val danceCategoriesJoin: Join<CustomList, DanceCategory> = root.join("danceCategories")
                predicates.add(danceCategoriesJoin.get<UUID>("id").`in`(categoryIds))
            }

            // 4. Search by Name
            if (!nameSearch.isNullOrBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%${nameSearch.lowercase()}%"))
            }

            query?.distinct(true)

            cb.and(*predicates.toTypedArray())
        }
    }
}
