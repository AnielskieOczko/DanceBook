package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import jakarta.persistence.criteria.Join
import org.springframework.data.jpa.domain.Specification

object CommentSpecification {

    fun visibleTo(user: AppUser?): Specification<Comment> {
        return Specification { root, query, cb ->
            if (user?.role == Role.ADMIN) {
                cb.conjunction()
            } else {
                val materialJoin: Join<Comment, Material> = root.join("material")
                MaterialSpecification.visibilityPredicate(materialJoin, query, cb, user)
            }
        }
    }
}
